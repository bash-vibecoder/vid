# APocketVid

A local video player for Android with a dark blue interface. The home screen shows folders, with sorting, saved previews, per-folder resume, and an orange circular button to resume the last video.

## Download

[Download the latest APK](https://github.com/bash-vibecoder/vid/releases/latest) for Android 8 or later on ARM64 devices. Install it over an existing APocketVid build to keep saved positions and settings. Release APKs use the same signing certificate as the earlier project builds.

The current version is **1.4.2**. See the [changelog](CHANGELOG.md) for release changes and the [feature guide](FEATURES.md) for controls and supported behavior.

## Features

- Fully offline local playback, with bundled software video and audio codecs.
- A folder home screen, sorting, search, and persistent video previews.
- Saved playback positions, per-folder Resume, and a floating orange button for the last video.
- A dark blue theme with a slim bottom playback bar and thin blue glass dividers.
- Brightness, volume, and seek gestures, plus playback speed controls.
- Embedded audio/subtitle selection and local subtitle files.
- Portrait/landscape detection and recovery when returning from the background.
- No streaming, advertising, accounts, analytics, or Internet permission.

## Playback

Version 1.4.2 bundles **LibVLC Android 3.7.7**, including its FFmpeg software audio/video decoders. Playback explicitly disables MediaCodec/OMX hardware decoding, FFmpeg hardware acceleration, and encoded audio passthrough. Android provides the display surface and decoded PCM audio output. No codec pack or separate player needs to be installed.

The player initializes its engine in the background and reuses it between videos. File access and native player shutdown also run away from the UI thread. Local buffering is 75 ms; resuming uses a single initial seek. These changes remove avoidable work from opening a video, but file indexing, storage speed, and software decoding can still affect startup time. Playback startup exceptions are recorded in Android logs under the `APocketVid` tag.

The bundled engine supports common formats such as H.264, HEVC, VP9, AV1, AAC, AC-3/E-AC-3, DTS, MP3, FLAC, and Opus, subject to the exact file/profile. Damaged, encrypted, or unusual files can still fail. Software decoding uses more CPU and battery than hardware decoding; very demanding video may drop frames on a phone.

This build packages **ARM64 (`arm64-v8a`) only**, for the Poco X3 Pro/vayu and other 64-bit ARM devices. It supports Android 8 (API 26) and later and targets API 35. The library requires SDK 36 at build time. The app accepts local files, has no Internet permission, and has no streaming, advertising, account, or analytics features.

Previews use bundled FFmpeg software decoding through a small native bridge included in this project. It opens granted file descriptors with seek support, and creates its scaler only after decoding a frame with a known pixel format. Thumbnail extraction runs in a private `:previews` process, separate from the library UI and playback engine. A native decoder crash cannot directly abort the player. A timed-out extraction is stopped, failed videos are skipped for 24 hours, and three worker crashes stop further native preview attempts for that session.

Visible rows have priority, and the rest of the library is processed gradually. Successful previews are saved as JPEGs in app storage and reused after restarting the app. A change in a video's URI, size, modified time, or duration invalidates its preview. The cache retains up to 96 MB of previews, evicting the oldest when full. Background preview scheduling pauses during playback and while the app is hidden. If extraction fails for a playable video, the app can save a frame from the actual player. Corrupt or unsupported files may still lack a preview.

When the screen locks or the player enters the background, APocketVid saves the position and releases the native video output. On return it reuses the initialized engine, reopens the video at that position, and restores its play/pause state, speed, and saved tracks. Selecting a subtitle file keeps playback running.

- Tap a video to start it. Use a folder's Resume button or the floating resume button to continue from its saved position.
- Previous, Play/Pause, and Next appear widely spaced in a slim glass control bar attached to the bottom edge, below the seek bar. Thin blue pill dividers separate the buttons, which retain 48 dp touch targets. The player keeps the controls visible when paused. Previous/Next follow the selected folder and sort order.
- Swipe horizontally to seek. A fifth of the screen width is about five seconds, independent of video length. Swiping shows only the seek indicator, without revealing the control bars. Double-tap the left/right sides to skip ten seconds.
- Swipe vertically on the left for brightness and on the right for volume. Playback speed, audio tracks, subtitle tracks, and orientation controls are in the player.
- Use **CC** to select embedded subtitles or load a local subtitle file. The picker keeps the current video running. VLC handles formats such as SRT, ASS/SSA, and WebVTT. Subtitle file access and the choice are remembered per video when the storage provider allows it.
- Video access is requested on first launch. A separate first-run prompt offers the requested battery optimization setting.

## Build in this ARM64 Ubuntu chroot

The required tools have been installed in `.local/`: JDK 17 is provided by Ubuntu, Gradle 8.11.1, Android SDK platform 36/build tools 35.0.0, and ARM64-native `aapt2`/`zipalign`. `adb` is installed through Ubuntu's package manager.

```sh
./scripts/build.sh
```

The script resolves the pinned libraries and their dependencies, builds and signs the APK, checks the signature and 16 KB zip alignment, and writes `dist/APocketVid-v1.4.2.apk` with a SHA-256 file. The existing development keystore is reused so this APK can update the previous project builds. Keep `.local/pocket-video-debug.jks` if you want future builds to install as updates. Signing keys, local SDKs, build output, and credentials are excluded from this repository.

After dependencies have been cached, an offline rebuild is available:

```sh
./scripts/build.sh --offline
```

For other Gradle tasks in this chroot, use `./scripts/gradle-chroot.sh :app:lintDebug`, for example. Official Linux SDK native executables are x86-64, so the wrapper sets an ARM64 `aapt2` override and `ANDROID_DATA`; the Java SDK tools work normally. The ARM64 native tools come from [lzhiyong/android-sdk-tools, release 35.0.2](https://github.com/lzhiyong/android-sdk-tools/releases/tag/35.0.2). Build logs are in `build/`.

Run the playback startup and persistent preview cache regression tests, plus lint, during a build with `./scripts/build.sh :app:testDebugUnitTest :app:lintDebug`.

On another machine, install JDK 17, SDK platform 36, and build tools 35.0.0, then set `ANDROID_HOME` and use the included Gradle wrapper:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Alternatively, run `GRADLE=./gradlew ./scripts/build.sh` to package the signed APK and checksum under `dist/`, or open the project in a compatible Android Studio. Gradle downloads its pinned distribution, LibVLC, and AndroidX/Kotlin dependencies on the first build. A fresh clone uses a new local development signing key; it cannot update an official release unless built with the original private key.

The included device smoke test runs only against a temporary `com.apocketvid.validation` package. It checks launch, actual FFmpeg preview extraction, saved JPEG reuse, main-process survival when the preview process is killed, worker recovery, software playback, visible decoded frames, and visible video after moving the app to the background and returning. These checks passed on the vayu phone. This is a short fixture test; physical screen-lock behavior, audio/subtitle combinations, long playback, and demanding files still benefit from manual testing.

Build the temporary device test APKs with:

```sh
./scripts/gradle-chroot.sh -PvalidationBuild :app:assembleDebug :app:assembleDebugAndroidTest --offline
```

Install both APKs on a test device, then run `am instrument -w com.apocketvid.validation.test/com.apocketvid.player.StartupSmokeInstrumentation`. When `files/validation-phase.txt` reports `READY_TO_TERMINATE_PREVIEW_PROCESS`, terminate only `com.apocketvid.validation:previews` so the fault-recovery check can continue. Remove the temporary packages afterward. Build the production APK again without `-PvalidationBuild` before distributing it.

The preview JNI bridge is shipped in `app/src/main/jniLibs/arm64-v8a/` so routine Java builds do not need an NDK. Its source and rebuild instructions are in [native/preview/README.md](native/preview/README.md).

Third-party license information and source locations are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); license texts are also packaged under `assets/licenses/` in the APK.
