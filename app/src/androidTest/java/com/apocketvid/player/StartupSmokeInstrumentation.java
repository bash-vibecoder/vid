package com.apocketvid.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Device smoke test for a temporary validation package; requires no test framework dependency. */
public final class StartupSmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private Context target;
    private VideoPreviewLoader previews;
    private VideoPreviewLoader reopened;
    private ServiceConnection watch;
    private boolean watching;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            target = getTargetContext();
            if (!target.getPackageName().equals("com.apocketvid.validation")) {
                throw new AssertionError("This smoke test must use the temporary validation package");
            }
            target.getSharedPreferences("player", Context.MODE_PRIVATE).edit()
                    .putBoolean("mediaPermissionRequested", true).putBoolean("batteryPromptShown", true).commit();
            File fixture = new File(target.getFilesDir(), "preview-fixture.mp4");
            try (InputStream input = getContext().getAssets().open("preview-fixture.mp4");
                 FileOutputStream output = new FileOutputStream(fixture)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            }
            activity = startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            // Keep the Activity's library scan from competing with the controlled fixture client.
            Field libraryPreviews = MainActivity.class.getDeclaredField("previewLoader");
            libraryPreviews.setAccessible(true);
            runOnMainSync(() -> {
                try { ((VideoPreviewLoader) libraryPreviews.get(activity)).close(); }
                catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            });
            Thread.sleep(350);
            assertActivityAlive();
            passed(report, "launch");

            AtomicReference<ImageView> first = new AtomicReference<>();
            VideoPreviewLoader.Source source = source(fixture);
            runOnMainSync(() -> {
                previews = new VideoPreviewLoader(target);
                ImageView image = new ImageView(target);
                first.set(image);
                previews.load(source, image, android.R.drawable.ic_media_play);
            });
            awaitImage(first.get(), 18000);
            File saved = new PreviewDiskCache(new File(target.getFilesDir(), "video-previews-v2"), 96L * 1024 * 1024).file(source.key);
            if (saved.length() == 0) throw new AssertionError("Preview was not saved");
            passed(report, "nativePreview");

            CountDownLatch connected = new CountDownLatch(1);
            CountDownLatch died = new CountDownLatch(1);
            watch = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected.countDown(); }
                @Override public void onServiceDisconnected(ComponentName name) { died.countDown(); }
            };
            runOnMainSync(() -> watching = target.bindService(new Intent(target, PreviewService.class), watch, Context.BIND_AUTO_CREATE));
            if (!watching || !connected.await(10, TimeUnit.SECONDS)) throw new AssertionError("Preview service did not bind");
            try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "validation-phase.txt"))) {
                output.write("READY_TO_TERMINATE_PREVIEW_PROCESS".getBytes("UTF-8"));
            }
            if (!died.await(25, TimeUnit.SECONDS)) throw new AssertionError("Preview process termination was not observed");
            assertActivityAlive();
            passed(report, "survivesPreviewProcessDeath");
            runOnMainSync(() -> { target.unbindService(watch); watching = false; previews.close(); });

            AtomicReference<ImageView> cached = new AtomicReference<>();
            runOnMainSync(() -> {
                reopened = new VideoPreviewLoader(target);
                ImageView image = new ImageView(target);
                cached.set(image);
                reopened.load(source, image, android.R.drawable.ic_media_play);
            });
            awaitImage(cached.get(), 4000);
            passed(report, "persistentPreview");

            File secondFixture = new File(target.getFilesDir(), "preview-fixture-second.mp4");
            try (InputStream input = new java.io.FileInputStream(fixture);
                 FileOutputStream output = new FileOutputStream(secondFixture)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            }
            AtomicReference<ImageView> restarted = new AtomicReference<>();
            runOnMainSync(() -> {
                ImageView image = new ImageView(target);
                restarted.set(image);
                reopened.load(source(secondFixture), image, android.R.drawable.ic_media_play);
            });
            awaitImage(restarted.get(), 18000);
            passed(report, "previewProcessRecovery");

            Class<?> videoClass = Class.forName("com.apocketvid.player.MainActivity$VideoItem");
            Constructor<?> constructor = videoClass.getDeclaredConstructor(Uri.class, String.class, String.class, long.class, long.class);
            constructor.setAccessible(true);
            Object video = constructor.newInstance(Uri.fromFile(fixture), "Validation video", "Validation", 30000L, fixture.length());
            Method open = MainActivity.class.getDeclaredMethod("openVideo", videoClass, int.class);
            open.setAccessible(true);
            runOnMainSync(() -> invoke(open, video, 0));
            Field prepared = MainActivity.class.getDeclaredField("mediaPrepared");
            prepared.setAccessible(true);
            long deadline = SystemClock.uptimeMillis() + 12000;
            AtomicBoolean playing = new AtomicBoolean();
            while (!playing.get() && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync(() -> {
                    try { playing.set(prepared.getBoolean(activity)); }
                    catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                });
                Thread.sleep(100);
            }
            if (!playing.get()) throw new AssertionError("Bundled video playback did not start");
            Thread.sleep(650);
            Method position = MainActivity.class.getDeclaredMethod("safePosition");
            position.setAccessible(true);
            AtomicReference<Integer> time = new AtomicReference<>(0);
            runOnMainSync(() -> time.set((Integer) invoke(position)));
            if (time.get() <= 0) throw new AssertionError("Playback did not advance");
            passed(report, "bundledVideoPlayback");
            awaitVideoFrame();
            passed(report, "renderedVideo");
            runOnMainSync(() -> activity.moveTaskToBack(true));
            awaitVisibility(false);
            Thread.sleep(350);
            runOnMainSync(() -> target.startActivity(new Intent(target, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            awaitVisibility(true);
            awaitVideoFrame();
            passed(report, "backgroundSurfaceRecovery");
            assertActivityAlive();
            report.putString("result", "PASS");
            cleanUp();
            finish(Activity.RESULT_OK, report);
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            report.putString("result", "FAIL");
            report.putString("failure", trace.toString());
            cleanUp();
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private void passed(Bundle report, String check) {
        report.putString(check, "PASS");
        Bundle progress = new Bundle();
        progress.putString(check, "PASS");
        sendStatus(0, progress);
    }

    private VideoPreviewLoader.Source source(File fixture) {
        return new VideoPreviewLoader.Source(Uri.fromFile(fixture), fixture.length(), fixture.lastModified(), 30000);
    }

    private void awaitImage(ImageView image, long timeout) throws InterruptedException {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> ready.set(image.getDrawable() instanceof BitmapDrawable && image.getImageTintList() == null));
            Thread.sleep(100);
        }
        if (!ready.get()) throw new AssertionError("No decoded preview returned within " + timeout + " ms");
    }

    private void awaitVisibility(boolean visible) throws Exception {
        Field state = MainActivity.class.getDeclaredField("activityVisible");
        state.setAccessible(true);
        AtomicBoolean matched = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!matched.get() && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> {
                try { matched.set(state.getBoolean(activity) == visible); }
                catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            });
            Thread.sleep(100);
        }
        if (!matched.get()) throw new AssertionError("Activity visibility did not change to " + visible);
    }

    private void awaitVideoFrame() throws Exception {
        Field layout = MainActivity.class.getDeclaredField("videoLayout");
        layout.setAccessible(true);
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            CountDownLatch copied = new CountDownLatch(1);
            AtomicBoolean visible = new AtomicBoolean();
            Bitmap frame = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
            runOnMainSync(() -> {
                try {
                    SurfaceView surface = findSurface((View) layout.get(activity));
                    if (surface == null || !surface.getHolder().getSurface().isValid()) {
                        copied.countDown();
                        return;
                    }
                    PixelCopy.request(surface, frame, result -> {
                        visible.set(result == PixelCopy.SUCCESS && VideoPreviewLoader.hasVisibleContent(frame));
                        copied.countDown();
                    }, new android.os.Handler(android.os.Looper.getMainLooper()));
                } catch (Exception failure) { copied.countDown(); }
            });
            if (!copied.await(3, TimeUnit.SECONDS)) throw new AssertionError("Video surface did not answer PixelCopy");
            frame.recycle();
            if (visible.get()) return;
            Thread.sleep(150);
        }
        throw new AssertionError("Video surface stayed black");
    }

    private SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView surface = findSurface(group.getChildAt(i));
                if (surface != null) return surface;
            }
        }
        return null;
    }

    private void assertActivityAlive() {
        runOnMainSync(() -> {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) throw new AssertionError("Activity exited");
        });
    }

    private Object invoke(Method method, Object... args) {
        try { return method.invoke(activity, args); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void cleanUp() {
        runOnMainSync(() -> {
            if (previews != null) previews.close();
            if (reopened != null) reopened.close();
            if (watching) { target.unbindService(watch); watching = false; }
            if (activity != null) activity.finish();
        });
    }
}
