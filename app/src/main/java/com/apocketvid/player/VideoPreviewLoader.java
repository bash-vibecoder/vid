package com.apocketvid.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The UI process only reads JPEGs; native extraction runs in the private preview service. */
final class VideoPreviewLoader {
    static final int MAX_EDGE = PreviewProtocol.MAX_EDGE;
    private static final long RETRY_DELAY_MS = 24L * 60 * 60 * 1000;

    static final class Source {
        final Uri uri;
        final long duration;
        final String key;
        Source(Uri uri, long size, long modified, long duration) {
            this.uri = uri;
            this.duration = duration;
            key = PreviewDiskCache.keyFor(uri.toString(), size, modified, duration);
        }
    }

    private static final class Job {
        final Source source;
        final ArrayList<WeakReference<ImageView>> targets = new ArrayList<>();
        boolean visible;
        boolean awaitingDecoder;
        Job(Source source) { this.source = source; }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + 5);
        task.run();
    }, "preview-cache"));
    private final PreviewDiskCache disk;
    private final SharedPreferences failureTimes;
    private final PreviewFailures failures = new PreviewFailures();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final LinkedHashMap<String, Job> pending = new LinkedHashMap<>();
    private final Set<String> available = new HashSet<>();
    private Job current;
    private volatile boolean paused;
    private boolean closed;
    private boolean bound;
    private Messenger decoder;

    private final Runnable decoderTimeout = () -> decoderFailed(true);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed || !bound) return;
            decoder = new Messenger(binder);
            if (current != null && current.awaitingDecoder) sendToDecoder(current);
        }
        @Override public void onServiceDisconnected(ComponentName name) { decoderFailed(true); }
        @Override public void onBindingDied(ComponentName name) { decoderFailed(true); }
        @Override public void onNullBinding(ComponentName name) { decoderFailed(false); }
    };

    VideoPreviewLoader(Context context) {
        this.context = context.getApplicationContext();
        disk = new PreviewDiskCache(new File(context.getFilesDir(), "video-previews-v2"), 96L * 1024 * 1024);
        failureTimes = context.getSharedPreferences("preview-failures-v1", Context.MODE_PRIVATE);
    }

    void load(Source source, ImageView target, int fallbackIcon) {
        target.setTag(source.key);
        Bitmap bitmap = memory.get(source.key);
        if (bitmap != null) { bind(target, bitmap); return; }
        target.setImageResource(fallbackIcon);
        target.setImageTintList(ColorStateList.valueOf(Color.rgb(132, 140, 137)));
        if (closed) return;
        Job job = enqueue(source);
        job.visible = true;
        job.targets.add(new WeakReference<>(target));
        startNext();
    }

    void prefetch(ArrayList<Source> sources) {
        if (closed) return;
        for (Source source : sources) if (!available.contains(source.key)) enqueue(source);
        startNext();
    }

    void setPlaybackActive(boolean active) {
        paused = active;
        if (!paused) startNext();
    }

    boolean hasPreview(Source source) { return available.contains(source.key); }

    void storePlaybackFrame(Source source, Bitmap bitmap) {
        if (closed) { bitmap.recycle(); return; }
        available.add(source.key);
        failures.recovered(source.key);
        failureTimes.edit().remove(source.key).apply();
        memory.put(source.key, bitmap);
        Job queued = pending.remove(source.key);
        if (queued != null) deliver(queued, bitmap);
        if (current != null && current.source.key.equals(source.key)) deliver(current, bitmap);
        worker.execute(() -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) save(source, output.toByteArray());
        });
    }

    void close() {
        closed = true;
        main.removeCallbacks(decoderTimeout);
        unbindDecoder();
        pending.clear();
        memory.evictAll();
        worker.shutdown();
    }

    private Job enqueue(Source source) {
        if (current != null && current.source.key.equals(source.key)) return current;
        Job job = pending.get(source.key);
        if (job == null) { job = new Job(source); pending.put(source.key, job); }
        return job;
    }

    private void startNext() {
        if (closed || paused || current != null || pending.isEmpty()) return;
        Job next = null;
        for (Job job : pending.values()) if (job.visible) { next = job; break; }
        if (next == null) next = pending.values().iterator().next();
        pending.remove(next.source.key);
        current = next;
        Job job = next;
        worker.execute(() -> {
            Bitmap bitmap = readCached(job.source);
            main.post(() -> {
                if (closed || current != job) { if (bitmap != null) bitmap.recycle(); return; }
                if (bitmap != null) finish(job, bitmap);
                else if (paused) {
                    current = null;
                    pending.put(job.source.key, job);
                } else requestDecode(job);
            });
        });
    }

    private void requestDecode(Job job) {
        long failedAt = failureTimes.getLong(job.source.key, 0);
        if (!failures.canExtract(job.source.key)
                || (failedAt > 0 && System.currentTimeMillis() - failedAt < RETRY_DELAY_MS)) {
            finish(job, null);
            return;
        }
        job.awaitingDecoder = true;
        main.removeCallbacks(decoderTimeout);
        main.postDelayed(decoderTimeout, 20000);
        if (decoder != null) sendToDecoder(job);
        else if (!bound) {
            try {
                bound = context.bindService(new Intent(context, PreviewService.class), connection, Context.BIND_AUTO_CREATE);
                if (!bound) decoderFailed(false);
            } catch (RuntimeException error) { decoderFailed(false); }
        }
    }

    private void sendToDecoder(Job job) {
        if (decoder == null || closed || current != job) return;
        Message request = Message.obtain(null, PreviewProtocol.EXTRACT);
        Bundle data = new Bundle();
        data.putString(PreviewProtocol.KEY, job.source.key);
        data.putString(PreviewProtocol.URI, job.source.uri.toString());
        data.putLong(PreviewProtocol.DURATION, job.source.duration);
        request.setData(data);
        request.replyTo = inbox;
        try { decoder.send(request); }
        catch (RemoteException | RuntimeException error) { decoderFailed(true); }
    }

    private boolean receive(Message message) {
        if (message.what != PreviewProtocol.RESULT || closed || current == null) return true;
        Bundle data = message.getData();
        Job job = current;
        if (!job.awaitingDecoder || !job.source.key.equals(data.getString(PreviewProtocol.KEY))) return true;
        main.removeCallbacks(decoderTimeout);
        job.awaitingDecoder = false;
        byte[] jpeg = data.getByteArray(PreviewProtocol.JPEG);
        if (jpeg == null || jpeg.length == 0 || jpeg.length > 512 * 1024) {
            markFailed(job, false);
            finish(job, null);
            return true;
        }
        worker.execute(() -> {
            save(job.source, jpeg);
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            main.post(() -> finish(job, bitmap));
        });
        return true;
    }

    private void decoderFailed(boolean crashed) {
        main.removeCallbacks(decoderTimeout);
        unbindDecoder();
        if (closed || current == null || !current.awaitingDecoder) return;
        Job job = current;
        job.awaitingDecoder = false;
        markFailed(job, crashed);
        finish(job, null);
    }

    private void markFailed(Job job, boolean crashed) {
        failures.failed(job.source.key, crashed);
        failureTimes.edit().putLong(job.source.key, System.currentTimeMillis()).apply();
    }

    private void unbindDecoder() {
        decoder = null;
        if (bound) {
            bound = false;
            try { context.unbindService(connection); } catch (RuntimeException ignored) {}
        }
    }

    private void finish(Job job, Bitmap bitmap) {
        if (closed || current != job) { if (bitmap != null) bitmap.recycle(); return; }
        current = null;
        Bitmap captured = memory.get(job.source.key);
        if (captured != null) {
            if (bitmap != null && bitmap != captured) bitmap.recycle();
            bitmap = captured;
        }
        if (bitmap != null) {
            available.add(job.source.key);
            memory.put(job.source.key, bitmap);
            deliver(job, bitmap);
        }
        startNext();
    }

    private void deliver(Job job, Bitmap bitmap) {
        for (WeakReference<ImageView> reference : job.targets) {
            ImageView target = reference.get();
            if (target != null && job.source.key.equals(target.getTag())) bind(target, bitmap);
        }
    }

    private static void bind(ImageView target, Bitmap bitmap) {
        target.setImageTintList(null);
        target.setImageBitmap(bitmap);
    }

    private Bitmap readCached(Source source) {
        File cached = disk.file(source.key);
        if (!cached.isFile()) return null;
        Bitmap bitmap = BitmapFactory.decodeFile(cached.getAbsolutePath());
        if (bitmap == null) cached.delete();
        else cached.setLastModified(System.currentTimeMillis());
        return bitmap;
    }

    private void save(Source source, byte[] jpeg) {
        try { disk.put(source.key, jpeg); } catch (IOException | RuntimeException ignored) {}
    }

    static boolean hasVisibleContent(Bitmap bitmap) {
        int nonBlack = 0;
        for (int y = 1; y <= 6; y++) for (int x = 1; x <= 6; x++) {
            int pixel = bitmap.getPixel(Math.min(bitmap.getWidth() - 1, bitmap.getWidth() * x / 7),
                    Math.min(bitmap.getHeight() - 1, bitmap.getHeight() * y / 7));
            if (Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel))) > 24) nonBlack++;
        }
        return nonBlack >= 2;
    }
}
