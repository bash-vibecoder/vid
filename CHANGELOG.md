# Changelog

## 1.4.3 — 2026-11-17

### Fixed

- Replaced the legacy launcher icon with an adaptive icon to remove the launcher-added white backing.
- Used a solid black background and blue V for adaptive, round, and legacy launcher icons.
- Reduced the V to 75% of its previous size while keeping it centered.

### Changed

- Removed personal device model references from the documentation and kept the build environment described as an Ubuntu ARM64 chroot.

## 1.4.2 — 2026-11-16

### Changed

- Reduced the bottom playback bar from 122 dp to 80 dp, placing its buttons closer to the bottom edge.
- Added two translucent blue pill dividers between Previous, Play/Pause, and Next.
- Kept 48 dp touch targets and wide spacing for all three playback buttons.
- Added the public feature guide, release history, download links, and Gradle wrapper.

### Included fixes

- Includes the startup crash protection, persistent previews, video-output recovery, and five-second seek swipe sensitivity from 1.4.1.

## 1.4.1 — 2026-11-16

### Fixed

- Fixed the startup crash caused by the native thumbnail retriever.
- Replaced the retriever interface with a local FFmpeg bridge that supports seeking through granted file descriptors and configures scaling after decoding a valid frame.
- Moved native preview extraction into a private process, with timeouts, remembered failures, and a limit on repeated worker crashes.
- Restored visible video when returning from the background by reopening the saved position with a fresh video output.

### Changed

- Moved Previous, Play/Pause, and Next into a bottom playback bar.
- Increased horizontal seek sensitivity so a fifth of the screen width moves about five seconds.
- Seeking shows the seek indicator without revealing the full playback controls.

### Validation

- Eight unit tests passed, with no lint errors.
- Device smoke tests passed for launch, preview generation, persisted previews, worker failure/recovery, software playback, visible frames, and background video recovery.
- The user confirmed the update worked on their phone.

## 1.4 — 2026-11-16

### Added

- Persistent JPEG previews with visible-row priority, background generation, and capture from playback when extraction fails.
- An orange circular button to resume the last video.
- Translucent playback controls with widely spaced transport buttons.

### Changed

- Prepared the playback engine in the background and reused it between videos.
- Moved local file access and player shutdown off the UI thread, reduced local buffering, and removed redundant resume seeking.

### Known issue in this version

- Native thumbnail extraction could crash the app at startup; fixed in 1.4.1. Use the latest release.

## Earlier development

Established the offline Android player, folder library and sorting, saved positions and per-folder resume, embedded audio/subtitle selection, local subtitle loading, brightness/volume/seek gestures, orientation handling, bundled ARM64 software codecs, and APocketVid branding.
