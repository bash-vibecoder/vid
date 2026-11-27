package com.apocketvid.player;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;

/** Watches the displayed surface during a drag; VLC statistics refresh only every 250 ms. */
final class SeekFrameMonitor {
    private final Handler handler;
    private final Bitmap sample = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[64 * 36];
    private int signature;
    private int sequence;
    private int reportedFrames = -1;
    private int acknowledgedNativeFrames = -1;
    private long pictureTimeMs = -1;
    private long changedAt;
    private boolean sampled;
    private boolean copying;
    private boolean closed;
    private long requestedAt;

    SeekFrameMonitor(Handler handler) { this.handler = handler; }

    int sample(SurfaceView surface, int nativeFrames, long playbackTimeMs) {
        reportedFrames = nativeFrames;
        long now = SystemClock.uptimeMillis();
        if (!closed && !copying && surface != null && surface.getHolder().getSurface().isValid()
                && now - requestedAt >= 32) {
            copying = true;
            requestedAt = now;
            try {
                PixelCopy.request(surface, sample, result -> {
                    copying = false;
                    if (closed) { sample.recycle(); return; }
                    if (result != PixelCopy.SUCCESS) return;
                    sample.getPixels(pixels, 0, 64, 0, 0, 64, 36);
                    int next = java.util.Arrays.hashCode(pixels);
                    long capturedAt = SystemClock.uptimeMillis();
                    if (!sampled) {
                        changedAt = capturedAt;
                        acknowledgedNativeFrames = reportedFrames;
                    } else if (next != signature) {
                        sequence++;
                        changedAt = capturedAt;
                        acknowledgedNativeFrames = reportedFrames;
                    } else if (capturedAt - changedAt >= 250 && reportedFrames > 0
                            && reportedFrames != acknowledgedNativeFrames) {
                        // A static picture still progresses when VLC confirms another frame.
                        sequence++;
                        changedAt = capturedAt;
                        acknowledgedNativeFrames = reportedFrames;
                    }
                    pictureTimeMs = playbackTimeMs;
                    sampled = true;
                    signature = next;
                }, handler);
            } catch (IllegalArgumentException error) {
                copying = false;
            }
        }
        return sequence;
    }

    long pictureTimeMs() { return pictureTimeMs; }

    void close() {
        if (closed) return;
        closed = true;
        if (!copying) sample.recycle();
    }
}
