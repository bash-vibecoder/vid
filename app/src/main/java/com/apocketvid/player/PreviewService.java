package com.apocketvid.player;

import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
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
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/** Loads the preview codec only in the private :previews process, never in the player. */
public final class PreviewService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + 5);
        task.run();
    }, "preview-decoder"));
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private boolean busy;

    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }

    private boolean receive(Message message) {
        if (message.what != PreviewProtocol.EXTRACT || message.replyTo == null || busy) return true;
        Bundle request = message.getData();
        String key = request.getString(PreviewProtocol.KEY);
        String location = request.getString(PreviewProtocol.URI);
        if (key == null || location == null) return true;
        Uri uri = Uri.parse(location);
        if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) return true;
        Messenger reply = message.replyTo;
        long duration = Math.max(0, request.getLong(PreviewProtocol.DURATION));
        busy = true;
        // A wedged native codec can terminate this worker process without touching the UI.
        Runnable timeout = () -> Process.killProcess(Process.myPid());
        main.postDelayed(timeout, 15000);
        worker.execute(() -> {
            byte[] jpeg = null;
            try {
                jpeg = extract(uri, duration);
            } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError error) {
                Log.w("APocketVid", "Preview extraction failed", error);
            }
            byte[] image = jpeg;
            main.post(() -> {
                main.removeCallbacks(timeout);
                busy = false;
                Message result = Message.obtain(null, PreviewProtocol.RESULT);
                Bundle data = new Bundle();
                data.putString(PreviewProtocol.KEY, key);
                if (image != null) data.putByteArray(PreviewProtocol.JPEG, image);
                result.setData(data);
                try { reply.send(result); } catch (RemoteException ignored) {}
            });
        });
        return true;
    }

    private byte[] extract(Uri uri, long duration) throws IOException {
        Bitmap bitmap = null;
        try (AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) return null;
            bitmap = NativePreview.frame(descriptor, Math.min(1_000_000L, duration * 500));
            if (bitmap == null || !hasVisibleContent(bitmap)) {
                Bitmap later = NativePreview.frame(descriptor, Math.min(10_000_000L, duration * 1000 / 3));
                if (later != null) {
                    if (bitmap != null && bitmap != later) bitmap.recycle();
                    bitmap = later;
                }
            }
            if (bitmap == null) bitmap = NativePreview.frame(descriptor, 0);
            if (bitmap == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output) || output.size() > 512 * 1024) return null;
            return output.toByteArray();
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
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

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
        if (busy) Process.killProcess(Process.myPid());
    }
}
