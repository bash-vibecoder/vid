package com.apocketvid.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

/** Sends only the latest drag position to the cached decoder, once per display frame. */
final class ScrubPreview implements AutoCloseable {
    interface Listener {
        void onFrame(int positionMs);
        void onFailure();
    }

    private final Context context;
    private final TextureView view;
    private final Uri uri;
    private final Listener listener;
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger service;
    private Surface surface;
    private boolean bound, closed, ready, active, sending;
    private int positionMs;
    private int width, height;
    private int lastSentPosition = -1;
    private boolean lastSentActive;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            service = new Messenger(binder);
            open();
        }
        @Override public void onServiceDisconnected(ComponentName name) { failed(); }
        @Override public void onBindingDied(ComponentName name) { failed(); }
        @Override public void onNullBinding(ComponentName name) { failed(); }
    };

    ScrubPreview(Context context, TextureView view, Uri uri, int positionMs, Listener listener) {
        this.context = context.getApplicationContext();
        this.view = view;
        this.uri = uri;
        this.positionMs = Math.max(0, positionMs);
        this.listener = listener;
        view.setOpaque(false);
        view.setAlpha(0f);
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                surface = new Surface(texture);
                open();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                failed();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        if (view.isAvailable()) surface = new Surface(view.getSurfaceTexture());
        try { bound = this.context.bindService(new Intent(this.context, ScrubService.class), connection, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException ignored) {}
        if (!bound) failed();
    }

    boolean isReady() { return ready && !closed; }
    boolean isAvailable() { return !closed; }

    void position(int positionMs, boolean active) {
        this.positionMs = Math.max(0, positionMs);
        this.active = active;
        if (sending || closed) return;
        sending = true;
        view.postOnAnimation(() -> {
            sending = false;
            sendTarget();
        });
    }

    void hide() {
        active = false;
        view.setAlpha(0f);
        sendTarget();
    }

    private void open() {
        if (closed || surface == null || service == null) return;
        Message message = Message.obtain(null, ScrubService.OPEN, positionMs, 0);
        Bundle data = new Bundle();
        data.putString("uri", uri.toString());
        data.putParcelable("surface", surface);
        message.setData(data);
        message.replyTo = inbox;
        try { service.send(message); }
        catch (RemoteException error) { failed(); }
    }

    private void sendTarget() {
        if (closed || !ready || service == null) return;
        if (lastSentPosition == positionMs && lastSentActive == active) return;
        lastSentPosition = positionMs;
        lastSentActive = active;
        try { service.send(Message.obtain(null, ScrubService.TARGET, positionMs, active ? 1 : 0)); }
        catch (RemoteException error) { failed(); }
    }

    private boolean receive(Message message) {
        if (closed) return true;
        if (message.what == ScrubService.READY) {
            width = message.arg1;
            height = message.arg2;
            ready = true;
            fit();
            sendTarget();
        } else if (message.what == ScrubService.FRAME && active) {
            fit();
            view.setAlpha(1f);
            listener.onFrame(message.arg1);
        } else if (message.what == ScrubService.FAILED) failed();
        return true;
    }

    private void fit() {
        View parent = (View) view.getParent();
        if (parent == null || width <= 0 || height <= 0 || parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
        float scale = Math.min(parent.getWidth() / (float) width, parent.getHeight() / (float) height);
        int fittedWidth = Math.round(width * scale), fittedHeight = Math.round(height * scale);
        if (view.getLayoutParams().width != fittedWidth || view.getLayoutParams().height != fittedHeight) {
            view.setLayoutParams(new FrameLayout.LayoutParams(fittedWidth, fittedHeight, Gravity.CENTER));
        }
    }

    private void failed() {
        if (closed) return;
        close();
        listener.onFailure();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ready = false;
        view.setAlpha(0f);
        view.setSurfaceTextureListener(null);
        if (bound) {
            bound = false;
            try { context.unbindService(connection); } catch (RuntimeException ignored) {}
        }
        if (surface != null) surface.release();
        surface = null;
        service = null;
    }
}
