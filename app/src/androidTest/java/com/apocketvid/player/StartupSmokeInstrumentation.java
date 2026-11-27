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
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
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
    private boolean savedClockPreview;

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
            onMain(this::checkTransportLayout);
            savePlayerScreenshot("player-controls");
            passed(report, "centeredTransportLayout");
            checkLongSubtitleList(video, constructor);
            passed(report, "subtitleListOpensAtOffWithManyTracks");
            checkLiveSeek();
            passed(report, "swipeSeekPreviewsBeforeRelease");
            checkContinuousSeek(video, constructor);
            passed(report, "continuousSwipeSeekRendersAllDecodersBothDirections");
            passed(report, "seekTimerMatchesDecodedFrames");
            passed(report, "seekingKeepsPlaybackAndVolumeEnabled");
            checkBoostedVolume();
            passed(report, "hardwarePlusVolumeBoost");
            checkSubtitleSelection();
            passed(report, "subtitleSelectionMatchesPlayback");
            passed(report, "transparentRightSideTrackPanels");
            checkAudioSyncPanel();
            passed(report, "editableAudioSyncOffset");
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
                require(!Boolean.TRUE.equals(field("touchLocked")), "Bottom-left lock did not unlock playback");
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

    private void checkTransportLayout() {
        call("setControlsVisible", new Class<?>[] {boolean.class}, true);
        View previous = (View) field("previousVideoButton");
        View play = (View) field("playPauseButton");
        View next = (View) field("nextVideoButton");
        View root = (View) field("playerRoot");
        float density = activity.getResources().getDisplayMetrics().density;
        int[] previousPosition = new int[2], playPosition = new int[2], nextPosition = new int[2], rootPosition = new int[2];
        previous.getLocationOnScreen(previousPosition);
        play.getLocationOnScreen(playPosition);
        next.getLocationOnScreen(nextPosition);
        root.getLocationOnScreen(rootPosition);
        float transportCenter = (previousPosition[0] + nextPosition[0] + next.getWidth()) / 2f;
        require(Math.abs(playPosition[0] + play.getWidth() / 2f - transportCenter) <= density,
                "Play is not centered in the transport strip");
        require(previous.getWidth() >= 48 * density && play.getHeight() >= 56 * density,
                "Transport touch targets are too small");
        require(nextPosition[0] + next.getWidth() - previousPosition[0] >= root.getWidth() - 100 * density,
                "Transport rectangles do not fill the bottom bar outside the lock corner");
        require(rootPosition[0] + root.getWidth() - nextPosition[0] - next.getWidth() <= 20 * density,
                "Transport leaves too much empty space on the right");
        int firstGap = playPosition[0] - previousPosition[0] - previous.getWidth();
        int secondGap = nextPosition[0] - playPosition[0] - play.getWidth();
        require(firstGap >= 0 && firstGap <= 6 * density && secondGap >= 0 && secondGap <= 6 * density,
                "Transport rectangles do not extend toward one another");
        View lock = (View) field("lockButton");
        int[] lockPosition = new int[2];
        lock.getLocationOnScreen(lockPosition);
        require(lockPosition[0] + lock.getWidth() < previousPosition[0], "Transport overlaps the bottom-left lock");
        for (View view : new View[] {previous, play, next}) {
            ImageView icon = (ImageView) view;
            android.graphics.drawable.Drawable drawable = icon.getDrawable();
            android.graphics.RectF bounds = new android.graphics.RectF(0, 0,
                    drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            icon.getImageMatrix().mapRect(bounds);
            require(Math.abs(icon.getPaddingLeft() + bounds.centerX() - icon.getWidth() / 2f) <= density,
                    "Playback icon is not centered horizontally inside its rectangle");
            require(Math.abs(icon.getPaddingTop() + bounds.centerY() - icon.getHeight() / 2f) <= density,
                    "Playback icon is not centered vertically inside its rectangle");
        }
    }

    private void checkLiveSeek() throws Exception {
        AtomicReference<Long> down = new AtomicReference<>();
        AtomicReference<Integer> start = new AtomicReference<>();
        onMain(() -> {
            call("setControlsVisible", new Class<?>[] {boolean.class}, false);
            View root = (View) field("playerRoot");
            start.set((Integer) call("safePosition", new Class<?>[0]));
            down.set(SystemClock.uptimeMillis());
            dispatchTouch(root, down.get(), down.get(), MotionEvent.ACTION_DOWN, root.getWidth() * .25f, root.getHeight() * .5f);
            dispatchTouch(root, down.get(), down.get() + 32, MotionEvent.ACTION_MOVE, root.getWidth() * .5f, root.getHeight() * .5f);
        });
        try {
            long deadline = SystemClock.uptimeMillis() + 1800;
            AtomicBoolean previewed = new AtomicBoolean();
            while (!previewed.get() && SystemClock.uptimeMillis() < deadline) {
                onMain(() -> previewed.set((Integer) field("displayedSeekPositionMs") > start.get() + 3000));
                Thread.sleep(60);
            }
            require(previewed.get(), "Video did not seek while the finger was still down");
            onMain(() -> require(!((View) field("lockButton")).isShown(), "Seeking revealed the lock without the overlay"));
            awaitVideoFrame();
            savePlayerScreenshot("live-seek");
        } finally {
            onMain(() -> {
                View root = (View) field("playerRoot");
                dispatchTouch(root, down.get(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        root.getWidth() * .5f, root.getHeight() * .5f);
            });
        }
        onMain(() -> require(!Boolean.TRUE.equals(field("seeking")), "Seek gesture did not finish"));
    }

    private void checkContinuousSeek(Object originalVideo, Constructor<?> constructor) throws Exception {
        File stress = new File(target.getFilesDir(), "seek-stress.mp4");
        try (InputStream input = getContext().getAssets().open("seek-stress.mp4");
             FileOutputStream output = new FileOutputStream(stress)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        Object video = constructor.newInstance(Uri.fromFile(stress), "Continuous seek validation", "Validation", 40000L, stress.length());
        Method open = MainActivity.class.getDeclaredMethod("openVideo", video.getClass(), int.class);
        open.setAccessible(true);
        try {
            // Start at zero so the initial VLC resume jump cannot look like a drag seek.
            onMain(() -> invoke(open, video, 0));
            awaitPlayerPrepared();
            long previewDeadline = SystemClock.uptimeMillis() + 5000;
            AtomicBoolean previewReady = new AtomicBoolean();
            while (!previewReady.get() && SystemClock.uptimeMillis() < previewDeadline) {
                onMain(() -> {
                    ScrubPreview preview = (ScrubPreview) field("scrubPreview");
                    previewReady.set(preview != null && preview.isReady());
                });
                Thread.sleep(20);
            }
            require(previewReady.get(), "Scrub decoder did not start");
            checkScrubDirection(-1, 1);
            for (int mode : new int[] {0, 1, 2}) {
                onMain(() -> call("setDecoderMode", new Class<?>[] {int.class}, mode));
                awaitPlayerPrepared();
                onMain(() -> call("seekTo", new Class<?>[] {int.class}, 2000));
                awaitSeekPosition(2000);
                awaitMovingVideo();
                Thread.sleep(2500);
                checkScrubDirection(mode, 1);
                awaitMovingVideo();
                checkScrubDirection(mode, -1);
                awaitMovingVideo();
            }
            savePlayerScreenshot("continuous-seek");
        } finally {
            onMain(() -> call("setDecoderMode", new Class<?>[] {int.class}, 0));
            onMain(() -> invoke(open, originalVideo, 6000));
            awaitPlayerPrepared();
        }
    }

    private void checkScrubDirection(int mode, int direction) throws Exception {
        AtomicReference<Long> down = new AtomicReference<>();
        AtomicReference<Integer> targetPosition = new AtomicReference<>();
        AtomicReference<Integer> initialVolume = new AtomicReference<>();
        AtomicReference<Long> originalPlaybackTime = new AtomicReference<>();
        java.util.HashSet<Integer> pictures = new java.util.HashSet<>();
        int previewUpdates = 0;
        int lastPreview = -1;
        long lastPreviewAt = SystemClock.uptimeMillis();
        long longestPreviewGap = 0;
        float startX = direction > 0 ? .12f : .85f;
        onMain(() -> {
            org.videolan.libvlc.MediaPlayer player = (org.videolan.libvlc.MediaPlayer) field("mediaPlayer");
            initialVolume.set(player.getVolume());
            originalPlaybackTime.set(player.getTime());
            require(player.getAudioTrack() >= 0, "Scrub fixture has no audio track");
            require(((ScrubPreview) field("scrubPreview")).isReady(), "Persistent scrub decoder did not prepare");
            call("setControlsVisible", new Class<?>[] {boolean.class}, false);
            View root = (View) field("playerRoot");
            down.set(SystemClock.uptimeMillis());
            dispatchTouch(root, down.get(), down.get(), MotionEvent.ACTION_DOWN,
                    root.getWidth() * startX, root.getHeight() * .45f);
        });
        try {
            for (int step = 1; step <= 48; step++) {
                final float fraction = startX + direction * .38f * step / 48f;
                onMain(() -> {
                    View root = (View) field("playerRoot");
                    dispatchTouch(root, down.get(), SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                            root.getWidth() * fraction, root.getHeight() * .45f);
                });
                Thread.sleep(20);
                AtomicReference<Integer> preview = new AtomicReference<>();
                onMain(() -> preview.set((Integer) field("displayedSeekPositionMs")));
                if (preview.get() != lastPreview) {
                    long now = SystemClock.uptimeMillis();
                    if (lastPreview >= 0) longestPreviewGap = Math.max(longestPreviewGap, now - lastPreviewAt);
                    lastPreviewAt = now;
                    lastPreview = preview.get();
                    previewUpdates++;
                }
                pictures.add(videoFrameSignature());
                if (step % 12 == 0) {
                    requireSeekTimerMatchesPicture();
                    onMain(() -> {
                        org.videolan.libvlc.MediaPlayer player = (org.videolan.libvlc.MediaPlayer) field("mediaPlayer");
                        require(Boolean.TRUE.equals(field("separateScrub")), "Scrubbing fell back to decoder resets");
                        require(Boolean.TRUE.equals(field("playWhenReady")) && player.isPlaying(), "Scrubbing paused playback");
                        require(player.getVolume() == initialVolume.get(), "Scrubbing changed audio volume");
                        long elapsed = SystemClock.uptimeMillis() - down.get();
                        long playbackTime = player.getTime();
                        require(Math.abs(playbackTime - originalPlaybackTime.get() - elapsed) < 1500,
                                "Playback clock jumped during drag: start=" + originalPlaybackTime.get()
                                        + ", current=" + playbackTime + ", elapsed=" + elapsed);
                    });
                }
            }
            String label = "Mode" + mode + (direction > 0 ? "Forward" : "Backward");
            Bundle metrics = new Bundle();
            metrics.putInt("scrubUpdates" + label, previewUpdates);
            metrics.putInt("scrubDistinctPictures" + label, pictures.size());
            metrics.putLong("scrubLongestGapMs" + label, longestPreviewGap);
            sendStatus(0, metrics);
            require(previewUpdates >= 24 && pictures.size() >= 24, "Too few scrub pictures in " + label);
            require(longestPreviewGap <= 180, "Scrub picture froze for " + longestPreviewGap + " ms in " + label);
            onMain(() -> targetPosition.set((Integer) field("gestureTargetPosition")));
            requireSeekTimerMatchesPicture();
        } finally {
            onMain(() -> {
                View root = (View) field("playerRoot");
                dispatchTouch(root, down.get(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        root.getWidth() * (startX + direction * .38f), root.getHeight() * .45f);
            });
        }
        awaitSeekPosition(targetPosition.get());
        AtomicBoolean previewGone = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 2000;
        while (!previewGone.get() && SystemClock.uptimeMillis() < deadline) {
            onMain(() -> previewGone.set(((View) field("scrubView")).getAlpha() == 0f));
            Thread.sleep(20);
        }
        require(previewGone.get(), "Scrub view did not hand off to full quality playback");
    }

    private void awaitSeekPosition(int expectedMs) throws Exception {
        AtomicBoolean settled = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!settled.get() && SystemClock.uptimeMillis() < deadline) {
            onMain(() -> {
                org.videolan.libvlc.MediaPlayer player = (org.videolan.libvlc.MediaPlayer) field("mediaPlayer");
                if (player != null && Boolean.TRUE.equals(field("mediaPrepared"))) {
                    settled.set(!((SeekQueue) field("seekQueue")).hasWork() && Math.abs(player.getTime() - expectedMs) < 1800);
                }
            });
            Thread.sleep(80);
        }
        require(settled.get(), "Seek did not settle near " + expectedMs + " ms");
    }

    private void awaitMovingVideo() throws Exception {
        int first = videoFrameSignature();
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(140);
            if (videoFrameSignature() != first) return;
        }
        throw new AssertionError("Decoded video picture stopped changing after seeking");
    }

    private int videoFrameSignature() throws Exception {
        return java.util.Arrays.hashCode(videoFramePixels());
    }

    private int[] videoFramePixels() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        onMain(() -> {
            android.view.TextureView preview = (android.view.TextureView) field("scrubView");
            if (preview != null && preview.getAlpha() > .5f && preview.isAvailable()) {
                preview.getBitmap(bitmap);
                result.set(PixelCopy.SUCCESS);
                ready.countDown();
                return;
            }
            SurfaceView surface = findSurface((View) field("videoLayout"));
            require(surface != null && surface.getHolder().getSurface().isValid(), "Video surface is not ready");
            PixelCopy.request(surface, bitmap, status -> { result.set(status); ready.countDown(); },
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        });
        try {
            require(ready.await(3, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS, "Could not read decoded video frame");
            int[] pixels = new int[160 * 90];
            bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90);
            return pixels;
        } finally {
            bitmap.recycle();
        }
    }

    private int videoPictureTimeMs() throws Exception {
        int[] pixels = videoFramePixels();
        if (!savedClockPreview) {
            savedClockPreview = true;
            Bitmap snapshot = Bitmap.createBitmap(pixels, 160, 90, Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "validation-clock-first.png"))) {
                snapshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            } finally { snapshot.recycle(); }
        }
        int left = 160, right = -1, top = 90, lastColorRow = -1;
        for (int y = 0; y < 90; y++) {
            for (int x = 0; x < 160; x++) {
                int pixel = pixels[y * 160 + x];
                int maximum = Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel)));
                int minimum = Math.min(Color.red(pixel), Math.min(Color.green(pixel), Color.blue(pixel)));
                if (maximum - minimum > 64) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    lastColorRow = Math.max(lastColorRow, y);
                }
            }
        }
        require(right > left && lastColorRow > top, "Could not locate the test video inside the surface");
        int width = right - left + 1;
        int height = Math.min(90 - top, Math.round((lastColorRow - top + 1) * 720f / 656));
        int middleFrame = readPictureClock(pixels, left, top, width, height, 352f / 720);
        int bottomFrame = readPictureClock(pixels, left, top, width, height, 688f / 720);
        require(middleFrame == bottomFrame, "Picture contains different decoded frame numbers across its height");
        return Math.round(bottomFrame * 1000f / 24);
    }

    private int readPictureClock(int[] pixels, int left, int top, int width, int height, float bandCenter) {
        int centerY = Math.max(1, Math.min(88, top + Math.round(height * bandCenter)));
        int frame = 0;
        for (int bit = 0; bit < 10; bit++) {
            int centerX = Math.max(2, Math.min(157, left + Math.round((bit + .5f) * width / 10f)));
            int pixel = pixels[centerY * 160 + centerX];
            boolean white = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 384;
            if (white) frame |= 1 << bit;
            for (int y = centerY - 1; y <= centerY + 1; y++) {
                for (int x = centerX - 2; x <= centerX + 2; x += 2) {
                    int sample = pixels[y * 160 + x];
                    require((Color.red(sample) + Color.green(sample) + Color.blue(sample) > 384) == white,
                            "Decoded clock block contains inconsistent picture data");
                }
            }
        }
        return frame;
    }

    private void requireSeekTimerMatchesPicture() throws Exception {
        AtomicReference<Integer> displayed = new AtomicReference<>();
        int pictureTime = -1;
        for (int attempt = 0; attempt < 8; attempt++) {
            pictureTime = videoPictureTimeMs();
            onMain(() -> displayed.set((Integer) field("displayedSeekPositionMs")));
            if (Math.abs(pictureTime - displayed.get()) <= 300) {
                onMain(() -> {
                    String time = (String) call("formatTime", new Class<?>[] {int.class}, displayed.get());
                    require(((TextView) field("adjustmentHud")).getText().toString().startsWith(time + " / "),
                            "Seek timer does not show the rendered picture time");
                });
                return;
            }
            Thread.sleep(24);
        }
        throw new AssertionError("Seek timer " + displayed.get() + " ms differs from decoded picture " + pictureTime + " ms");
    }


    private void checkBoostedVolume() throws Exception {
        AudioManager audio = (AudioManager) target.getSystemService(Context.AUDIO_SERVICE);
        int originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            onMain(() -> call("setDecoderMode", new Class<?>[] {int.class}, 2));
            awaitPlayerPrepared();
            onMain(() -> {
                call("setControlsVisible", new Class<?>[] {boolean.class}, false);
                call("setGestureVolume", new Class<?>[] {int.class}, 100);
                View root = (View) field("playerRoot");
                touch(root, root.getWidth() * .85f, root.getHeight() * .85f,
                        root.getWidth() * .85f, root.getHeight() * .25f);
                org.videolan.libvlc.MediaPlayer player = (org.videolan.libvlc.MediaPlayer) field("mediaPlayer");
                require((Integer) field("audioBoostPercent") == 200, "Volume swipe did not unlock 200% in HW+");
                require(player.getVolume() == 200, "Native player did not accept 200% gain");
                require(((TextView) field("adjustmentHud")).getText().toString().contains("200%"), "Volume HUD did not show boost");
                touch(root, root.getWidth() * .85f, root.getHeight() * .25f,
                        root.getWidth() * .85f, root.getHeight() * .85f);
                require((Integer) field("audioBoostPercent") == 100 && player.getVolume() == 100,
                        "Lowering volume did not clear player gain below 100%");
            });
        } finally {
            onMain(() -> {
                call("setAudioBoost", new Class<?>[] {int.class}, 100);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
                call("setDecoderMode", new Class<?>[] {int.class}, 0);
            });
            awaitPlayerPrepared();
        }
    }

    private void awaitPlayerPrepared() throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            onMain(() -> ready.set(Boolean.TRUE.equals(field("mediaPrepared"))));
            Thread.sleep(100);
        }
        require(ready.get(), "Player did not prepare after decoder change");
    }

    private void checkSubtitleSelection() throws Exception {
        File subtitle = new File(target.getFilesDir(), "validation-subtitle.srt");
        try (FileOutputStream output = new FileOutputStream(subtitle)) {
            output.write("1\n00:00:00,000 --> 00:00:29,900\nSubtitle selection validation\n".getBytes("UTF-8"));
        }
        onMain(() -> call("loadExternalSubtitle", new Class<?>[] {Uri.class, field("currentVideo").getClass(), boolean.class},
                Uri.fromFile(subtitle), field("currentVideo"), true));
        AtomicBoolean selected = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (!selected.get() && SystemClock.uptimeMillis() < deadline) {
            onMain(() -> selected.set(((org.videolan.libvlc.MediaPlayer) field("mediaPlayer")).getSpuTrack() >= 0));
            Thread.sleep(100);
        }
        require(selected.get(), "External subtitle did not start");
        AtomicReference<android.app.AlertDialog> dialog = new AtomicReference<>();
        try {
            onMain(() -> dialog.set((android.app.AlertDialog) call("showTrackSelection", new Class<?>[] {boolean.class}, true)));
            waitForIdleSync();
            onMain(() -> {
                checkPlayerPanel(dialog.get());
                ListView list = dialog.get().getListView();
                require("Off".equals(list.getAdapter().getItem(0)), "Off is not the first subtitle option");
                require(list.getFirstVisiblePosition() == 0, "Subtitle list did not open at Off");
                require(list.getCheckedItemPosition() > 0, "Subtitle menu shows Off while a subtitle is running");
            });
            savePlayerScreenshot("subtitle-panel");
            onMain(() -> {
                ListView list = dialog.get().getListView();
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
            });
            waitForIdleSync();
            onMain(() -> {
                org.videolan.libvlc.MediaPlayer player = (org.videolan.libvlc.MediaPlayer) field("mediaPlayer");
                require(player.getSpuTrack() == -1, "Selecting Off did not stop subtitles");
                call("restoreTrackSelections", new Class<?>[] {org.videolan.libvlc.MediaPlayer.class}, player);
                require(player.getSpuTrack() == -1, "Saved settings re-enabled subtitles after Off");
                dialog.set((android.app.AlertDialog) call("showTrackSelection", new Class<?>[] {boolean.class}, true));
                require(dialog.get().getListView().getCheckedItemPosition() == 0, "Subtitle menu did not reflect Off");
            });
        } finally {
            onMain(() -> { if (dialog.get() != null) dialog.get().dismiss(); });
        }
        try {
            onMain(() -> dialog.set((android.app.AlertDialog) call("showTrackSelection", new Class<?>[] {boolean.class}, false)));
            waitForIdleSync();
            onMain(() -> checkPlayerPanel(dialog.get()));
            savePlayerScreenshot("audio-panel");
        } finally {
            onMain(() -> { if (dialog.get() != null) dialog.get().dismiss(); });
        }
    }

    private void checkLongSubtitleList(Object originalVideo, Constructor<?> constructor) throws Exception {
        File fixture = new File(target.getFilesDir(), "subtitle-tracks.mkv");
        try (InputStream input = getContext().getAssets().open("subtitle-tracks.mkv");
             FileOutputStream output = new FileOutputStream(fixture)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        Object video = constructor.newInstance(Uri.fromFile(fixture), "Many subtitle tracks", "Validation", 30000L, fixture.length());
        Method open = MainActivity.class.getDeclaredMethod("openVideo", video.getClass(), int.class);
        open.setAccessible(true);
        AtomicReference<android.app.AlertDialog> dialog = new AtomicReference<>();
        try {
            onMain(() -> invoke(open, video, 6000));
            awaitPlayerPrepared();
            AtomicBoolean ready = new AtomicBoolean();
            long deadline = SystemClock.uptimeMillis() + 6000;
            while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
                onMain(() -> {
                    org.videolan.libvlc.MediaPlayer player = (org.videolan.libvlc.MediaPlayer) field("mediaPlayer");
                    org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = player.getSpuTracks();
                    if (tracks != null && tracks.length >= 16) {
                        ready.set(player.setSpuTrack(tracks[tracks.length - 1].id));
                    }
                });
                Thread.sleep(100);
            }
            require(ready.get(), "Sixteen subtitle tracks were not available");
            for (int attempt = 0; attempt < 3; attempt++) {
                onMain(() -> dialog.set((android.app.AlertDialog) call("showTrackSelection", new Class<?>[] {boolean.class}, true)));
                waitForIdleSync();
                Thread.sleep(250);
                onMain(() -> {
                    ListView list = dialog.get().getListView();
                    require("Off".equals(list.getAdapter().getItem(0)), "Off is not the first subtitle option");
                    require(list.getFirstVisiblePosition() == 0, "Subtitle list scrolled to the active track instead of Off");
                    require(list.getCheckedItemPosition() == 16, "Subtitle list does not mark the active last track");
                    TextView first = findText(list.getChildAt(0), "Off");
                    require(first != null && first.isShown(), "Off is not visible when the subtitle list opens");
                    dialog.get().dismiss();
                });
            }
            onMain(() -> dialog.set((android.app.AlertDialog) call("showTrackSelection", new Class<?>[] {boolean.class}, true)));
            waitForIdleSync();
            onMain(() -> {
                ListView list = dialog.get().getListView();
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
                require(((org.videolan.libvlc.MediaPlayer) field("mediaPlayer")).getSpuTrack() == -1,
                        "Off did not disable the last subtitle track");
            });
        } finally {
            onMain(() -> {
                if (dialog.get() != null) dialog.get().dismiss();
                invoke(open, originalVideo, 6000);
            });
            awaitPlayerPrepared();
        }
    }

    private void checkPlayerPanel(android.app.AlertDialog dialog) {
        android.view.Window window = dialog.getWindow();
        require(window != null, "Player panel has no window");
        android.view.WindowManager.LayoutParams params = window.getAttributes();
        require((params.gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK) == android.view.Gravity.RIGHT,
                "Player panel is not aligned to the right");
        require((params.flags & android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0,
                "Player panel dims the video");
        android.graphics.drawable.Drawable background = window.getDecorView().getBackground();
        require(background instanceof android.graphics.drawable.ColorDrawable
                        && Color.alpha(((android.graphics.drawable.ColorDrawable) background).getColor()) == 0,
                "Player panel background is not transparent");
    }

    private void checkAudioSyncPanel() throws Exception {
        AtomicReference<android.app.AlertDialog> dialog = new AtomicReference<>();
        String key = "audioDelayMs:" + Uri.fromFile(new File(target.getFilesDir(), "preview-fixture.mp4"));
        try {
            onMain(() -> dialog.set((android.app.AlertDialog) call("showAudioDelayDialog", new Class<?>[0])));
            waitForIdleSync();
            onMain(() -> {
                checkPlayerPanel(dialog.get());
                View decor = dialog.get().getWindow().getDecorView();
                EditText input = findViewOfType(decor, EditText.class);
                require(input != null, "Audio sync has no editable number");
                require(findViewOfType(decor, SeekBar.class) == null, "Audio sync still uses a slider");
                input.setText("");
                dialog.get().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                require(dialog.get().isShowing() && input.getError() != null, "Empty audio offset was accepted");
                input.setText("-0.25");
                findByDescription(decor, "Increase audio delay by 0.1 seconds").performClick();
                require("-0.15".equals(input.getText().toString()), "Plus did not add 0.1 seconds");
                findByDescription(decor, "Decrease audio delay by 0.1 seconds").performClick();
                require("-0.25".equals(input.getText().toString()), "Minus did not subtract 0.1 seconds");
                input.setText("+0.125");
                require(((org.videolan.libvlc.MediaPlayer) field("mediaPlayer")).getAudioDelay() == 125000L,
                        "Typed offset was not applied to the player");
            });
            savePlayerScreenshot("audio-sync-panel");
            onMain(() -> {
                dialog.get().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                require(((SharedPreferences) field("preferences")).getInt(key, 0) == 125, "Typed offset was not saved");
                dialog.set((android.app.AlertDialog) call("showAudioDelayDialog", new Class<?>[0]));
                EditText input = findViewOfType(dialog.get().getWindow().getDecorView(), EditText.class);
                require(input != null && "+0.125".equals(input.getText().toString()), "Saved offset lost its precision");
            });
            waitForIdleSync();
            onMain(() -> {
                dialog.get().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
                require(((SharedPreferences) field("preferences")).getInt(key, -1) == 0, "Reset did not clear audio sync");
            });
        } finally {
            onMain(() -> { if (dialog.get() != null) dialog.get().dismiss(); });
        }
    }

    private <T extends View> T findViewOfType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findViewOfType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findByDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void savePlayerScreenshot(String name) throws Exception {
        waitForIdleSync();
        // Idle does not wait for the window compositor's dialog transition.
        Thread.sleep(350);
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        require(screenshot != null, "Could not capture player layout");
        try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "validation-" + name + ".png"))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            screenshot.recycle();
        }
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
            View lock = (View) field("lockButton");
            require(lock.isShown(), "Bottom-left lock control is unavailable");
            call("setControlsVisible", new Class<?>[] {boolean.class}, false);
            require(!lock.isShown(), "Bottom-left lock remained visible without the playback overlay");
            call("setControlsVisible", new Class<?>[] {boolean.class}, true);
            require(lock.isShown(), "Bottom-left lock did not return with the playback overlay");
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
            tapCenter((View) field("lockButton"));
        });
        waitForIdleSync();
        onMain(this::checkLocked);
    }

    private void checkLocked() {
        require(Boolean.TRUE.equals(field("touchLocked")), "Playback touch lock was lost");
        require(((View) field("touchLockOverlay")).isShown(), "Touch guard is hidden");
        require(((View) field("unlockButton")).isShown(), "Unlock button is hidden");
        require(!((View) field("lockButton")).isShown(), "Locked player still shows the lock button");
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

    private Object fieldOnMain(String name) {
        AtomicReference<Object> value = new AtomicReference<>();
        onMain(() -> value.set(field(name)));
        return value.get();
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
