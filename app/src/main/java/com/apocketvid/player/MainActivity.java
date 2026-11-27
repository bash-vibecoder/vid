package com.apocketvid.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 10, 16);
    private static final int PANEL = Color.rgb(15, 22, 32);
    private static final int SURFACE = Color.rgb(21, 31, 45);
    private static final int INK = Color.rgb(242, 246, 252);
    private static final int MUTED = Color.rgb(139, 154, 174);
    private static final int ACCENT = Color.rgb(38, 139, 255);
    private static final int ORANGE = Color.rgb(255, 143, 0);
    private static final int LINE = Color.rgb(33, 46, 63);
    private static final int PERMISSION_REQUEST = 52;
    private static final int SUBTITLE_FILE_REQUEST = 53;
    private static final int AUDIO_FILE_REQUEST = 54;
    private static final int DECODER_SOFTWARE = 0;
    private static final int DECODER_HARDWARE = 1;
    private static final int DECODER_HARDWARE_PLUS = 2;
    private static final int SORT_NAME = 0;
    private static final int SORT_NEWEST = 1;
    private static final int SORT_OLDEST = 2;
    private static final int SORT_LONGEST = 3;
    private static final int SORT_LARGEST = 4;
    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_BRIGHTNESS = 1;
    private static final int GESTURE_VOLUME = 2;
    private static final int GESTURE_SEEK = 3;

    private final ArrayList<VideoItem> videos = new ArrayList<>();
    private final ArrayList<VideoItem> visibleVideos = new ArrayList<>();
    private final ArrayList<FolderItem> visibleFolders = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService playerIo = Executors.newSingleThreadExecutor();
    private Future<LibVLC> engineFuture;
    private VideoPreviewLoader previewLoader;
    private volatile int playerLoadGeneration;
    private boolean activityVisible;

    private LinearLayout libraryRoot;
    private FrameLayout libraryContent;
    private ListView videoList;
    private LinearLayout emptyPanel;
    private TextView countText;
    private TextView emptyTitle;
    private TextView emptyBody;
    private TextView emptyAction;
    private View resumeLastButton;
    private EditText searchField;
    private VideoAdapter adapter;
    private FolderAdapter folderAdapter;
    private TextView screenTitle;
    private View folderBackButton;
    private SharedPreferences preferences;
    private String searchQuery = "";
    private String selectedFolder = "";
    private boolean folderOpen;
    private int sortMode = SORT_NAME;
    private int loadGeneration;

    private FrameLayout playerRoot;
    private VLCVideoLayout videoLayout;
    private MediaPlayer mediaPlayer;
    private ImageButton playPauseButton;
    private ImageButton previousVideoButton;
    private ImageButton nextVideoButton;
    private SeekBar seekBar;
    private TextView currentTimeText;
    private TextView durationText;
    private TextView playerTitle;
    private TextView speedButton;
    private TextView decoderButton;
    private TextView audioButton;
    private TextView subtitleButton;
    private TextView adjustmentHud;
    private ImageButton lockButton;
    private View loadingPanel;
    private TextView loadingMessage;
    private View controlsTop;
    private View controlsBottom;
    private FrameLayout touchLockOverlay;
    private ImageButton unlockButton;
    private boolean touchLocked;
    private int decoderMode;
    private int audioBoostPercent = 100;
    private VideoItem currentVideo;
    private int currentIndex = -1;
    private int durationMs;
    private int pendingSeekMs;
    private int savedPositionMs;
    private int lastPersistedPositionMs = -1;
    private int videoWidth;
    private int videoHeight;
    private int videoRotation;
    private int activeGesture;
    private int gestureStartVolume;
    private int gestureTargetPosition;
    private int gestureStartPosition;
    private final SeekQueue seekQueue = new SeekQueue();
    private SeekFrameMonitor seekFrameMonitor;
    private boolean seekQueueScheduled;
    private boolean scrubPreviewActive;
    private int displayedSeekPositionMs;
    private int lastSeekHudFrame = -1;
    private float gestureStartBrightness;
    private float originalScreenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private float speed = 1.0f;
    private int speedIndex;
    private boolean seeking;
    private boolean controlsVisible = true;
    private boolean orientationFlipped;
    private boolean pausedForBackground;
    private boolean restartPlaybackOnStart;
    private boolean mediaPrepared;
    private boolean playWhenReady = true;
    private boolean searchVisible;
    private boolean brightnessChanged;
    private AudioManager audioManager;
    private boolean mediaPickerActive;
    private String pickedTrackVideoUri;
    private AssetFileDescriptor currentVideoFd;
    private final ArrayList<File> importedTrackFiles = new ArrayList<>();
    private boolean externalSubtitleAttached;
    private int subtitleLoadGeneration;
    private int audioLoadGeneration;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (mediaPlayer == null) return;
            int position = safePosition();
            savedPositionMs = position;
            if (Math.abs(position - lastPersistedPositionMs) >= 1000) persistResumePosition();
            if (seekBar != null && durationMs > 0 && !seeking) {
                seekBar.setProgress((int) ((position * 1000L) / durationMs));
            }
            if (currentTimeText != null) currentTimeText.setText(formatTime(position));
            handler.postDelayed(this, 250);
        }
    };

    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            if (playerRoot != null) setControlsVisible(false);
        }
    };

    private final Runnable processSeekRequests = new Runnable() {
        @Override public void run() {
            seekQueueScheduled = false;
            MediaPlayer player = mediaPlayer;
            if (player == null || !mediaPrepared || playerRoot == null) {
                seekQueue.clear();
                return;
            }
            long now = SystemClock.uptimeMillis();
            int frames = displayedVideoFrames(player);
            long frameTime = player.getTime();
            if (seekFrameMonitor != null) {
                frames = seekFrameMonitor.sample(findVideoSurface(videoLayout), frames, frameTime);
                if (seekFrameMonitor.pictureTimeMs() >= 0) frameTime = seekFrameMonitor.pictureTimeMs();
            }
            boolean waitingForFrame = seekQueue.hasWork();
            SeekQueue.Request request = seekQueue.poll(now, frames, playWhenReady, frameTime);
            int renderedTarget = seekQueue.takeRenderedTarget();
            if (scrubPreviewActive && (renderedTarget >= 0
                    || (!waitingForFrame && frames > 0 && frames != lastSeekHudFrame))) {
                showSeekHud((int) Math.max(0, frameTime));
                lastSeekHudFrame = frames;
            }
            if (request != null) {
                savedPositionMs = request.positionMs;
                // Decode the requested frame accurately, including its reference frames.
                player.setTime(request.positionMs, false);
            }
            int recoveryTarget = seekQueue.takeRecoveryTarget();
            if (recoveryTarget >= 0) {
                Log.w("APocketVid", "Decoder stopped rendering after seek; reopening at " + recoveryTarget);
                reopenAfterStalledSeek(recoveryTarget);
                return;
            }
            if (seekQueue.hasWork() || (scrubPreviewActive && activeGesture == GESTURE_SEEK)) {
                seekQueueScheduled = true;
                handler.postDelayed(this, 16);
            } else if (scrubPreviewActive) finishScrubPreview();
        }
    };

    private final Runnable hideAdjustmentHud = new Runnable() {
        @Override public void run() {
            if (adjustmentHud != null) adjustmentHud.setVisibility(View.GONE);
        }
    };

    private final Runnable prepareTimeout = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && !mediaPrepared && loadingMessage != null) {
                loadingMessage.setText("Still opening video. Check the file format or try again.");
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("player", MODE_PRIVATE);
        decoderMode = clamp(preferences.getInt("decoderMode", preferences.getBoolean("hardwareDecoding", false)
                ? DECODER_HARDWARE : DECODER_SOFTWARE), DECODER_SOFTWARE, DECODER_HARDWARE_PLUS);
        audioBoostPercent = clamp(preferences.getInt("audioBoostPercent", 100), 100, 200);
        previewLoader = new VideoPreviewLoader(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        sortMode = preferences.getInt("sortMode", SORT_NAME);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildLibrary();
        if (state != null) {
            folderOpen = state.getBoolean("folderOpen", false);
            selectedFolder = state.getString("selectedFolder", "");
            searchQuery = state.getString("searchQuery", "");
            if (!searchQuery.isEmpty()) {
                searchVisible = true;
                searchField.setVisibility(View.VISIBLE);
                searchField.setText(searchQuery);
            }
        }
        showLibrary();

        if (state != null && state.getString("playerUri") != null) {
            VideoItem restored = new VideoItem(
                    Uri.parse(state.getString("playerUri")),
                    state.getString("playerTitle", "Video"),
                    state.getString("playerFolder", ""),
                    state.getLong("playerDuration", 0L),
                    state.getLong("playerSize", 0L));
            restored.dateModified = state.getLong("playerModified", 0L);
            openVideo(restored, state.getInt("playerPosition", 0));
            setTouchLocked(state.getBoolean("touchLocked", false));
        }

        prepareEngine();
        if (hasVideoPermission()) {
            loadVideos();
            showBatteryOptimizationPromptOnce();
        } else if (!preferences.getBoolean("mediaPermissionRequested", false)) {
            preferences.edit().putBoolean("mediaPermissionRequested", true).apply();
            requestVideoPermission();
        } else {
            updateLibrary();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        activityVisible = true;
        previewLoader.setPlaybackActive(playerRoot != null);
        boolean resume = pausedForBackground;
        if (resume) {
            pausedForBackground = false;
            playWhenReady = true;
        }
        if (restartPlaybackOnStart && currentVideo != null && videoLayout != null && playerRoot != null) {
            restartPlaybackOnStart = false;
            pendingSeekMs = savedPositionMs;
            if (loadingPanel != null) loadingPanel.setVisibility(View.VISIBLE);
            VideoItem item = currentVideo;
            VLCVideoLayout owner = videoLayout;
            owner.post(() -> startMediaPlayer(item, owner));
        } else {
            restoreVideoOutput();
            if (resume && mediaPlayer != null && mediaPrepared) mediaPlayer.play();
        }
        if (resume) scheduleHideControls();
    }

    @Override protected void onResume() {
        super.onResume();
        restoreVideoOutput();
        if (hasVideoPermission() && videos.isEmpty()) loadVideos();
    }

    @Override protected void onStop() {
        activityVisible = false;
        previewLoader.setPlaybackActive(true);
        if (currentVideo != null && mediaPrepared) persistResumePosition();
        if (!mediaPickerActive && playerRoot != null && playWhenReady) {
            savedPositionMs = safePosition();
            playWhenReady = false;
            if (mediaPlayer != null && mediaPrepared && mediaPlayer.isPlaying()) mediaPlayer.pause();
            pausedForBackground = true;
        }
        if (!mediaPickerActive && playerRoot != null && currentVideo != null) {
            // A destroyed SurfaceView invalidates VLC's decoder output. Reopen the input on
            // return, using the existing engine and saved state, instead of reviving that output.
            if (mediaPrepared) savedPositionMs = safePosition();
            restartPlaybackOnStart = true;
            releaseMediaPlayer();
        }
        super.onStop();
    }

    private void restoreVideoOutput() {
        MediaPlayer player = mediaPlayer;
        VLCVideoLayout owner = videoLayout;
        if (player == null || owner == null || playerRoot == null) return;
        // VLC detaches its native window when Android destroys a locked/background surface.
        if (!player.getVLCVout().areViewsAttached()) {
            player.detachViews();
            player.attachViews(owner, null, true, false);
            player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        }
        owner.post(() -> {
            if (activityVisible && mediaPlayer == player && videoLayout == owner) player.updateVideoSurfaces();
        });
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(progressTick);
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(hideAdjustmentHud);
        handler.removeCallbacks(prepareTimeout);
        restoreScreenBrightness();
        releaseMediaPlayer();
        releaseLibVlc();
        playerIo.shutdown();
        io.shutdownNow();
        previewLoader.close();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (videoLayout != null) {
            videoLayout.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            if (videoWidth > 0 && videoHeight > 0) adjustVideoSurface(videoWidth, videoHeight);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("folderOpen", folderOpen);
        out.putString("selectedFolder", selectedFolder);
        out.putString("searchQuery", searchQuery);
        if (currentVideo != null && playerRoot != null) {
            persistResumePosition();
            out.putString("playerUri", currentVideo.uri.toString());
            out.putString("playerTitle", currentVideo.title);
            out.putString("playerFolder", currentVideo.folder);
            out.putLong("playerDuration", currentVideo.duration);
            out.putLong("playerSize", currentVideo.size);
            out.putLong("playerModified", currentVideo.dateModified);
            out.putInt("playerPosition", safePosition());
            out.putBoolean("touchLocked", touchLocked);
        }
        super.onSaveInstanceState(out);
    }

    @Override public void onBackPressed() {
        if (playerRoot != null && touchLocked) return;
        if (playerRoot != null) {
            showLibrary();
        } else if (folderOpen) {
            folderOpen = false;
            selectedFolder = "";
            applyFilters();
        } else {
            super.onBackPressed();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playerRoot != null) applyImmersiveMode();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable glassBackground(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0x52607591, 0x26203045});
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), 0x50ffffff);
        return drawable;
    }

    private ImageButton iconButton(int icon, String description, boolean dark, View.OnClickListener action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33ffffff), rounded(Color.TRANSPARENT, 24), null));
        button.setImageTintList(ColorStateList.valueOf(dark ? Color.WHITE : INK));
        button.setOnClickListener(action);
        button.setFocusable(true);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return button;
    }

    private void buildLibrary() {
        libraryRoot = vertical();
        libraryRoot.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 35) {
            libraryRoot.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }

        LinearLayout header = vertical();
        header.setPadding(dp(20), dp(14), dp(16), dp(10));
        libraryRoot.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout top = horizontal();
        header.addView(top, new LinearLayout.LayoutParams(-1, -2));
        folderBackButton = iconButton(android.R.drawable.ic_media_previous, "Back to folders", false, v -> {
            folderOpen = false;
            selectedFolder = "";
            applyFilters();
        });
        folderBackButton.setVisibility(View.GONE);
        top.addView(folderBackButton);
        LinearLayout titleBlock = vertical();
        top.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        screenTitle = text("Folders", 25, INK, true);
        screenTitle.setSingleLine(true);
        screenTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(screenTitle);
        countText = text("", 13, MUTED, false);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-2, -2);
        countParams.topMargin = dp(2);
        titleBlock.addView(countText, countParams);

        ImageButton searchButton = iconButton(android.R.drawable.ic_menu_search, "Search videos", false, v -> {
            searchVisible = !searchVisible;
            searchField.setVisibility(searchVisible ? View.VISIBLE : View.GONE);
            if (searchVisible) {
                searchField.requestFocus();
                searchField.post(() -> {
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (keyboard != null) keyboard.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
                });
            }
            else {
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
                searchField.setText("");
                searchQuery = "";
                applyFilters();
            }
        });
        top.addView(searchButton);
        ImageButton sortButton = iconButton(android.R.drawable.ic_menu_sort_by_size, "Sort folders and videos", false,
                v -> showSortMenu(v));
        top.addView(sortButton);
        ImageButton refreshButton = iconButton(android.R.drawable.ic_popup_sync, "Refresh library", false, v -> loadVideos());
        top.addView(refreshButton);

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(15);
        searchField.setTextColor(INK);
        searchField.setHintTextColor(MUTED);
        searchField.setHint("Search videos");
        searchField.setPadding(dp(14), 0, dp(14), 0);
        searchField.setBackground(rounded(PANEL, 6));
        searchField.setVisibility(View.GONE);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(46));
        searchParams.topMargin = dp(10);
        header.addView(searchField, searchParams);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilters();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        libraryContent = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1);
        libraryRoot.addView(libraryContent, contentParams);

        videoList = new ListView(this);
        videoList.setDivider(null);
        videoList.setCacheColorHint(Color.TRANSPARENT);
        videoList.setSelector(android.R.color.transparent);
        videoList.setVerticalScrollBarEnabled(false);
        videoList.setPadding(0, dp(4), 0, dp(76));
        videoList.setClipToPadding(false);
        adapter = new VideoAdapter();
        folderAdapter = new FolderAdapter();
        videoList.setAdapter(folderAdapter);
        videoList.setOnItemClickListener((parent, row, position, id) -> {
            if (folderOpen) {
                if (position >= 0 && position < visibleVideos.size()) openVideo(visibleVideos.get(position), 0);
            } else if (position >= 0 && position < visibleFolders.size()) {
                enterFolder(visibleFolders.get(position).path);
            }
        });
        libraryContent.addView(videoList, new FrameLayout.LayoutParams(-1, -1));

        emptyPanel = vertical();
        emptyPanel.setGravity(Gravity.CENTER);
        emptyPanel.setPadding(dp(32), dp(24), dp(32), dp(24));
        emptyPanel.setVisibility(View.GONE);
        emptyTitle = text("No videos found", 19, INK, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyPanel.addView(emptyTitle);
        emptyBody = text("", 14, MUTED, false);
        emptyBody.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(8);
        emptyPanel.addView(emptyBody, bodyParams);
        emptyAction = text("", 14, ACCENT, true);
        emptyAction.setGravity(Gravity.CENTER);
        emptyAction.setPadding(dp(14), dp(12), dp(14), dp(12));
        emptyAction.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33ffffff), rounded(SURFACE, 6), null));
        emptyAction.setOnClickListener(v -> {
            if (!hasVideoPermission()) requestVideoPermission();
            else if (!searchQuery.isEmpty()) {
                searchField.setText("");
                searchQuery = "";
                applyFilters();
            } else if (folderOpen) {
                folderOpen = false;
                selectedFolder = "";
                applyFilters();
            } else loadVideos();
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.topMargin = dp(12);
        emptyPanel.addView(emptyAction, actionParams);
        libraryContent.addView(emptyPanel, new FrameLayout.LayoutParams(-1, -1));

        ImageButton resume = new ImageButton(this);
        resume.setContentDescription("Resume last video");
        resume.setImageResource(R.drawable.ic_play);
        resume.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        resume.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        resume.setPadding(dp(16), dp(16), dp(16), dp(16));
        resume.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff), rounded(ORANGE, 28), null));
        resume.setOnClickListener(v -> resumeLastVideo());
        FrameLayout.LayoutParams resumeParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.RIGHT | Gravity.BOTTOM);
        resumeParams.setMargins(0, 0, dp(18), dp(18));
        libraryContent.addView(resume, resumeParams);
        resumeLastButton = resume;
    }

    private void showLibrary() {
        if (currentVideo != null && mediaPrepared) persistResumePosition();
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(progressTick);
        handler.removeCallbacks(hideAdjustmentHud);
        handler.removeCallbacks(prepareTimeout);
        restoreScreenBrightness();
        touchLocked = false;
        touchLockOverlay = null;
        unlockButton = null;
        lockButton = null;
        playerRoot = null;
        releaseMediaPlayer();
        previewLoader.setPlaybackActive(!activityVisible);
        videoLayout = null;
        currentVideo = null;
        currentIndex = -1;
        pausedForBackground = false;
        orientationFlipped = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(libraryRoot);
        applyFilters();
    }

    private void requestVideoPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }, PERMISSION_REQUEST);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] { Manifest.permission.READ_MEDIA_VIDEO }, PERMISSION_REQUEST);
        } else {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, PERMISSION_REQUEST);
        }
    }

    private boolean hasVideoPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void showBatteryOptimizationPromptOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || preferences.getBoolean("batteryPromptShown", false)) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null || power.isIgnoringBatteryOptimizations(getPackageName())) {
            preferences.edit().putBoolean("batteryPromptShown", true).apply();
            return;
        }
        preferences.edit().putBoolean("batteryPromptShown", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Battery optimization")
                .setMessage("Allow APocketVid to run without battery restrictions so playback can resume reliably after the app is backgrounded.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings", (dialog, which) -> {
                    Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    request.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(request);
                    } catch (RuntimeException ignored) {
                        try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                        catch (RuntimeException unavailable) { Toast.makeText(this, "Battery settings are unavailable", Toast.LENGTH_SHORT).show(); }
                    }
                })
                .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (hasVideoPermission()) loadVideos();
            else updateLibrary();
            showBatteryOptimizationPromptOnce();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SUBTITLE_FILE_REQUEST && requestCode != AUDIO_FILE_REQUEST) return;
        mediaPickerActive = false;
        String pickedForVideo = pickedTrackVideoUri;
        pickedTrackVideoUri = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {}
        }
        if (currentVideo != null && currentVideo.uri.toString().equals(pickedForVideo)) {
            if (requestCode == SUBTITLE_FILE_REQUEST) loadExternalSubtitle(uri, currentVideo, true);
            else loadExternalAudio(uri, currentVideo, true);
        }
    }

    private void loadVideos() {
        if (!hasVideoPermission()) {
            updateLibrary();
            return;
        }
        final int generation = ++loadGeneration;
        countText.setText("Scanning device");
        io.execute(() -> {
            ArrayList<VideoItem> found = queryVideos();
            runOnUiThread(() -> {
                if (isFinishing() || generation != loadGeneration) return;
                videos.clear();
                if (found != null) videos.addAll(found);
                if (folderOpen && !containsFolder(selectedFolder)) {
                    folderOpen = false;
                    selectedFolder = "";
                }
                applyFilters();
                if (currentVideo != null) {
                    currentIndex = findVideoIndex(currentVideo.uri);
                    updateTransportButtons();
                }
                ArrayList<VideoPreviewLoader.Source> previews = new ArrayList<>();
                for (VideoItem video : videos) previews.add(previewSource(video));
                previewLoader.prefetch(previews);
            });
        });
    }

    private ArrayList<VideoItem> queryVideos() {
        ArrayList<String> columns = new ArrayList<>();
        columns.add(MediaStore.Video.Media._ID);
        columns.add(MediaStore.Video.Media.DISPLAY_NAME);
        columns.add(MediaStore.Video.Media.TITLE);
        columns.add(MediaStore.Video.Media.DURATION);
        columns.add(MediaStore.Video.Media.SIZE);
        columns.add(MediaStore.Video.Media.DATE_ADDED);
        columns.add(MediaStore.Video.Media.DATE_MODIFIED);
        columns.add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
        columns.add(MediaStore.MediaColumns.WIDTH);
        columns.add(MediaStore.MediaColumns.HEIGHT);
        columns.add(MediaStore.MediaColumns.ORIENTATION);
        columns.add(Build.VERSION.SDK_INT >= 29
                ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.Video.Media.DATA);

        ArrayList<VideoItem> result = new ArrayList<>();
        String[] projection = columns.toArray(new String[0]);
        String sort = MediaStore.Video.Media.DATE_ADDED + " DESC, "
                + MediaStore.Video.Media.DISPLAY_NAME + " COLLATE NOCASE ASC";
        try (Cursor cursor = getContentResolver().query(
                videoCollectionUri(),
                projection,
                MediaStore.Video.Media.DURATION + " > 0",
                null,
                sort)) {
            if (cursor == null) return result;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
            int widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int orientationColumn = cursor.getColumnIndex(MediaStore.MediaColumns.ORIENTATION);
            int bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            int pathColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT >= 29
                    ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.Video.Media.DATA);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                if (name == null || name.trim().isEmpty()) name = cursor.getString(titleColumn);
                if (name == null || name.trim().isEmpty()) name = "Untitled video";
                String folder = pathColumn >= 0 ? cursor.getString(pathColumn) : null;
                if (Build.VERSION.SDK_INT < 29 && folder != null) {
                    int slash = folder.lastIndexOf('/');
                    folder = slash > 0 ? folder.substring(0, slash) : null;
                    String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
                    if (folder != null && folder.startsWith(storageRoot)) folder = folder.substring(storageRoot.length());
                }
                if (folder == null || folder.trim().isEmpty()) folder = cursor.getString(bucketColumn);
                if (folder == null || folder.trim().isEmpty()) folder = "Videos";
                Uri uri = ContentUris.withAppendedId(videoCollectionUri(), id);
                VideoItem video = new VideoItem(uri, name, folder,
                        cursor.getLong(durationColumn), cursor.getLong(sizeColumn), cursor.getLong(dateColumn),
                        widthColumn < 0 ? 0 : cursor.getInt(widthColumn),
                        heightColumn < 0 ? 0 : cursor.getInt(heightColumn),
                        orientationColumn < 0 ? 0 : cursor.getInt(orientationColumn));
                video.dateModified = cursor.getLong(modifiedColumn);
                result.add(video);
            }
        } catch (SecurityException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
        return result;
    }

    private Uri videoCollectionUri() {
        if (Build.VERSION.SDK_INT >= 29) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    private boolean containsFolder(String folder) {
        for (VideoItem item : videos) if (item.folder.equals(folder)) return true;
        return false;
    }

    private String folderLabel(String folder) {
        String value = folder == null ? "Videos" : folder.replaceAll("/+$", "");
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private void applyFilters() {
        Map<String, FolderItem> grouped = new HashMap<>();
        for (VideoItem item : videos) {
            FolderItem folder = grouped.get(item.folder);
            if (folder == null) {
                folder = new FolderItem(item.folder);
                grouped.put(item.folder, folder);
            }
            folder.add(item);
        }
        visibleFolders.clear();
        for (FolderItem folder : grouped.values()) {
            boolean matches = searchQuery.isEmpty()
                    || folder.path.toLowerCase(Locale.ROOT).contains(searchQuery);
            if (!matches && !searchQuery.isEmpty()) {
                for (VideoItem item : folder.videos) {
                    if (item.title.toLowerCase(Locale.ROOT).contains(searchQuery)) {
                        matches = true;
                        break;
                    }
                }
            }
            if (matches) {
                folder.loadResume(preferences);
                visibleFolders.add(folder);
            }
        }
        Collections.sort(visibleFolders, folderComparator());

        visibleVideos.clear();
        if (folderOpen) for (VideoItem item : videos) {
            if (!selectedFolder.equals(item.folder)) continue;
            if (!searchQuery.isEmpty()) {
                if (!item.title.toLowerCase(Locale.ROOT).contains(searchQuery)) continue;
            }
            visibleVideos.add(item);
        }
        Collections.sort(visibleVideos, videoComparator());
        screenTitle.setText(folderOpen ? folderLabel(selectedFolder) : "Folders");
        folderBackButton.setVisibility(folderOpen ? View.VISIBLE : View.GONE);
        countText.setText(folderOpen
                ? visibleVideos.size() + (visibleVideos.size() == 1 ? " video" : " videos")
                : visibleFolders.size() + (visibleFolders.size() == 1 ? " folder" : " folders"));
        if (videoList.getAdapter() != (folderOpen ? adapter : folderAdapter)) {
            videoList.setAdapter(folderOpen ? adapter : folderAdapter);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (folderAdapter != null) folderAdapter.notifyDataSetChanged();
        updateLibrary();
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().setGroupCheckable(0, true, true);
        String[] labels = { "Name A-Z", "Newest", "Oldest", "Longest", "Largest" };
        for (int i = 0; i < labels.length; i++) {
            MenuItem item = menu.getMenu().add(0, i, i, labels[i]);
            item.setCheckable(true);
            item.setChecked(sortMode == i);
        }
        menu.setOnMenuItemClickListener(item -> {
            sortMode = item.getItemId();
            preferences.edit().putInt("sortMode", sortMode).apply();
            applyFilters();
            return true;
        });
        menu.show();
    }

    private Comparator<VideoItem> videoComparator() {
        switch (sortMode) {
            case SORT_NEWEST: return (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
            case SORT_OLDEST: return (a, b) -> Long.compare(a.dateAdded, b.dateAdded);
            case SORT_LONGEST: return (a, b) -> Long.compare(b.duration, a.duration);
            case SORT_LARGEST: return (a, b) -> Long.compare(b.size, a.size);
            default: return (a, b) -> a.title.compareToIgnoreCase(b.title);
        }
    }

    private Comparator<FolderItem> folderComparator() {
        Comparator<FolderItem> comparator;
        switch (sortMode) {
            case SORT_NEWEST: comparator = (a, b) -> Long.compare(b.latestDate, a.latestDate); break;
            case SORT_OLDEST: comparator = (a, b) -> Long.compare(a.latestDate, b.latestDate); break;
            case SORT_LONGEST: comparator = (a, b) -> Long.compare(b.totalDuration, a.totalDuration); break;
            case SORT_LARGEST: comparator = (a, b) -> Long.compare(b.totalSize, a.totalSize); break;
            default: comparator = (a, b) -> folderLabel(a.path).compareToIgnoreCase(folderLabel(b.path)); break;
        }
        return comparator.thenComparing(a -> a.path, String.CASE_INSENSITIVE_ORDER);
    }

    private void enterFolder(String path) {
        folderOpen = true;
        selectedFolder = path;
        applyFilters();
    }

    private void updateLibrary() {
        if (libraryRoot == null || playerRoot != null) return;
        boolean allowed = hasVideoPermission();
        String lastUri = preferences.getString("lastUri", null);
        int lastPosition = preferences.getInt("lastPosition", 0);
        boolean hasResume = allowed && lastUri != null && lastPosition >= 0 && findVideo(Uri.parse(lastUri)) != null;
        if (resumeLastButton != null) {
            resumeLastButton.setVisibility(hasResume ? View.VISIBLE : View.GONE);
            resumeLastButton.setContentDescription(hasResume ? "Resume " + preferences.getString("lastTitle", "last video") : "Resume last video");
        }
        boolean filtered = !searchQuery.isEmpty();
        int itemCount = folderOpen ? visibleVideos.size() : visibleFolders.size();
        boolean showEmpty = !allowed || itemCount == 0;
        videoList.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        emptyPanel.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (!allowed) {
            emptyTitle.setText("Video access needed");
            emptyBody.setText("Allow access to find videos stored on this device.");
            emptyAction.setText("Allow video access");
        } else if (videos.isEmpty()) {
            emptyTitle.setText("No videos found");
            emptyBody.setText("Your device library is empty.");
            emptyAction.setText("Refresh library");
        } else if (filtered) {
            emptyTitle.setText("No matching videos");
            emptyBody.setText(folderOpen ? "Try another search." : "No folder contains a matching video.");
            emptyAction.setText("Clear filters");
        } else if (folderOpen) {
            emptyTitle.setText("Folder is empty");
            emptyBody.setText("No videos are available in this folder.");
            emptyAction.setText("Back to folders");
        } else {
            emptyTitle.setText("No folders found");
            emptyBody.setText("Your device library is empty.");
            emptyAction.setText("Refresh library");
        }
    }

    private VideoItem findVideo(Uri uri) {
        if (uri == null) return null;
        for (VideoItem item : videos) if (item.uri.equals(uri)) return item;
        return null;
    }

    private void resumeLastVideo() {
        String lastUri = preferences.getString("lastUri", null);
        if (lastUri == null) return;
        VideoItem item = findVideo(Uri.parse(lastUri));
        if (item == null) {
            loadVideos();
            Toast.makeText(this, "Last video is unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        openVideo(item, preferences.getInt("lastPosition", 0));
    }

    private void requestThumbnail(VideoItem item, ImageView target) {
        requestThumbnail(item, target, android.R.drawable.ic_media_play);
    }

    private void requestThumbnail(VideoItem item, ImageView target, int fallbackIcon) {
        previewLoader.load(previewSource(item), target, fallbackIcon);
    }

    private VideoPreviewLoader.Source previewSource(VideoItem item) {
        return new VideoPreviewLoader.Source(item.uri, item.size, item.dateModified, item.duration);
    }

    private final class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleFolders.size(); }
        @Override public FolderItem getItem(int position) { return visibleFolders.get(position); }
        @Override public long getItemId(int position) { return getItem(position).path.hashCode(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            FolderRowHolder holder;
            if (convertView == null) {
                LinearLayout rowRoot = vertical();
                LinearLayout row = horizontal();
                row.setPadding(dp(20), dp(8), dp(14), dp(8));
                rowRoot.addView(row, new LinearLayout.LayoutParams(-1, dp(86)));
                ImageView thumbnail = new ImageView(MainActivity.this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackground(rounded(SURFACE, 6));
                thumbnail.setClipToOutline(true);
                row.addView(thumbnail, new LinearLayout.LayoutParams(dp(76), dp(54)));

                LinearLayout details = vertical();
                details.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, -1, 1);
                detailParams.leftMargin = dp(14);
                row.addView(details, detailParams);
                TextView title = text("", 15, INK, true);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                details.addView(title, new LinearLayout.LayoutParams(-1, -2));
                TextView meta = text("", 12, MUTED, false);
                meta.setSingleLine(true);
                meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
                metaParams.topMargin = dp(4);
                details.addView(meta, metaParams);
                TextView resume = text("Resume", 12, ACCENT, true);
                resume.setGravity(Gravity.CENTER);
                resume.setPadding(dp(9), dp(8), dp(9), dp(8));
                resume.setBackground(new RippleDrawable(
                        ColorStateList.valueOf(0x33ffffff), rounded(SURFACE, 5), null));
                resume.setOnClickListener(v -> {
                    Object tag = v.getTag();
                    if (tag instanceof FolderItem) resumeFolder((FolderItem) tag);
                });
                row.addView(resume, new LinearLayout.LayoutParams(dp(64), dp(40)));
                View divider = new View(MainActivity.this);
                divider.setBackgroundColor(LINE);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
                dividerParams.leftMargin = dp(82);
                rowRoot.addView(divider, dividerParams);
                holder = new FolderRowHolder(thumbnail, title, meta, resume);
                rowRoot.setTag(holder);
                convertView = rowRoot;
            } else {
                holder = (FolderRowHolder) convertView.getTag();
            }

            FolderItem folder = getItem(position);
            holder.title.setText(folderLabel(folder.path));
            String lastPlayedFolder = preferences.getString("lastPlayedFolder", preferences.getString("lastFolder", null));
            holder.title.setTextColor(folder.lastPlayedVideo != null && folder.path.equals(lastPlayedFolder) ? ORANGE : INK);
            String detail = folder.videos.size() + (folder.videos.size() == 1 ? " video" : " videos")
                    + "  /  " + formatSize(folder.totalSize);
            String parentPath = parentFolderLabel(folder.path);
            if (!parentPath.isEmpty()) detail = parentPath + "  /  " + detail;
            holder.meta.setText(detail);
            requestThumbnail(folder.previewVideo, holder.thumbnail, com.apocketvid.player.R.drawable.ic_folder);
            holder.resume.setTag(folder);
            holder.resume.setVisibility(folder.resumeVideo == null ? View.INVISIBLE : View.VISIBLE);
            holder.resume.setContentDescription("Resume " + folderLabel(folder.path));
            return convertView;
        }
    }

    private String parentFolderLabel(String folder) {
        String value = folder == null ? "" : folder.replaceAll("/+$", "");
        int slash = value.lastIndexOf('/');
        return slash > 0 ? value.substring(0, slash) : "";
    }

    private void resumeFolder(FolderItem folder) {
        if (folder.resumeVideo == null) return;
        openVideo(folder.resumeVideo, folder.resumePosition);
    }

    private String resumeUriKey(String folder) { return "resumeUri:" + folder; }
    private String resumePositionKey(String folder) { return "resumePosition:" + folder; }

    private void persistResumePosition() {
        if (currentVideo == null || preferences == null) return;
        int position = safePosition();
        savedPositionMs = position;
        preferences.edit().putString("lastPlayedUri:" + currentVideo.folder, currentVideo.uri.toString())
                .putString("lastPlayedFolder", currentVideo.folder).apply();
        if (durationMs > 0 && position >= durationMs - 1500) {
            clearResumePosition(currentVideo);
            return;
        }
        SharedPreferences.Editor edit = preferences.edit();
        edit.putString(resumeUriKey(currentVideo.folder), currentVideo.uri.toString());
        edit.putInt(resumePositionKey(currentVideo.folder), position);
        edit.putString("lastUri", currentVideo.uri.toString());
        edit.putString("lastFolder", currentVideo.folder);
        edit.putString("lastTitle", currentVideo.title);
        edit.putInt("lastPosition", position);
        edit.apply();
        lastPersistedPositionMs = position;
    }

    private void clearResumePosition(VideoItem item) {
        if (item == null || preferences == null) return;
        SharedPreferences.Editor edit = preferences.edit()
                .remove(resumeUriKey(item.folder))
                .remove(resumePositionKey(item.folder));
        if (item.uri.toString().equals(preferences.getString("lastUri", null))) {
            edit.remove("lastUri").remove("lastFolder").remove("lastTitle").remove("lastPosition");
        }
        edit.apply();
        lastPersistedPositionMs = -1;
    }

    private final class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleVideos.size(); }
        @Override public VideoItem getItem(int position) { return visibleVideos.get(position); }
        @Override public long getItemId(int position) { return getItem(position).uri.hashCode(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout rowRoot = vertical();
                LinearLayout row = horizontal();
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                rowRoot.addView(row, new LinearLayout.LayoutParams(-1, dp(88)));
                ImageView thumbnail = new ImageView(MainActivity.this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackground(rounded(SURFACE, 5));
                thumbnail.setClipToOutline(true);
                row.addView(thumbnail, new LinearLayout.LayoutParams(dp(116), dp(66)));

                LinearLayout details = vertical();
                details.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, -1, 1);
                detailParams.leftMargin = dp(14);
                row.addView(details, detailParams);
                TextView title = text("", 15, INK, true);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                details.addView(title, new LinearLayout.LayoutParams(-1, -2));
                TextView meta = text("", 12, MUTED, false);
                meta.setSingleLine(true);
                meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
                metaParams.topMargin = dp(4);
                details.addView(meta, metaParams);
                View divider = new View(MainActivity.this);
                divider.setBackgroundColor(LINE);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
                dividerParams.leftMargin = dp(150);
                rowRoot.addView(divider, dividerParams);
                holder = new RowHolder(thumbnail, title, meta);
                rowRoot.setTag(holder);
                convertView = rowRoot;
            } else {
                holder = (RowHolder) convertView.getTag();
            }
            VideoItem item = getItem(position);
            holder.title.setText(item.title);
            holder.title.setTextColor(isLastPlayedVideo(item) ? ORANGE : INK);
            holder.meta.setText(folderLabel(item.folder) + "  /  " + formatSize(item.size)
                    + "  /  " + formatTime((int) item.duration));
            requestThumbnail(item, holder.thumbnail);
            return convertView;
        }
    }

    private boolean isLastPlayedVideo(VideoItem item) {
        String fallback = preferences.getString(resumeUriKey(item.folder), null);
        return item.uri.toString().equals(preferences.getString("lastPlayedUri:" + item.folder, fallback));
    }

    private static final class RowHolder {
        final ImageView thumbnail;
        final TextView title;
        final TextView meta;
        RowHolder(ImageView thumbnail, TextView title, TextView meta) {
            this.thumbnail = thumbnail;
            this.title = title;
            this.meta = meta;
        }
    }

    private static final class FolderRowHolder {
        final ImageView thumbnail;
        final TextView title;
        final TextView meta;
        final TextView resume;
        FolderRowHolder(ImageView thumbnail, TextView title, TextView meta, TextView resume) {
            this.thumbnail = thumbnail;
            this.title = title;
            this.meta = meta;
            this.resume = resume;
        }
    }

    private static final class VideoItem {
        final Uri uri;
        final String title;
        final String folder;
        final long duration;
        final long size;
        final long dateAdded;
        long dateModified;
        int width;
        int height;
        int rotation;
        VideoItem(Uri uri, String title, String folder, long duration, long size) {
            this(uri, title, folder, duration, size, 0L);
        }
        VideoItem(Uri uri, String title, String folder, long duration, long size, long dateAdded) {
            this(uri, title, folder, duration, size, dateAdded, 0, 0, 0);
        }
        VideoItem(Uri uri, String title, String folder, long duration, long size,
                  long dateAdded, int width, int height, int rotation) {
            this.uri = uri;
            this.title = title;
            this.folder = folder;
            this.duration = duration;
            this.size = size;
            this.dateAdded = dateAdded;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
    }

    private static final class FolderItem {
        final String path;
        final ArrayList<VideoItem> videos = new ArrayList<>();
        long latestDate;
        long totalDuration;
        long totalSize;
        VideoItem previewVideo;
        VideoItem resumeVideo;
        VideoItem lastPlayedVideo;
        int resumePosition;

        FolderItem(String path) { this.path = path; }

        void add(VideoItem video) {
            videos.add(video);
            if (previewVideo == null || video.dateAdded > previewVideo.dateAdded) previewVideo = video;
            latestDate = Math.max(latestDate, video.dateAdded);
            totalDuration += video.duration;
            totalSize += video.size;
        }

        void loadResume(SharedPreferences preferences) {
            resumeVideo = null;
            lastPlayedVideo = null;
            resumePosition = 0;
            String uri = preferences.getString("resumeUri:" + path, null);
            String playedUri = preferences.getString("lastPlayedUri:" + path, uri);
            int position = preferences.getInt("resumePosition:" + path, 0);
            for (VideoItem video : videos) {
                if (video.uri.toString().equals(playedUri)) lastPlayedVideo = video;
                if (video.uri.toString().equals(uri) && position >= 0
                        && (video.duration <= 0 || position < video.duration - 1500)) {
                    resumeVideo = video;
                    resumePosition = position;
                }
            }
        }
    }

    private void openVideo(VideoItem item, int resumeAtMs) {
        restartPlaybackOnStart = false;
        previewLoader.setPlaybackActive(true);
        if (playerRoot == null) {
            originalScreenBrightness = getWindow().getAttributes().screenBrightness;
            brightnessChanged = false;
        }
        if (currentVideo != null && !currentVideo.uri.equals(item.uri) && playerRoot != null) {
            persistResumePosition();
        }
        releaseMediaPlayer();
        currentVideo = item;
        currentIndex = findVideoIndex(item.uri);
        pendingSeekMs = Math.max(0, resumeAtMs);
        savedPositionMs = pendingSeekMs;
        lastPersistedPositionMs = -1;
        durationMs = (int) Math.min(Integer.MAX_VALUE, item.duration);
        videoWidth = item.width;
        videoHeight = item.height;
        videoRotation = normalizeRotation(item.rotation);
        speed = 1.0f;
        speedIndex = 0;
        seeking = false;
        controlsVisible = !touchLocked;
        pausedForBackground = false;
        playWhenReady = true;
        mediaPrepared = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildPlayer();
        VLCVideoLayout owner = videoLayout;
        owner.post(() -> startMediaPlayer(item, owner));
        applyImmersiveMode();
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);
    }

    private int findVideoIndex(Uri uri) {
        for (int i = 0; i < videos.size(); i++) {
            if (videos.get(i).uri.equals(uri)) return i;
        }
        return -1;
    }

    private void buildPlayer() {
        playerRoot = new FrameLayout(this);
        playerRoot.setBackgroundColor(Color.BLACK);
        videoLayout = new VLCVideoLayout(this);
        playerRoot.addView(videoLayout, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        View gestureLayer = new View(this);
        playerRoot.addView(gestureLayer, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout loading = horizontal();
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(18), dp(12), dp(20), dp(12));
        loading.setBackground(rounded(0xe50b111b, 8));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
        loadingMessage = text("Preparing video...", 14, INK, true);
        LinearLayout.LayoutParams loadingTextParams = new LinearLayout.LayoutParams(-2, -2);
        loadingTextParams.leftMargin = dp(12);
        loading.addView(loadingMessage, loadingTextParams);
        loadingPanel = loading;
        playerRoot.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout top = vertical();
        top.setPadding(dp(8), dp(4), dp(8), dp(4));
        top.setBackground(glassBackground(18));
        controlsTop = top;
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topParams.setMargins(dp(12), dp(12), dp(12), 0);
        playerRoot.addView(top, topParams);
        LinearLayout heading = horizontal();
        LinearLayout trackControls = horizontal();
        heading.addView(iconButton(android.R.drawable.ic_menu_close_clear_cancel, "Back to videos", true, v -> showLibrary()));
        playerTitle = text(currentVideo.title, 15, Color.WHITE, true);
        playerTitle.setSingleLine(true);
        playerTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams playerTitleParams = new LinearLayout.LayoutParams(0, -2, 1);
        playerTitleParams.leftMargin = dp(8);
        heading.addView(playerTitle, playerTitleParams);
        heading.addView(iconButton(android.R.drawable.ic_menu_rotate, "Toggle screen orientation", true, v -> toggleOrientation()));
        audioButton = playerTextButton("Audio", "Select audio track", v -> showTrackSelection(false));
        trackControls.addView(audioButton, new LinearLayout.LayoutParams(dp(52), dp(44)));
        subtitleButton = playerTextButton("CC", "Select subtitle track", v -> showTrackSelection(true));
        trackControls.addView(subtitleButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        speedButton = text("1.0x", 14, Color.WHITE, true);
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setContentDescription("Playback speed");
        speedButton.setPadding(dp(10), dp(8), dp(10), dp(8));
        speedButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x44ffffff), rounded(Color.TRANSPARENT, 5), null));
        speedButton.setOnClickListener(v -> cycleSpeed());
        trackControls.addView(speedButton, new LinearLayout.LayoutParams(-2, dp(44)));
        decoderButton = playerTextButton("", "Select video decoding mode", v -> showDecoderSelection());
        trackControls.addView(decoderButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        updateDecoderButton();
        top.addView(heading, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(-2, dp(44));
        trackParams.gravity = Gravity.END;
        top.addView(trackControls, trackParams);
        top.addOnLayoutChangeListener((view, left, topEdge, right, bottomEdge, oldLeft, oldTop, oldRight, oldBottom) -> {
            boolean wide = right - left >= dp(600);
            int orientation = wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
            if (top.getOrientation() == orientation) return;
            top.setOrientation(orientation);
            heading.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, dp(48), wide ? 1 : 0));
        });

        LinearLayout bottom = vertical();
        bottom.setPadding(dp(8), dp(2), dp(8), dp(2));
        bottom.setBackground(glassBackground(0));
        controlsBottom = bottom;
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        playerRoot.addView(bottom, bottomParams);

        adjustmentHud = text("", 14, Color.WHITE, true);
        adjustmentHud.setGravity(Gravity.CENTER);
        adjustmentHud.setPadding(dp(16), dp(12), dp(16), dp(12));
        adjustmentHud.setBackground(rounded(0xe50b111b, 8));
        adjustmentHud.setVisibility(View.GONE);
        playerRoot.addView(adjustmentHud, new FrameLayout.LayoutParams(-2, dp(48), Gravity.CENTER));

        LinearLayout times = horizontal();
        currentTimeText = text("0:00", 12, Color.WHITE, false);
        durationText = text(formatTime(durationMs), 12, Color.WHITE, false);
        times.addView(currentTimeText, new LinearLayout.LayoutParams(dp(48), -2));
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff46566e));
        times.addView(seekBar, new LinearLayout.LayoutParams(0, dp(28), 1));
        durationText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        times.addView(durationText, new LinearLayout.LayoutParams(dp(48), -2));
        bottom.addView(times, new LinearLayout.LayoutParams(-1, -2));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) currentTimeText.setText(formatTime((int) (durationMs * (progress / 1000.0))));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (durationMs > 0 && mediaPlayer != null && mediaPrepared) {
                    savedPositionMs = (int) (durationMs * (bar.getProgress() / 1000.0));
                    seekTo(savedPositionMs);
                }
                seeking = false;
                scheduleHideControls();
            }
        });

        FrameLayout playbackRow = new FrameLayout(this);
        playbackRow.setPadding(dp(64), 0, dp(8), 0);
        bottom.addView(playbackRow, new LinearLayout.LayoutParams(-1, dp(64)));
        LinearLayout playback = horizontal();
        playback.setGravity(Gravity.CENTER);
        playbackRow.addView(playback, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        previousVideoButton = iconButton(R.drawable.ic_previous, "Previous video", true, v -> playAdjacent(-1));
        playPauseButton = iconButton(R.drawable.ic_pause, "Pause", true, v -> togglePlayback());
        playPauseButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        nextVideoButton = iconButton(R.drawable.ic_next, "Next video", true, v -> playAdjacent(1));
        for (ImageButton button : new ImageButton[] {previousVideoButton, playPauseButton, nextVideoButton}) {
            button.setBackground(transportButtonBackground());
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        addTransportButton(playback, previousVideoButton, 56);
        addTransportButton(playback, playPauseButton, 56);
        addTransportButton(playback, nextVideoButton, 56);
        updateTransportButtons();

        lockButton = iconButton(R.drawable.ic_lock, "Lock playback touches", true, v -> setTouchLocked(true));
        FrameLayout.LayoutParams lockParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.LEFT);
        lockParams.setMargins(dp(12), 0, 0, dp(10));
        playerRoot.addView(lockButton, lockParams);

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (touchLocked) return true;
                setControlsVisible(!controlsVisible);
                if (controlsVisible) scheduleHideControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (touchLocked) return true;
                float edgeWidth = videoLayout.getWidth() / 3f;
                if (event.getX() < edgeWidth) {
                    seekBy(-10000);
                } else if (event.getX() > videoLayout.getWidth() - edgeWidth) {
                    seekBy(10000);
                } else {
                    togglePlayback();
                }
                setControlsVisible(true);
                scheduleHideControls();
                return true;
            }
            @Override public boolean onScroll(MotionEvent start, MotionEvent current, float distanceX, float distanceY) {
                if (touchLocked) return true;
                float dx = current.getX() - start.getX();
                float dy = start.getY() - current.getY();
                if (activeGesture == GESTURE_NONE) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) < dp(12)) return false;
                    if (Math.abs(dy) > Math.abs(dx)) {
                        if (start.getX() < viewWidth(videoLayout) * 0.44f) {
                            activeGesture = GESTURE_BRIGHTNESS;
                            gestureStartBrightness = currentBrightness();
                        } else if (start.getX() > viewWidth(videoLayout) * 0.56f) {
                            activeGesture = GESTURE_VOLUME;
                            gestureStartVolume = currentVolumePercent();
                        } else {
                            return false;
                        }
                    } else {
                        if (!mediaPrepared || durationMs <= 0 || mediaPlayer == null || !mediaPlayer.isSeekable()) return false;
                        activeGesture = GESTURE_SEEK;
                        gestureStartPosition = safePosition();
                        gestureTargetPosition = gestureStartPosition;
                        seekQueue.beginGesture();
                        if (seekFrameMonitor != null) seekFrameMonitor.close();
                        seekFrameMonitor = new SeekFrameMonitor(handler);
                        scrubPreviewActive = true;
                        lastSeekHudFrame = -1;
                        showSeekHud(gestureStartPosition);
                        seeking = true;
                        setControlsVisible(false);
                        handler.removeCallbacks(hideControls);
                    }
                }

                if (activeGesture == GESTURE_BRIGHTNESS) {
                    float brightness = clamp(gestureStartBrightness + dy / Math.max(1, viewHeight(videoLayout)), 0.03f, 1.0f);
                    setWindowBrightness(brightness);
                    showAdjustmentHud("Brightness  " + Math.round(brightness * 100) + "%");
                } else if (activeGesture == GESTURE_VOLUME && audioManager != null) {
                    int percent = clamp(gestureStartVolume
                            + Math.round(dy / Math.max(1, viewHeight(videoLayout)) * 200), 0, 200);
                    setGestureVolume(percent);
                    showAdjustmentHud("Volume  " + percent + "%" + (percent > 100 ? "  Boost" : ""));
                } else if (activeGesture == GESTURE_SEEK) {
                    gestureTargetPosition = clamp(gestureStartPosition
                            + SeekGesture.seekDeltaMs(dx, Math.max(1, viewWidth(videoLayout))), 0, durationMs);
                    int previewPosition = Math.min(gestureTargetPosition, Math.max(0, durationMs - 100));
                    if (seekQueue.requestPreview(previewPosition)) scheduleSeekRequests();
                }
                return true;
            }
        });
        gestureLayer.setOnTouchListener((view, event) -> {
            if (touchLocked) return true;
            gestures.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) finishPlayerGesture(true);
            else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) finishPlayerGesture(false);
            return true;
        });
        touchLockOverlay = new FrameLayout(this);
        touchLockOverlay.setClickable(true);
        touchLockOverlay.setOnTouchListener((view, event) -> true);
        playerRoot.addView(touchLockOverlay, new FrameLayout.LayoutParams(-1, -1));
        unlockButton = iconButton(R.drawable.ic_lock, "Unlock playback touches", true, v -> setTouchLocked(false));
        unlockButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        unlockButton.setImageTintList(ColorStateList.valueOf(ACCENT));
        unlockButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                rounded(0x99000000, 24), null));
        FrameLayout.LayoutParams unlockParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.LEFT);
        unlockParams.setMargins(dp(12), 0, 0, dp(10));
        touchLockOverlay.addView(unlockButton, unlockParams);
        setControlsVisible(controlsVisible);
        setContentView(playerRoot);
    }

    private int normalizeRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return normalized == 90 || normalized == 180 || normalized == 270 ? normalized : 0;
    }

    private Future<LibVLC> prepareEngine() {
        if (engineFuture == null) {
            Context application = getApplicationContext();
            engineFuture = playerIo.submit(() -> new LibVLC(application, PlaybackOptions.create()));
        }
        return engineFuture;
    }

    private void startMediaPlayer(VideoItem item, VLCVideoLayout owner) {
        if (currentVideo != item || videoLayout != owner || playerRoot == null || isDestroyed()) return;
        int generation = playerLoadGeneration;
        Future<LibVLC> ready = prepareEngine();
        handler.removeCallbacks(prepareTimeout);
        handler.postDelayed(prepareTimeout, 30000);
        playerIo.execute(() -> {
            if (generation != playerLoadGeneration) return;
            try {
                // Library initialization and provider access must not block the UI thread.
                LibVLC engine = ready.get();
                String scheme = item.uri.getScheme();
                if (!"content".equals(scheme) && !"file".equals(scheme)) {
                    throw new IOException("Only local video files are supported");
                }
                AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(item.uri, "r");
                if (descriptor == null) throw new IOException("Cannot open video file");
                handler.post(() -> attachPreparedVideo(item, owner, generation, engine, descriptor));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                handler.post(() -> reportPlaybackFailure(item, generation, error));
            } catch (ExecutionException | RuntimeException | IOException | LinkageError error) {
                handler.post(() -> reportPlaybackFailure(item, generation, error));
            }
        });
    }

    private void attachPreparedVideo(VideoItem item, VLCVideoLayout owner, int generation,
                                     LibVLC engine, AssetFileDescriptor descriptor) {
        if (generation != playerLoadGeneration || currentVideo != item || videoLayout != owner
                || playerRoot == null || isDestroyed()) {
            try { descriptor.close(); } catch (IOException ignored) {}
            return;
        }
        currentVideoFd = descriptor;
        try {
            MediaPlayer player = new MediaPlayer(engine);
            mediaPlayer = player;
            mediaPrepared = false;
            externalSubtitleAttached = false;
            // Android only supplies a PCM output sink. Encoded audio never goes to a device decoder.
            player.setAudioDigitalOutputEnabled(false);
            player.setAudioOutputDevice("stereo");
            player.attachViews(owner, null, true, false);
            player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            player.setEventListener(event -> handlePlayerEvent(player, event));

            Media media = new Media(engine, currentVideoFd);
            try {
                // Per-media decoder options select hardware or software while retaining codec fallback.
                media.addOption(":file-caching=" + PlaybackOptions.LOCAL_CACHE_MS);
                media.addOption(":no-input-fast-seek");
                boolean useHardware = decoderMode != DECODER_SOFTWARE;
                media.setHWDecoderEnabled(useHardware, decoderMode == DECODER_HARDWARE_PLUS);
                if (decoderMode == DECODER_HARDWARE_PLUS) media.addOption(":avcodec-hw=any");
                if (pendingSeekMs > 0) {
                    media.addOption(String.format(Locale.US, ":start-time=%.3f", pendingSeekMs / 1000.0));
                }
                player.setMedia(media);
            } finally {
                media.release();
            }
            player.play();
        } catch (RuntimeException | LinkageError error) {
            reportPlaybackFailure(item, generation, error);
        }
    }

    private void reportPlaybackFailure(VideoItem item, int generation, Throwable error) {
        if (generation != playerLoadGeneration || currentVideo != item || isDestroyed()) return;
        Log.e("APocketVid", "Could not initialize local playback", error);
        handler.removeCallbacks(prepareTimeout);
        Toast.makeText(this, "Could not start playback", Toast.LENGTH_LONG).show();
        showLibrary();
    }

    private void handlePlayerEvent(MediaPlayer player, MediaPlayer.Event event) {
        if (mediaPlayer != player || currentVideo == null || playerRoot == null) return;
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                boolean firstStart = !mediaPrepared;
                mediaPrepared = true;
                handler.removeCallbacks(prepareTimeout);
                if (loadingPanel != null) loadingPanel.setVisibility(View.GONE);
                updateVideoDuration(player.getLength());
                updateVideoGeometry(player);
                if (firstStart) {
                    // start-time already handles resume; seeking here again decodes the same GOP twice.
                    pendingSeekMs = 0;
                    persistResumePosition();
                    if (speed != 1f) player.setRate(speed);
                    player.setVolume(audioBoostPercent);
                    player.setAudioDelay(preferences.getInt("audioDelayMs:" + currentVideo.uri, 0) * 1000L);
                    restoreTrackSelections(player);
                    loadSavedExternalAudio();
                    loadSavedExternalSubtitle();
                    handler.removeCallbacks(progressTick);
                    handler.post(progressTick);
                    VideoItem video = currentVideo;
                    VLCVideoLayout owner = videoLayout;
                    handler.postDelayed(() -> capturePlaybackPreview(player, video, owner, 0), 900);
                }
                if (!playWhenReady) player.pause();
                updateTrackControls(player);
                updatePlaybackIcon();
                scheduleHideControls();
                break;
            case MediaPlayer.Event.LengthChanged:
                updateVideoDuration(event.getLengthChanged());
                break;
            case MediaPlayer.Event.Vout:
                updateVideoGeometry(player);
                break;
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESDeleted:
                updateTrackControls(player);
                if (mediaPrepared) restoreTrackSelections(player);
                break;
            case MediaPlayer.Event.Paused:
                updatePlaybackIcon();
                break;
            case MediaPlayer.Event.EndReached:
                handler.removeCallbacks(processSeekRequests);
                seekQueue.clear();
                seekQueueScheduled = false;
                if (seekFrameMonitor != null) seekFrameMonitor.close();
                seekFrameMonitor = null;
                scrubPreviewActive = false;
                playWhenReady = false;
                clearResumePosition(currentVideo);
                VideoItem next = adjacentVideo(1);
                if (next != null) {
                    currentVideo = null;
                    openVideo(next, 0);
                } else {
                    updatePlaybackIcon();
                    setControlsVisible(true);
                }
                break;
            case MediaPlayer.Event.EncounteredError:
                handler.removeCallbacks(prepareTimeout);
                Toast.makeText(this, "The bundled player could not decode this file", Toast.LENGTH_LONG).show();
                showLibrary();
                break;
        }
    }

    private void updateVideoDuration(long length) {
        if (length > 0) durationMs = (int) Math.min(Integer.MAX_VALUE, length);
        if (durationText != null) durationText.setText(formatTime(durationMs));
    }

    private void updateVideoGeometry(MediaPlayer player) {
        IMedia.VideoTrack track = player.getCurrentVideoTrack();
        if (track != null && track.width > 0 && track.height > 0) {
            videoWidth = track.width;
            videoHeight = track.height;
            videoRotation = track.orientation >= IMedia.VideoTrack.Orientation.LeftTop ? 90 : 0;
        }
        applyVideoOrientation();
        adjustVideoSurface(videoWidth, videoHeight);
    }

    private void adjustVideoSurface(int width, int height) {
        if (videoLayout == null || mediaPlayer == null) return;
        if (width > 0) videoWidth = width;
        if (height > 0) videoHeight = height;
        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        videoLayout.post(() -> {
            if (mediaPlayer != null && videoLayout != null) mediaPlayer.updateVideoSurfaces();
        });
    }

    private void applyVideoOrientation() {
        int width = videoWidth > 0 ? videoWidth : currentVideo == null ? 0 : currentVideo.width;
        int height = videoHeight > 0 ? videoHeight : currentVideo == null ? 0 : currentVideo.height;
        int rotation = videoRotation;
        if (width <= 0 || height <= 0) return;
        if (rotation == 90 || rotation == 270) {
            int swap = width;
            width = height;
            height = swap;
        }
        boolean portrait = height > width;
        if (orientationFlipped) portrait = !portrait;
        int requested = portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        if (getRequestedOrientation() != requested) setRequestedOrientation(requested);
    }

    private void releaseMediaPlayer() {
        playerLoadGeneration++;
        handler.removeCallbacks(prepareTimeout);
        handler.removeCallbacks(processSeekRequests);
        seekQueue.clear();
        seekQueueScheduled = false;
        if (seekFrameMonitor != null) seekFrameMonitor.close();
        seekFrameMonitor = null;
        scrubPreviewActive = false;
        activeGesture = GESTURE_NONE;
        seeking = false;
        subtitleLoadGeneration++;
        audioLoadGeneration++;
        externalSubtitleAttached = false;
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        mediaPrepared = false;
        if (player != null) {
            player.setEventListener(null);
            try { player.detachViews(); } catch (RuntimeException ignored) {}
        }
        AssetFileDescriptor descriptor = currentVideoFd;
        currentVideoFd = null;
        ArrayList<File> importedFiles = new ArrayList<>(importedTrackFiles);
        importedTrackFiles.clear();
        if (player != null || descriptor != null || !importedFiles.isEmpty()) playerIo.execute(() -> {
            // Detach views on the main thread, then let native decoding stop off the UI thread.
            try {
                if (player != null) {
                    try { player.stop(); } catch (RuntimeException ignored) {}
                    player.release();
                }
            } finally {
                if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) {}
                for (File file : importedFiles) file.delete();
            }
        });
    }

    private void releaseLibVlc() {
        Future<LibVLC> engine = engineFuture;
        engineFuture = null;
        if (engine != null) playerIo.execute(() -> {
            try { engine.get().release(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | RuntimeException ignored) {}
        });
    }

    private void addTransportButton(LinearLayout row, ImageButton button, int size) {
        FrameLayout slot = new FrameLayout(this);
        row.addView(slot, new LinearLayout.LayoutParams(0, -1, 1));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(size), Gravity.CENTER);
        params.setMargins(dp(2), 0, dp(2), 0);
        slot.addView(button, params);
    }

    private RippleDrawable transportButtonBackground() {
        GradientDrawable outline = rounded(0x221d3f61, 2);
        outline.setStroke(dp(1), 0xaa268bff);
        return new RippleDrawable(ColorStateList.valueOf(0x55388fff), outline, null);
    }

    private void addTransportDivider(LinearLayout row) {
        FrameLayout slot = new FrameLayout(this);
        row.addView(slot, new LinearLayout.LayoutParams(dp(16), -1));
        View divider = new View(this);
        GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x284d9fff, 0xb379bcff, 0x284d9fff});
        pill.setCornerRadius(dp(2));
        divider.setBackground(pill);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        slot.addView(divider, new FrameLayout.LayoutParams(dp(2), dp(22), Gravity.CENTER));
    }

    private void updateTransportButtons() {
        if (previousVideoButton == null || nextVideoButton == null) return;
        previousVideoButton.setEnabled(adjacentVideo(-1) != null);
        nextVideoButton.setEnabled(adjacentVideo(1) != null);
        previousVideoButton.setAlpha(previousVideoButton.isEnabled() ? 1f : 0.35f);
        nextVideoButton.setAlpha(nextVideoButton.isEnabled() ? 1f : 0.35f);
    }

    private SurfaceView findVideoSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView surface = findVideoSurface(group.getChildAt(i));
                if (surface != null) return surface;
            }
        }
        return null;
    }

    private void capturePlaybackPreview(MediaPlayer player, VideoItem video, VLCVideoLayout owner, int attempt) {
        if (mediaPlayer != player || currentVideo != video || videoLayout != owner || owner == null) return;
        VideoPreviewLoader.Source source = previewSource(video);
        if (previewLoader.hasPreview(source)) return;
        SurfaceView surface = findVideoSurface(owner);
        if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) return;
        float scale = VideoPreviewLoader.MAX_EDGE / (float) Math.max(surface.getWidth(), surface.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(surface.getWidth() * scale)),
                Math.max(1, Math.round(surface.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surface, bitmap, result -> {
                if (result == PixelCopy.SUCCESS && currentVideo == video && mediaPlayer == player
                        && (attempt >= 2 || VideoPreviewLoader.hasVisibleContent(bitmap))) {
                    previewLoader.storePlaybackFrame(source, bitmap);
                } else {
                    bitmap.recycle();
                    if (attempt < 2) handler.postDelayed(
                            () -> capturePlaybackPreview(player, video, owner, attempt + 1), 1500);
                }
            }, handler);
        } catch (IllegalArgumentException error) {
            bitmap.recycle();
            if (attempt < 2) handler.postDelayed(
                    () -> capturePlaybackPreview(player, video, owner, attempt + 1), 1500);
        }
    }

    private TextView playerTextButton(String label, String description, View.OnClickListener action) {
        TextView button = text(label, 12, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x44ffffff), rounded(Color.TRANSPARENT, 5), null));
        button.setOnClickListener(action);
        button.setFocusable(true);
        return button;
    }

    private void showDecoderSelection() {
        PopupMenu menu = new PopupMenu(this, decoderButton);
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.getMenu().add(0, DECODER_SOFTWARE, 0, "Software decoding")
                .setCheckable(true).setChecked(decoderMode == DECODER_SOFTWARE);
        menu.getMenu().add(0, DECODER_HARDWARE, 1, "Hardware decoding")
                .setCheckable(true).setChecked(decoderMode == DECODER_HARDWARE);
        menu.getMenu().add(0, DECODER_HARDWARE_PLUS, 2, "Hardware+ decoding")
                .setCheckable(true).setChecked(decoderMode == DECODER_HARDWARE_PLUS);
        menu.setOnMenuItemClickListener(item -> {
            setDecoderMode(item.getItemId());
            return true;
        });
        menu.show();
    }

    private void updateDecoderButton() {
        if (decoderButton == null) return;
        String label = decoderMode == DECODER_HARDWARE_PLUS ? "HW+"
                : decoderMode == DECODER_HARDWARE ? "HW" : "SW";
        decoderButton.setText(label);
        decoderButton.setContentDescription("Video decoding: " + label);
    }

    private void setDecoderMode(int mode) {
        mode = clamp(mode, DECODER_SOFTWARE, DECODER_HARDWARE_PLUS);
        if (decoderMode == mode) return;
        decoderMode = mode;
        preferences.edit().putInt("decoderMode", mode)
                .putBoolean("hardwareDecoding", mode != DECODER_SOFTWARE).apply();
        updateDecoderButton();
        if (currentVideo != null && playerRoot != null) {
            int position = mediaPrepared ? safePosition() : savedPositionMs;
            float previousSpeed = speed;
            int previousSpeedIndex = speedIndex;
            boolean shouldPlay = playWhenReady;
            persistResumePosition();
            openVideo(currentVideo, position);
            speed = previousSpeed;
            speedIndex = previousSpeedIndex;
            playWhenReady = shouldPlay;
            updateSpeedButton();
        }
    }

    private void updateTrackControls(MediaPlayer player) {
        if (audioButton == null || subtitleButton == null) return;
        audioButton.setEnabled(true);
        audioButton.setAlpha(1.0f);
        subtitleButton.setEnabled(true);
        subtitleButton.setAlpha(1.0f);
    }

    private ArrayList<MediaPlayer.TrackDescription> selectableTracks(MediaPlayer player, boolean subtitles) {
        ArrayList<MediaPlayer.TrackDescription> result = new ArrayList<>();
        MediaPlayer.TrackDescription[] tracks = subtitles ? player.getSpuTracks() : player.getAudioTracks();
        if (tracks != null) {
            for (MediaPlayer.TrackDescription track : tracks) if (track.id >= 0) result.add(track);
        }
        return result;
    }

    private void restoreTrackSelections(MediaPlayer player) {
        if (currentVideo == null) return;
        String uri = currentVideo.uri.toString();
        // VLC track IDs are different from Android MediaPlayer track indices.
        int savedAudio = preferences.getInt("vlcAudioTrack:" + uri, -2);
        ArrayList<MediaPlayer.TrackDescription> audioTracks = selectableTracks(player, false);
        boolean restoredAudio = false;
        for (MediaPlayer.TrackDescription track : audioTracks) {
            if (track.id == savedAudio) {
                restoredAudio = player.setAudioTrack(track.id);
                break;
            }
        }
        if (!restoredAudio && player.getAudioTrack() < 0 && !audioTracks.isEmpty()) {
            player.setAudioTrack(audioTracks.get(0).id);
        }
        String externalUri = preferences.getString("subtitleFileUri:" + uri, null);
        if (externalUri != null) {
            if (!externalSubtitleAttached) player.setSpuTrack(-1);
        } else {
            int savedSubtitle = preferences.getInt("vlcSubtitleTrack:" + uri, -2);
            if (savedSubtitle == -1) player.setSpuTrack(-1);
            else for (MediaPlayer.TrackDescription track : selectableTracks(player, true)) {
                if (track.id == savedSubtitle) { player.setSpuTrack(track.id); break; }
            }
        }
    }

    private String trackLabel(MediaPlayer player, MediaPlayer.TrackDescription description, boolean subtitles, int number) {
        String name = description.name;
        if (name == null || name.trim().isEmpty()) name = (subtitles ? "Subtitle " : "Audio ") + number;
        IMedia media = player.getMedia();
        if (media == null) return name;
        try {
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track track = media.getTrack(i);
                if (track == null || track.id != description.id) continue;
                if (subtitles && track.type != IMedia.Track.Type.Text) continue;
                if (!subtitles && track.type != IMedia.Track.Type.Audio) continue;
                if (track.codec != null && !track.codec.trim().isEmpty()) name += " / " + track.codec.trim().toUpperCase(Locale.ROOT);
                if (track instanceof IMedia.AudioTrack) name += " / " + ((IMedia.AudioTrack) track).channels + " ch";
                break;
            }
        } finally {
            media.release();
        }
        return name;
    }

    private AlertDialog showTrackSelection(boolean subtitles) {
        if (!mediaPrepared || mediaPlayer == null || currentVideo == null) {
            Toast.makeText(this, "Player is still preparing", Toast.LENGTH_SHORT).show();
            return null;
        }
        MediaPlayer selectedPlayer = mediaPlayer;
        VideoItem selectedVideo = currentVideo;
        ArrayList<MediaPlayer.TrackDescription> tracks = selectableTracks(selectedPlayer, subtitles);
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        int selected = subtitles ? selectedPlayer.getSpuTrack() : selectedPlayer.getAudioTrack();
        int checked = -1;
        if (subtitles) {
            labels.add("Off");
            ids.add(-1);
            checked = selected < 0 ? 0 : -1;
        }
        for (int i = 0; i < tracks.size(); i++) {
            MediaPlayer.TrackDescription track = tracks.get(i);
            labels.add(trackLabel(selectedPlayer, track, subtitles, i + 1));
            ids.add(track.id);
            if (track.id == selected) checked = ids.size() - 1;
        }
        int loadFileChoice = ids.size();
        labels.add(subtitles ? "Load subtitle from storage..." : "Load audio from storage...");
        int syncChoice = -1;
        int boostChoice = -1;
        if (!subtitles) {
            syncChoice = labels.size();
            labels.add("Audio sync correction...");
            boostChoice = labels.size();
            labels.add("Audio boost...");
        }
        final int audioSyncChoice = syncChoice;
        final int audioBoostChoice = boostChoice;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.PlayerPanelTheme);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(builder.getContext(),
                android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                row.setTextColor(INK);
                row.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
                row.setBackgroundColor(Color.TRANSPARENT);
                return row;
            }
        };
        AlertDialog trackDialog = builder
                .setTitle(subtitles ? "Subtitles" : "Audio track")
                .setSingleChoiceItems(adapter, subtitles ? -1 : checked, (dialog, choice) -> {
                    if (mediaPlayer != selectedPlayer || currentVideo != selectedVideo) {
                        dialog.dismiss();
                        return;
                    }
                    dialog.dismiss();
                    if (choice == loadFileChoice) {
                        if (subtitles) pickSubtitleFile(); else pickAudioFile();
                        return;
                    }
                    if (choice == audioSyncChoice) {
                        showAudioDelayDialog();
                        return;
                    }
                    if (choice == audioBoostChoice) {
                        showAudioBoostDialog();
                        return;
                    }
                    if (choice < 0 || choice >= ids.size()) return;
                    int id = ids.get(choice);
                    boolean changed = subtitles ? selectedPlayer.setSpuTrack(id) : selectedPlayer.setAudioTrack(id);
                    if (!changed) {
                        Toast.makeText(this, "Could not select that track", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    SharedPreferences.Editor edit = preferences.edit();
                    if (subtitles) {
                        subtitleLoadGeneration++;
                        externalSubtitleAttached = false;
                        edit.putInt("vlcSubtitleTrack:" + selectedVideo.uri, id)
                                .remove("subtitleFileUri:" + selectedVideo.uri)
                                .remove("subtitleFileName:" + selectedVideo.uri);
                    } else edit.putInt("vlcAudioTrack:" + selectedVideo.uri, id)
                            .remove("audioFileUri:" + selectedVideo.uri)
                            .remove("audioFileName:" + selectedVideo.uri);
                    edit.apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        showPlayerPanel(trackDialog);
        if (subtitles) {
            // Supplying checked to AlertController also scrolls to that track during layout.
            // Mark the radio separately and position Off after the window has been sized.
            ListView list = trackDialog.getListView();
            if (checked >= 0) list.setItemChecked(checked, true);
            list.setSelectionFromTop(0, 0);
            list.post(() -> {
                if (trackDialog.isShowing()) list.setSelectionFromTop(0, 0);
            });
        }
        return trackDialog;
    }

    private void showPlayerPanel(AlertDialog dialog) {
        dialog.setOnDismissListener(ignored -> applyImmersiveMode());
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0f);
        window.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View decor = window.getDecorView();
        decor.setPadding(0, 0, 0, 0);
        decor.setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
        int availableWidth = playerRoot != null && playerRoot.getWidth() > 0
                ? playerRoot.getWidth() : getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(dp(360), Math.round(availableWidth * .85f)), -2);
        decor.post(() -> addPlayerPanelTextShadows(decor));
    }

    private void addPlayerPanelTextShadows(View view) {
        if (view instanceof TextView) ((TextView) view).setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) addPlayerPanelTextShadows(group.getChildAt(i));
        }
    }

    private void showAudioBoostDialog() {
        if (mediaPlayer == null || currentVideo == null) return;
        int initial = audioBoostPercent;
        int[] selected = { initial };
        LinearLayout content = vertical();
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView value = text("Audio boost  " + initial + "%", 14, INK, true);
        SeekBar level = new SeekBar(this);
        level.setMax(100);
        level.setProgress(initial - 100);
        level.setProgressTintList(ColorStateList.valueOf(ACCENT));
        level.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        content.addView(value);
        content.addView(level, new LinearLayout.LayoutParams(-1, dp(44)));
        level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                selected[0] = 100 + progress;
                value.setText("Audio boost  " + selected[0] + "%");
                setAudioBoost(selected[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PlayerPanelTheme)
                .setTitle("Audio boost")
                .setView(content)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .create();
        dialog.setOnCancelListener(ignored -> setAudioBoost(initial));
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                preferences.edit().putInt("audioBoostPercent", selected[0]).apply();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                setAudioBoost(initial);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                selected[0] = 100;
                level.setProgress(0);
                preferences.edit().putInt("audioBoostPercent", 100).apply();
                dialog.dismiss();
            });
        });
        showPlayerPanel(dialog);
    }

    private void setAudioBoost(int percent) {
        audioBoostPercent = clamp(percent, 100, 200);
        if (mediaPlayer != null) {
            try { mediaPlayer.setVolume(audioBoostPercent); }
            catch (RuntimeException ignored) {}
        }
    }

    private int currentVolumePercent() {
        if (audioManager == null) return 0;
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (maximum <= 0) return 0;
        return volume >= maximum ? audioBoostPercent : Math.round(volume * 100f / maximum);
    }

    private void setGestureVolume(int percent) {
        if (audioManager == null) return;
        percent = clamp(percent, 0, 200);
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        // Android's stream stops at 100%; LibVLC supplies the additional PCM gain.
        setAudioBoost(Math.max(100, percent));
        int volume = Math.round(Math.min(percent, 100) * maximum / 100f);
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != volume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        }
    }

    private AlertDialog showAudioDelayDialog() {
        if (mediaPlayer == null || currentVideo == null) return null;
        MediaPlayer selectedPlayer = mediaPlayer;
        String videoUri = currentVideo.uri.toString();
        int initial = clamp(preferences.getInt("audioDelayMs:" + videoUri, 0), -10000, 10000);
        int[] selected = { initial };
        LinearLayout content = vertical();
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        content.addView(text("Audio offset in seconds", 14, INK, true));
        LinearLayout adjustments = horizontal();
        EditText value = new EditText(this);
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        value.setSingleLine(true);
        value.setSelectAllOnFocus(true);
        value.setImeOptions(EditorInfo.IME_ACTION_DONE);
        value.setTextColor(INK);
        value.setTextSize(20);
        value.setGravity(Gravity.CENTER);
        value.setContentDescription("Audio sync offset in seconds");
        value.setText(AudioSync.formatDelay(initial));
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                try {
                    selected[0] = AudioSync.parseDelayMs(text.toString());
                    value.setError(null);
                    if (mediaPlayer == selectedPlayer) selectedPlayer.setAudioDelay(selected[0] * 1000L);
                } catch (IllegalArgumentException ignored) {
                    // Allow an incomplete sign or decimal while the user is typing.
                }
            }
        });
        TextView decrease = playerTextButton("−", "Decrease audio delay by 0.1 seconds", v -> {
            value.setText(AudioSync.formatDelay(AudioSync.adjustDelayMs(selected[0], -100)));
            value.setSelection(value.length());
        });
        TextView increase = playerTextButton("+", "Increase audio delay by 0.1 seconds", v -> {
            value.setText(AudioSync.formatDelay(AudioSync.adjustDelayMs(selected[0], 100)));
            value.setSelection(value.length());
        });
        decrease.setTextSize(24);
        increase.setTextSize(24);
        decrease.setBackground(transportButtonBackground());
        increase.setBackground(transportButtonBackground());
        adjustments.addView(decrease, new LinearLayout.LayoutParams(dp(48), dp(48)));
        adjustments.addView(value, new LinearLayout.LayoutParams(0, dp(52), 1));
        adjustments.addView(increase, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(adjustments, new LinearLayout.LayoutParams(-1, -2));
        content.addView(text("−10 to +10 seconds · buttons adjust by 0.1 s", 12, INK, false));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PlayerPanelTheme)
                .setTitle("Audio sync correction")
                .setView(content)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .create();
        dialog.setOnCancelListener(ignored -> {
            if (mediaPlayer == selectedPlayer) selectedPlayer.setAudioDelay(initial * 1000L);
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    selected[0] = AudioSync.parseDelayMs(value.getText().toString());
                } catch (IllegalArgumentException error) {
                    value.setError("Enter a number from −10 to +10 seconds");
                    return;
                }
                preferences.edit().putInt("audioDelayMs:" + videoUri, selected[0]).apply();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (mediaPlayer == selectedPlayer) selectedPlayer.setAudioDelay(initial * 1000L);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (mediaPlayer == selectedPlayer) selectedPlayer.setAudioDelay(0);
                preferences.edit().putInt("audioDelayMs:" + videoUri, 0).apply();
                dialog.dismiss();
            });
        });
        value.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        showPlayerPanel(dialog);
        return dialog;
    }

    private void pickSubtitleFile() {
        launchTrackFilePicker(SUBTITLE_FILE_REQUEST, "*/*");
    }

    private void pickAudioFile() {
        launchTrackFilePicker(AUDIO_FILE_REQUEST, "audio/*");
    }

    private void launchTrackFilePicker(int requestCode, String mimeType) {
        if (playerRoot == null || currentVideo == null) return;
        mediaPickerActive = true;
        pickedTrackVideoUri = currentVideo.uri.toString();
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType(mimeType);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, requestCode);
        } catch (RuntimeException error) {
            mediaPickerActive = false;
            pickedTrackVideoUri = null;
            Toast.makeText(this, "Storage picker is unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSavedExternalSubtitle() {
        if (currentVideo == null) return;
        String value = preferences.getString("subtitleFileUri:" + currentVideo.uri, null);
        if (value != null) loadExternalSubtitle(Uri.parse(value), currentVideo, false);
    }

    private void loadSavedExternalAudio() {
        if (currentVideo == null) return;
        String value = preferences.getString("audioFileUri:" + currentVideo.uri, null);
        if (value != null) loadExternalAudio(Uri.parse(value), currentVideo, false);
    }

    private void loadExternalSubtitle(Uri uri, VideoItem video, boolean persist) {
        MediaPlayer player = mediaPlayer;
        if (player == null) return;
        int generation = ++subtitleLoadGeneration;
        io.execute(() -> {
            File subtitleFile = null;
            String name = externalTrackDisplayName(uri, "External subtitle.srt");
            try {
                subtitleFile = copyExternalTrack(uri, "subtitle-", name, ".srt", 16L * 1024 * 1024);
            } catch (IOException | RuntimeException error) {
                if (subtitleFile != null) subtitleFile.delete();
                runOnUiThread(() -> {
                    if (currentVideo == video && generation == subtitleLoadGeneration) {
                        Toast.makeText(this, "Could not read subtitle file", Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            File imported = subtitleFile;
            runOnUiThread(() -> {
                if (currentVideo != video || mediaPlayer != player || playerRoot == null
                        || generation != subtitleLoadGeneration) {
                    imported.delete();
                    return;
                }
                boolean wasAttached = externalSubtitleAttached;
                externalSubtitleAttached = true;
                boolean added = player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(imported), true);
                if (!added) {
                    externalSubtitleAttached = wasAttached;
                    imported.delete();
                    Toast.makeText(this, "This subtitle could not be loaded", Toast.LENGTH_LONG).show();
                    return;
                }
                importedTrackFiles.add(imported);
                if (persist) {
                    preferences.edit().putString("subtitleFileUri:" + video.uri, uri.toString())
                            .putString("subtitleFileName:" + video.uri, name)
                            .putInt("vlcSubtitleTrack:" + video.uri, -2).apply();
                }
                updateTrackControls(player);
                if (persist) Toast.makeText(this, "Loaded " + name, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void loadExternalAudio(Uri uri, VideoItem video, boolean persist) {
        MediaPlayer player = mediaPlayer;
        if (player == null) return;
        int generation = ++audioLoadGeneration;
        io.execute(() -> {
            File audioFile = null;
            String name = externalTrackDisplayName(uri, "External audio track.m4a");
            try {
                audioFile = copyExternalTrack(uri, "audio-track-", name, ".m4a", 1024L * 1024 * 1024);
            } catch (IOException | RuntimeException error) {
                if (audioFile != null) audioFile.delete();
                runOnUiThread(() -> {
                    if (currentVideo == video && generation == audioLoadGeneration) {
                        Toast.makeText(this, "Could not read audio track", Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            File imported = audioFile;
            runOnUiThread(() -> {
                if (currentVideo != video || mediaPlayer != player || playerRoot == null
                        || generation != audioLoadGeneration) {
                    imported.delete();
                    return;
                }
                boolean added = player.addSlave(IMedia.Slave.Type.Audio, Uri.fromFile(imported), true);
                if (!added) {
                    imported.delete();
                    Toast.makeText(this, "This audio track could not be loaded", Toast.LENGTH_LONG).show();
                    return;
                }
                importedTrackFiles.add(imported);
                player.setAudioDelay(preferences.getInt("audioDelayMs:" + video.uri, 0) * 1000L);
                if (persist) {
                    preferences.edit().putString("audioFileUri:" + video.uri, uri.toString())
                            .putString("audioFileName:" + video.uri, name)
                            .putInt("vlcAudioTrack:" + video.uri, -2).apply();
                }
                updateTrackControls(player);
                if (persist) Toast.makeText(this, "Loaded " + name, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private File copyExternalTrack(Uri uri, String prefix, String name, String fallbackSuffix,
                                   long maximumBytes) throws IOException {
        String lowerName = name.toLowerCase(Locale.ROOT);
        int dot = lowerName.lastIndexOf('.');
        String suffix = dot >= 0 ? lowerName.substring(dot) : fallbackSuffix;
        if (!suffix.matches("\\.[a-z0-9]{1,8}")) suffix = fallbackSuffix;
        File file = File.createTempFile(prefix, suffix, getCacheDir());
        boolean complete = false;
        try (InputStream source = getContentResolver().openInputStream(uri);
             FileOutputStream destination = new FileOutputStream(file)) {
            if (source == null) throw new IOException("Cannot open selected track");
            byte[] buffer = new byte[32 * 1024];
            int bytes;
            long total = 0;
            while ((bytes = source.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                total += bytes;
                if (total > maximumBytes) throw new IOException("Selected track is too large");
                destination.write(buffer, 0, bytes);
            }
            if (total == 0) throw new IOException("Selected track is empty");
            complete = true;
            return file;
        } finally {
            if (!complete) file.delete();
        }
    }

    private String externalTrackDisplayName(Uri uri, String fallback) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) {}
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        return name == null ? fallback : name;
    }

    private void applyImmersiveMode() {
        if (playerRoot == null) return;
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void setControlsVisible(boolean visible) {
        visible = visible && !touchLocked;
        controlsVisible = visible;
        if (controlsTop != null) controlsTop.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (controlsBottom != null) controlsBottom.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (touchLockOverlay != null) touchLockOverlay.setVisibility(touchLocked ? View.VISIBLE : View.GONE);
        if (lockButton != null) lockButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setTouchLocked(boolean locked) {
        touchLocked = locked;
        seekQueue.cancelPreviews();
        if (scrubPreviewActive) seekTo(displayedSeekPositionMs);
        activeGesture = GESTURE_NONE;
        seeking = false;
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(hideAdjustmentHud);
        if (adjustmentHud != null) adjustmentHud.setVisibility(View.GONE);
        setControlsVisible(!locked);
        if (touchLockOverlay != null) {
            touchLockOverlay.bringToFront();
            if (locked) unlockButton.requestFocus();
        }
        applyImmersiveMode();
        if (!locked) scheduleHideControls();
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideControls);
        if (playWhenReady && !seeking && !touchLocked) handler.postDelayed(hideControls, 3200);
    }

    private int safePosition() {
        int committedTarget = seekQueue.committedTarget();
        if (committedTarget >= 0) return committedTarget;
        if (mediaPlayer == null || !mediaPrepared) return Math.max(0, savedPositionMs);
        try { return Math.max(0, (int) Math.min(Integer.MAX_VALUE, mediaPlayer.getTime())); }
        catch (RuntimeException ignored) { return 0; }
    }

    private void updatePlaybackIcon() {
        if (playPauseButton == null) return;
        boolean playing = scrubPreviewActive ? playWhenReady
                : mediaPlayer != null && mediaPrepared && mediaPlayer.isPlaying();
        playPauseButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseButton.setContentDescription(playing ? "Pause" : "Play");
    }

    private void togglePlayback() {
        if (mediaPlayer == null || !mediaPrepared) {
            playWhenReady = !playWhenReady;
            updatePlaybackIcon();
            return;
        }
        if (mediaPlayer.isPlaying()) {
            savedPositionMs = safePosition();
            mediaPlayer.pause();
            playWhenReady = false;
        } else {
            mediaPlayer.play();
            playWhenReady = true;
        }
        updatePlaybackIcon();
        scheduleHideControls();
    }

    private void seekBy(int deltaMs) {
        if (mediaPlayer == null || !mediaPrepared) return;
        int position = safePosition();
        int end = durationMs > 0 ? durationMs : Integer.MAX_VALUE;
        savedPositionMs = Math.max(0, Math.min(end, position + deltaMs));
        seekTo(savedPositionMs);
        setControlsVisible(true);
        scheduleHideControls();
    }

    private void cycleSpeed() {
        float[] speeds = new float[] { 1.0f, 1.25f, 1.5f, 2.0f, 0.75f };
        speedIndex = (speedIndex + 1) % speeds.length;
        speed = speeds[speedIndex];
        updateSpeedButton();
        if (mediaPlayer != null && mediaPrepared) {
            try { mediaPlayer.setRate(speed); }
            catch (RuntimeException ignored) {}
        }
        scheduleHideControls();
    }

    private void updateSpeedButton() {
        if (speedButton != null) speedButton.setText(String.format(Locale.US, "%.2gx", speed));
    }

    private void toggleOrientation() {
        orientationFlipped = !orientationFlipped;
        applyVideoOrientation();
        scheduleHideControls();
    }

    private int viewWidth(View view) { return view == null ? 0 : view.getWidth(); }
    private int viewHeight(View view) { return view == null ? 0 : view.getHeight(); }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private float currentBrightness() {
        float brightness = getWindow().getAttributes().screenBrightness;
        if (brightness >= 0f) return brightness;
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
        } catch (RuntimeException ignored) {
            return 0.5f;
        }
    }

    private void setWindowBrightness(float brightness) {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = clamp(brightness, 0.03f, 1.0f);
        getWindow().setAttributes(attributes);
        brightnessChanged = true;
    }

    private void restoreScreenBrightness() {
        if (!brightnessChanged) return;
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = originalScreenBrightness;
        getWindow().setAttributes(attributes);
        brightnessChanged = false;
    }

    private void showAdjustmentHud(String message) {
        if (adjustmentHud == null || touchLocked) return;
        adjustmentHud.setText(message);
        adjustmentHud.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideAdjustmentHud);
        handler.postDelayed(hideAdjustmentHud, 900);
    }

    private void showSeekHud(int positionMs) {
        displayedSeekPositionMs = clamp(positionMs, 0, durationMs);
        if (adjustmentHud == null || touchLocked) return;
        adjustmentHud.setText(formatTime(displayedSeekPositionMs) + " / " + formatTime(durationMs));
        adjustmentHud.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideAdjustmentHud);
    }

    private void finishScrubPreview() {
        scrubPreviewActive = false;
        if (seekFrameMonitor != null) seekFrameMonitor.close();
        seekFrameMonitor = null;
        seekQueue.endGesture();
        updatePlaybackIcon();
        handler.removeCallbacks(hideAdjustmentHud);
        handler.postDelayed(hideAdjustmentHud, 900);
        scheduleHideControls();
    }

    private void finishPlayerGesture(boolean commitSeek) {
        if (activeGesture == GESTURE_SEEK) {
            // Commit precisely once; a cancelled drag returns to its original position.
            seekTo(commitSeek ? gestureTargetPosition : gestureStartPosition);
            seeking = false;
            scheduleHideControls();
        } else if (activeGesture == GESTURE_VOLUME) {
            preferences.edit().putInt("audioBoostPercent", audioBoostPercent).apply();
        }
        activeGesture = GESTURE_NONE;
    }

    private void seekTo(int positionMs) {
        if (mediaPlayer == null || !mediaPrepared || !mediaPlayer.isSeekable()) return;
        savedPositionMs = clamp(positionMs, 0, durationMs > 0 ? durationMs : Integer.MAX_VALUE);
        seekQueue.requestCommit(savedPositionMs);
        scheduleSeekRequests();
    }

    private void scheduleSeekRequests() {
        if (seekQueueScheduled) return;
        seekQueueScheduled = true;
        handler.post(processSeekRequests);
    }

    private int displayedVideoFrames(MediaPlayer player) {
        IMedia media = player.getMedia();
        if (media == null) return -1;
        try {
            IMedia.Stats stats = media.getStats();
            return stats == null ? -1 : stats.displayedPictures;
        } finally {
            media.release();
        }
    }

    private void reopenAfterStalledSeek(int positionMs) {
        if (currentVideo == null || playerRoot == null) return;
        float previousSpeed = speed;
        int previousSpeedIndex = speedIndex;
        boolean shouldPlay = playWhenReady;
        boolean showControls = controlsVisible;
        openVideo(currentVideo, positionMs);
        speed = previousSpeed;
        speedIndex = previousSpeedIndex;
        playWhenReady = shouldPlay;
        updateSpeedButton();
        setControlsVisible(showControls);
    }

    private VideoItem adjacentVideo(int direction) {
        if (currentVideo == null) return null;
        ArrayList<VideoItem> folderVideos = new ArrayList<>();
        for (VideoItem video : videos) if (currentVideo.folder.equals(video.folder)) folderVideos.add(video);
        Collections.sort(folderVideos, videoComparator());
        for (int i = 0; i < folderVideos.size(); i++) {
            if (!folderVideos.get(i).uri.equals(currentVideo.uri)) continue;
            int next = i + direction;
            return next >= 0 && next < folderVideos.size() ? folderVideos.get(next) : null;
        }
        return null;
    }

    private void playAdjacent(int direction) {
        VideoItem next = adjacentVideo(direction);
        if (next != null) openVideo(next, 0);
    }

    private String formatTime(int millis) {
        int seconds = Math.max(0, millis / 1000);
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int rest = seconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest);
        return String.format(Locale.US, "%d:%02d", minutes, rest);
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "Size unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
