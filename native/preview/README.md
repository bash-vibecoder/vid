# Native previews

`preview.c` and `scrub.c` are APocketVid's JNI bridges to the unmodified FFmpeg libraries distributed in FFmpegMediaMetadataRetriever-native 1.0.23. Thumbnail extraction runs in the private `:previews` service and persistent scrub decoding in the separate private `:scrub` service. The upstream retriever JNI and Java bindings are excluded from the APK.

The thumbnail bridge reads a granted file descriptor with `pread64`, accounts for asset offsets/lengths, and implements seek and size callbacks for FFmpeg. No filenames or network URLs enter FFmpeg. It selects a software video decoder, decodes a real frame before configuring swscale, scales to at most 320 pixels per edge, and returns RGBA pixels plus rotation metadata. Java creates the bitmap and JPEG. Allocations and FFmpeg contexts are released on every return path. Input work has a cooperative deadline, with a separate process watchdog for a stuck native decoder.

The scrub bridge holds a software decoder open for the current video and caches RGB565 frames at up to 384 pixels per edge. It handles rotation and sample aspect ratio, uses at most 96 MB and 1,024 frame slots, and prefetches around the playback position. Short decode pumps coalesce targets and draw directly through `ANativeWindow` to a TextureView, avoiding bitmap IPC and repeated seeks in the playback decoder. The nearest cached picture stays available while decoding catches up. Audio and underlying playback continue normally during the drag; one precise VLC seek commits the released position, then the preview hides when full quality playback catches up. Cooperative deadlines and a heartbeat watchdog contain stuck work in `:scrub`.

The small ARM64 shared library is included under `app/src/main/jniLibs/arm64-v8a/`. Rebuild it with:

```sh
./scripts/build-preview-native.sh
```

The script verifies the pinned upstream source/archive checksums, extracts the matching FFmpeg headers/libraries, and uses an Android NDK supplied through `ANDROID_NDK_HOME`. In this ARM64 Ubuntu chroot it can instead use Ubuntu clang with the existing Termux Android headers and Android Bionic libraries. It targets API 26 and produces ELF segments aligned to 16 KB. The source, wrapper build, unit tests, lint, and device smoke test should be checked after native changes.

The startup smoke test uses local test-only H.264 fixtures to verify preview generation, process failure containment, persistent cache reuse, decoder recovery, playback, and video surface recovery. A timestamped fixture also checks immediate and cached scrub frames, both drag directions in all three playback decoder modes, uninterrupted underlying playback, timer accuracy, and the handoff after release. Its packages are separate from the installed player.
