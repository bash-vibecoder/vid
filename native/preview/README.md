# Native previews

`preview.c` is APocketVid's JNI bridge to the unmodified FFmpeg libraries distributed in FFmpegMediaMetadataRetriever-native 1.0.23. It runs only inside the private preview service. The upstream retriever JNI and Java bindings are excluded from the APK.

The bridge reads a granted file descriptor with `pread64`, accounts for asset offsets/lengths, and implements seek and size callbacks for FFmpeg. No filenames or network URLs enter FFmpeg. It selects a software video decoder, decodes a real frame before configuring swscale, scales to at most 320 pixels per edge, and returns RGBA pixels plus rotation metadata. Java creates the bitmap and JPEG. Allocations and FFmpeg contexts are released on every return path. Input work has a cooperative deadline, with a separate process watchdog for a stuck native decoder.

The small ARM64 shared library is included under `app/src/main/jniLibs/arm64-v8a/`. Rebuild it with:

```sh
./scripts/build-preview-native.sh
```

The script verifies the pinned upstream source/archive checksums, extracts the matching FFmpeg headers/libraries, and uses an Android NDK supplied through `ANDROID_NDK_HOME`. In this ARM64 Ubuntu chroot it can instead use Ubuntu clang with the existing Termux Android headers and Android Bionic libraries. It targets API 26 and produces ELF segments aligned to 16 KB. The source, wrapper build, unit tests, lint, and device smoke test should be checked after native changes.

The startup smoke test uses a local test-only H.264 fixture to verify preview generation, process failure containment, persistent cache reuse, decoder recovery, playback, and video surface recovery. Its packages are separate from the installed player.
