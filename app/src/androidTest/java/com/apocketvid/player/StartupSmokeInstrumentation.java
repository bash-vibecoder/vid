package com.apocketvid.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.ImageView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
                    .clear().putBoolean("mediaPermissionRequested", true).putBoolean("batteryPromptShown", true).commit();
            File fixture = new File(target.getFilesDir(), "preview-fixture.mp4");
            try (InputStream input = getContext().getAssets().open("preview-fixture.mp4");
                 FileOutputStream output = new FileOutputStream(fixture)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            }
            activity = startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            onMain(() -> {
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
                activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
            waitForIdleSync();
            awaitVisibility(true);
            // Keep the Activity's library scan from competing with the controlled fixture client.
            Field libraryPreviews = MainActivity.class.getDeclaredField("previewLoader");
            libraryPreviews.setAccessible(true);
            onMain(() -> {
                try { ((VideoPreviewLoader) libraryPreviews.get(activity)).close(); }
                catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            });
            Thread.sleep(350);
            assertActivityAlive();
            passed(report, "launch");

            AtomicReference<ImageView> first = new AtomicReference<>();
            VideoPreviewLoader.Source source = source(fixture);
            onMain(() -> {
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
            onMain(() -> watching = target.bindService(new Intent(target, PreviewService.class), watch, Context.BIND_AUTO_CREATE));
            if (!watching || !connected.await(10, TimeUnit.SECONDS)) throw new AssertionError("Preview service did not bind");
            try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "validation-phase.txt"))) {
                output.write("READY_TO_TERMINATE_PREVIEW_PROCESS".getBytes("UTF-8"));
            }
            if (!died.await(25, TimeUnit.SECONDS)) throw new AssertionError("Preview process termination was not observed");
            assertActivityAlive();
            passed(report, "survivesPreviewProcessDeath");
            onMain(() -> { target.unbindService(watch); watching = false; previews.close(); });

            AtomicReference<ImageView> cached = new AtomicReference<>();
            onMain(() -> {
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
            onMain(() -> {
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
            onMain(() -> invoke(open, video, 6000));
            Field prepared = MainActivity.class.getDeclaredField("mediaPrepared");
            prepared.setAccessible(true);
            long deadline = SystemClock.uptimeMillis() + 12000;
            AtomicBoolean playing = new AtomicBoolean();
            while (!playing.get() && SystemClock.uptimeMillis() < deadline) {
                onMain(() -> {
                    try { playing.set(prepared.getBoolean(activity)); }
                    catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                });
                Thread.sleep(100);
            }
            if (!playing.get()) throw new AssertionError("Bundled video playback did not start");
            Method position = MainActivity.class.getDeclaredMethod("safePosition");
            position.setAccessible(true);
            AtomicReference<Integer> time = new AtomicReference<>(0);
            long progressDeadline = SystemClock.uptimeMillis() + 10000;
            while (time.get() <= 0 && SystemClock.uptimeMillis() < progressDeadline) {
                onMain(() -> time.set((Integer) invoke(position)));
                Thread.sleep(100);
            }
            if (time.get() <= 0) throw new AssertionError("Playback did not advance");
            passed(report, "bundledVideoPlayback");
            awaitVideoFrame();
            passed(report, "renderedVideo");
            checkTouchLock();
            passed(report, "touchLockBlocksAccidentalInput");
            onMain(() -> activity.moveTaskToBack(true));
            awaitVisibility(false);
            Thread.sleep(350);
            onMain(() -> target.startActivity(new Intent(target, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            awaitVisibility(true);
            awaitVideoFrame();
            passed(report, "backgroundSurfaceRecovery");
            onMain(() -> {
                checkLocked();
                tapCenter((View) field("unlockButton"));
            });
            waitForIdleSync();
            onMain(() -> {
                require(!Boolean.TRUE.equals(field("touchLocked")), "Top-left lock did not unlock playback");
                require(((View) field("controlsBottom")).getVisibility() == View.VISIBLE, "Unlock did not restore controls");
            });
            passed(report, "touchLockSurvivesBackground");
            checkResumeHighlights(video, constructor, secondFixture);
            passed(report, "resumeHighlightsAndRecycledRows");
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

    private void checkTouchLock() throws Exception {
        onMain(() -> {
            call("setControlsVisible", new Class<?>[] {boolean.class}, true);
            tapCenter((View) field("playPauseButton"));
        });
        waitForIdleSync();
        Thread.sleep(350);
        AtomicReference<Integer> position = new AtomicReference<>();
        AtomicReference<Integer> volume = new AtomicReference<>();
        AtomicReference<Float> brightness = new AtomicReference<>();
        onMain(() -> {
            position.set((Integer) call("safePosition", new Class<?>[0]));
            volume.set(((AudioManager) field("audioManager")).getStreamVolume(AudioManager.STREAM_MUSIC));
            brightness.set(activity.getWindow().getAttributes().screenBrightness);
            View lock = findDescription((View) field("controlsTop"), "Lock playback touches");
            require(lock != null && lock.isShown(), "Lock control is unavailable");
            tapCenter(lock);
        });
        waitForIdleSync();
        onMain(() -> {
            checkLocked();
            Bundle saved = new Bundle();
            callActivityOnSaveInstanceState(activity, saved);
            require(saved.getBoolean("touchLocked"), "Touch lock was not saved");
            View root = (View) field("playerRoot");
            float width = root.getWidth(), height = root.getHeight();
            touch(root, width * .5f, height * .5f, width * .5f, height * .5f);
            touch(root, width * .85f, height * .5f, width * .85f, height * .5f);
            touch(root, width * .85f, height * .5f, width * .85f, height * .5f);
            touch(root, width * .3f, height * .5f, width * .7f, height * .5f);
            touch(root, width * .2f, height * .8f, width * .2f, height * .3f);
            touch(root, width * .8f, height * .8f, width * .8f, height * .3f);
            touch(root, width * .5f, height * .95f, width * .5f, height * .95f);
            call("setControlsVisible", new Class<?>[] {boolean.class}, true);
            activity.onBackPressed();
        });
        // Allow any delayed single-tap callback to run before checking the locked state.
        Thread.sleep(500);
        onMain(() -> {
            checkLocked();
            require(Math.abs((Integer) call("safePosition", new Class<?>[0]) - position.get()) < 600,
                    "Locked touches changed playback position");
            require(!((org.videolan.libvlc.MediaPlayer) field("mediaPlayer")).isPlaying(), "Locked double tap resumed playback");
            require(((AudioManager) field("audioManager")).getStreamVolume(AudioManager.STREAM_MUSIC) == volume.get(),
                    "Locked swipe changed volume");
            require(activity.getWindow().getAttributes().screenBrightness == brightness.get(), "Locked swipe changed brightness");
            tapCenter((View) field("unlockButton"));
        });
        waitForIdleSync();
        onMain(() -> {
            require(!Boolean.TRUE.equals(field("touchLocked")), "Unlock button did not work");
            tapCenter((View) field("playPauseButton"));
        });
        waitForIdleSync();
        onMain(() -> {
            tapCenter(findDescription((View) field("controlsTop"), "Lock playback touches"));
        });
        waitForIdleSync();
        onMain(this::checkLocked);
    }

    private void checkLocked() {
        require(Boolean.TRUE.equals(field("touchLocked")), "Playback touch lock was lost");
        require(((View) field("touchLockOverlay")).isShown(), "Touch guard is hidden");
        require(((View) field("unlockButton")).isShown(), "Unlock button is hidden");
        require(((View) field("controlsTop")).getVisibility() == View.GONE, "Locked touches revealed top controls");
        require(((View) field("controlsBottom")).getVisibility() == View.GONE, "Locked touches revealed bottom controls");
        require(((View) field("adjustmentHud")).getVisibility() == View.GONE, "Locked touches revealed gesture feedback");
    }

    @SuppressWarnings("unchecked")
    private void checkResumeHighlights(Object video, Constructor<?> constructor, File secondFixture) throws Exception {
        Object second = constructor.newInstance(Uri.fromFile(secondFixture), "Other video", "Validation", 30000L, secondFixture.length());
        Uri unknownUri = Uri.fromFile(new File(target.getFilesDir(), "unknown-duration.mp4"));
        Object unknown = constructor.newInstance(unknownUri, "Unknown duration", "Other folder", 0L, 1L);
        onMain(() -> {
            ArrayList<Object> library = (ArrayList<Object>) field("videos");
            library.clear();
            library.add(video);
            library.add(second);
            library.add(unknown);
            call("showLibrary", new Class<?>[0]);
            SharedPreferences prefs = target.getSharedPreferences("player", Context.MODE_PRIVATE);
            require(prefs.contains("resumePosition:Validation"), "Playback did not save a resumable position");
            require("Validation".equals(prefs.getString("lastPlayedFolder", null)), "Playback did not save the latest folder");
            ListView list = (ListView) field("videoList");
            BaseAdapter folders = (BaseAdapter) field("folderAdapter");
            View row = folders.getView(1, null, list);
            assertRowColor(row, "Validation", true);
            row = folders.getView(0, row, list);
            assertRowColor(row, "Other folder", false);
            call("enterFolder", new Class<?>[] {String.class}, "Validation");
            BaseAdapter videos = (BaseAdapter) field("adapter");
            row = videos.getView(1, null, list);
            assertRowColor(row, "Validation video", true);
            row = videos.getView(0, row, list);
            assertRowColor(row, "Other video", false);
            // Each folder keeps its video highlight; only the latest home folder stays orange.
            prefs.edit().putString("resumeUri:Validation", Uri.fromFile(secondFixture).toString())
                    .putString("lastPlayedUri:Validation", Uri.fromFile(secondFixture).toString())
                    .putInt("resumePosition:Validation", 200)
                    .putString("resumeUri:Other folder", unknownUri.toString()).putInt("resumePosition:Other folder", 5000)
                    .putString("lastPlayedFolder", "Other folder")
                    .putString("lastUri", unknownUri.toString()).putInt("lastPosition", 5000).apply();
            call("applyFilters", new Class<?>[0]);
            row = videos.getView(0, row, list);
            assertRowColor(row, "Other video", true);
            row = videos.getView(1, row, list);
            assertRowColor(row, "Validation video", false);
            call("enterFolder", new Class<?>[] {String.class}, "Other folder");
            assertRowColor(videos.getView(0, null, list), "Unknown duration", true);
            activity.onBackPressed();
            row = folders.getView(0, null, list);
            assertRowColor(row, "Other folder", true);
            row = folders.getView(1, row, list);
            assertRowColor(row, "Validation", false);
            require(prefs.getInt("resumePosition:Validation", -1) == 200
                    && prefs.getInt("resumePosition:Other folder", -1) == 5000, "Switching folders lost a saved position");
            // An upgrade uses the previous global folder until another video is played.
            prefs.edit().remove("lastPlayedFolder").putString("lastFolder", "Other folder").apply();
            assertRowColor(folders.getView(0, row, list), "Other folder", true);
            row = folders.getView(1, null, list);
            assertRowColor(row, "Validation", false);
            // Switching back moves the home highlight without clearing the other folder's resume.
            prefs.edit().putInt("resumePosition:Validation", 29900)
                    .putString("lastPlayedFolder", "Validation")
                    .putString("lastUri", Uri.fromFile(secondFixture).toString()).putInt("lastPosition", 29900).apply();
            call("applyFilters", new Class<?>[0]);
            assertRowColor(folders.getView(0, row, list), "Other folder", false);
            View completedFolder = folders.getView(1, row, list);
            assertRowColor(completedFolder, "Validation", true);
            View resume = findDescription(completedFolder, "Resume Validation");
            require(resume != null && resume.getVisibility() != View.VISIBLE, "Completed video still has a resume action");
            call("enterFolder", new Class<?>[] {String.class}, "Validation");
            assertRowColor(videos.getView(0, null, list), "Other video", true);
            call("clearResumePosition", new Class<?>[] {second.getClass()}, second);
            activity.onBackPressed();
            assertRowColor(folders.getView(1, null, list), "Validation", true);
            require(!prefs.contains("lastUri"), "Completion did not clear the global resume target");
            require(prefs.getInt("resumePosition:Other folder", -1) == 5000, "Completion cleared another folder's resume");
            require(!Boolean.TRUE.equals(field("touchLocked")), "Returning to the library kept touch lock active");
        });
    }

    private void assertRowColor(View row, String title, boolean resume) {
        TextView label = findText(row, title);
        require(label != null, "Missing row: " + title);
        int expected = resume ? Color.rgb(255, 143, 0) : Color.rgb(242, 246, 252);
        require(label.getCurrentTextColor() == expected, "Wrong resume color: " + title);
    }

    private TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void tapCenter(View view) {
        require(view != null && view.isShown(), "Cannot tap a hidden control");
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        View root = activity.getWindow().getDecorView();
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        float x = location[0] - rootLocation[0] + view.getWidth() / 2f;
        float y = location[1] - rootLocation[1] + view.getHeight() / 2f;
        touch(root, x, y, x, y);
    }

    private void touch(View root, float x, float y, float endX, float endY) {
        long down = SystemClock.uptimeMillis();
        dispatchTouch(root, down, down, MotionEvent.ACTION_DOWN, x, y);
        if (x != endX || y != endY) {
            for (int i = 1; i <= 6; i++) {
                dispatchTouch(root, down, down + i * 16, MotionEvent.ACTION_MOVE,
                        x + (endX - x) * i / 6f, y + (endY - y) * i / 6f);
            }
        }
        dispatchTouch(root, down, down + 112, MotionEvent.ACTION_UP, endX, endY);
    }

    private void dispatchTouch(View root, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try { root.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private Object field(String name) {
        try {
            Field value = MainActivity.class.getDeclaredField(name);
            value.setAccessible(true);
            return value.get(activity);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return invoke(method, args);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        super.runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private VideoPreviewLoader.Source source(File fixture) {
        return new VideoPreviewLoader.Source(Uri.fromFile(fixture), fixture.length(), fixture.lastModified(), 30000);
    }

    private void awaitImage(ImageView image, long timeout) throws InterruptedException {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            onMain(() -> ready.set(image.getDrawable() instanceof BitmapDrawable && image.getImageTintList() == null));
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
            onMain(() -> {
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
            onMain(() -> {
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
        onMain(() -> {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) throw new AssertionError("Activity exited");
        });
    }

    private Object invoke(Method method, Object... args) {
        try { return method.invoke(activity, args); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void cleanUp() {
        onMain(() -> {
            if (previews != null) previews.close();
            if (reopened != null) reopened.close();
            if (watching) { target.unbindService(watch); watching = false; }
            if (activity != null) activity.finish();
        });
    }
}
