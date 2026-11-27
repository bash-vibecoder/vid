package com.apocketvid.player;

import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Surface;
import java.io.IOException;

/** A persistent frame cache and decoder, isolated from playback and thumbnail extraction. */
public final class ScrubService extends Service {
    static final int OPEN = 1, TARGET = 2, READY = 3, FRAME = 4, FAILED = 5;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private HandlerThread thread;
    private Handler worker;
    private volatile int generation;
    private volatile int positionMs;
    private volatile boolean display;
    private volatile long heartbeat;
    private long decoder;
    private int reportedPosition = -1;
    private Messenger client;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("scrub-cache", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }

    private boolean receive(Message message) {
        if (message.what == TARGET) {
            positionMs = Math.max(0, message.arg1);
            display = message.arg2 != 0;
        } else if (message.what == OPEN && message.replyTo != null) {
            Bundle data = message.getData();
            data.setClassLoader(Surface.class.getClassLoader());
            Uri uri = Uri.parse(data.getString("uri", ""));
            Surface surface = data.getParcelable("surface");
            if (surface == null || (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme()))) return true;
            client = message.replyTo;
            positionMs = Math.max(0, message.arg1);
            display = false;
            int owner = ++generation;
            worker.post(() -> open(uri, surface, owner));
        }
        return true;
    }

    private void open(Uri uri, Surface surface, int owner) {
        closeDecoder();
        heartbeat = SystemClock.uptimeMillis();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 5000);
        int[] size = new int[2];
        try (AssetFileDescriptor source = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (source != null && owner == generation) {
                decoder = NativeScrub.open(source.getParcelFileDescriptor().getFd(), source.getStartOffset(),
                        source.getDeclaredLength(), surface, size);
            }
        } catch (IOException | RuntimeException | LinkageError error) {
            android.util.Log.w("APocketVid", "Could not open scrub decoder", error);
        } finally { surface.release(); }
        if (decoder == 0 || owner != generation) {
            closeDecoder();
            reply(FAILED, 0, 0);
            return;
        }
        reportedPosition = -1;
        reply(READY, size[0], size[1]);
        pump(owner);
    }

    private void pump(int owner) {
        if (owner != generation || decoder == 0) return;
        heartbeat = SystemClock.uptimeMillis();
        try {
            long renderedUs = NativeScrub.pump(decoder, positionMs * 1000L, display);
            if (display && renderedUs >= 0) {
                int renderedMs = (int) (renderedUs / 1000);
                if (renderedMs != reportedPosition) {
                    reportedPosition = renderedMs;
                    reply(FRAME, renderedMs, 0);
                }
            }
            worker.postDelayed(() -> pump(owner), display ? 4 : renderedUs == -2 ? 150 : 12);
        } catch (RuntimeException | LinkageError error) {
            closeDecoder();
            reply(FAILED, 0, 0);
        }
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (SystemClock.uptimeMillis() - heartbeat > 5000) Process.killProcess(Process.myPid());
            else main.postDelayed(this, 2000);
        }
    };

    private void reply(int what, int first, int second) {
        Messenger reply = client;
        if (reply == null) return;
        try { reply.send(Message.obtain(null, what, first, second)); }
        catch (RemoteException ignored) {}
    }

    private void closeDecoder() {
        if (decoder != 0) NativeScrub.close(decoder);
        decoder = 0;
    }

    @Override public void onDestroy() {
        generation++;
        main.removeCallbacks(watchdog);
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> { closeDecoder(); thread.quitSafely(); });
        super.onDestroy();
    }
}
