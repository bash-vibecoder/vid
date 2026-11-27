# Changelog

## 1.4.12 — 2026-11-27

### Changed

- Use a persistent FFmpeg scrub decoder and cached frames during horizontal drags, avoiding repeated resets in the main playback decoder in SW, HW, and HW+ modes.
- Keep video and audio playing underneath the previews. Audio follows the original playback timeline during a drag; one precise seek on release moves video and audio to the chosen position, then full quality video replaces the preview.
- Show the decoded preview's timestamp in the seek indicator. Preview frames use at most 384 pixels per edge and a separate 96 MB memory cache. Uncached positions and demanding files can still take time to decode.
- Isolate scrub decoding in its own private process with deadlines and a watchdog. Preserve the subtitle list's Off-first behavior from 1.4.11.

### Tests

- Check immediate seeking before cache warmup, forward and backward drags in all three decoder modes, actual picture updates and their longest gaps, picture/timer alignment, and the transition back to full quality video after release.
- Verify that dragging keeps native playback advancing without repeated seeks, pauses, or changes to audio volume.

### Validation

- All 37 unit tests and 21 device checks passed; lint reported no errors.
- The 720p fixture produced 33–44 distinct pictures across 48 drag samples in both directions across SW, HW, and HW+, with longest measured update gaps of 58–95 milliseconds. An immediate drag before cache warmup produced 41 distinct pictures with a 70-millisecond longest gap. These measurements do not guarantee the same performance for other files.
- Signed APK verification, 16 KB zip alignment, and offline permission checks passed. Installed the production package as 1.4.12 (18) and verified that its APK matches the file in `dist`.

## 1.4.11 — 2026-11-27

### Fixed

- Open the subtitle list at Off even when an active track is far down a long list. Set its checked radio separately so Android cannot scroll the dialog to that track while opening it.
- Check the displayed video surface during swipe seeking instead of waiting for VLC's 250-millisecond statistics updates. Keep only the newest pending target and wait for pictures to continue at the requested time before sending another seek.
- Sample the playback clock with each picture check and process seek requests every 16 milliseconds. Playback and audio volume remain active throughout the gesture.

### Tests

- Exercise sixteen embedded subtitle tracks, select the last one, reopen the menu three times, and verify that Off stays visible and disables the active subtitles.
- Measure rendered seek updates and their longest gap in SW and HW+, compare the picture's embedded timestamp with the timer, and verify that native playback and volume remain enabled.

### Validation

- All 37 unit tests and 21 device checks passed; lint reported no errors.
- The continuous seek fixture produced 12 visible timer updates in SW and 9 in HW+, with longest gaps of 238 and 295 milliseconds. The earlier SW run produced 7 updates with a 438-millisecond gap and failed picture/timer alignment.

## 1.4.10 — 2026-11-27

### Changed

- Center Previous, Play/Pause, and Next inside their full-width rectangles. Keep the separate lock in its bottom-left corner.
- Keep video and audio playing during live scrubbing, with accurate decoded frames and no automatic pause or mute.
- Update the seek timer when a new picture is rendered, keeping it aligned with the displayed frame.
- Reduce the render wait from 350 to 64 milliseconds and poll at 24-millisecond intervals while seeking. Reuse an accurate preview on release and discard duplicate seek requests.

### Tests

- Add a machine-readable clock to the seek fixture and compare its decoded picture time with the on-screen timer in software and HW+ modes.
- Check centered icon geometry, unchanged playback intent and audio volume during scrubbing, quick frame acknowledgement, and release without redundant decoding.

## 1.4.9 — 2026-11-27

### Fixed

- Extend the transport rectangles across nearly the entire bottom bar beside the reserved lock corner, with small gaps and icons positioned toward the center.
- Wait for rendered video frames before sending the next swipe preview, keeping only the latest target and committing the released position once.
- Suspend preview retries when a decoder falls behind. If a committed seek stops producing pictures, reopen once at the requested position with the play state, speed, and controls preserved.
- Keep the displayed seek position stable while the decoder catches up.

### Tests

- Add seek queue tests for continuous input, final seek ordering, decoder stalls, paused playback, and cancellation.
- Add continuous forward-swipe checks using a 720p H.264 video with ten-second keyframe intervals in software and HW+ modes. The checks compare actual decoded pictures during the swipe and after release.

### Validation

- Twenty-nine unit tests and eighteen on-device checks passed. Continuous forward seeking rendered moving pictures during the drag and after release in software and HW+, without triggering decoder recovery. Lint reported no errors.
- Signed APK, 16 KB alignment, and offline permission checks passed.

## 1.4.8 — 2026-11-27

### Fixed

- Show the bottom-left lock only while the playback controls overlay is visible; keep unlock available while locked.
- Center a wider transport group with larger icons and taller blue outlined rectangles, separated by small gaps.
- Preview video while swipe seeking, with throttled fast seeks during the drag and one precise seek on release.
- Extend the volume swipe to 200%, using player audio gain above the Android stream's 100% limit in SW, HW, and HW+ modes.
- Keep Off first in the subtitle list while marking the actual active track, and verify that selecting Off stops subtitles.
- Show transparent audio/subtitle panels at the right edge without dimming the video, including audio boost and sync controls.
- Replace the audio sync slider with a signed editable seconds value and −/+ buttons for 0.1-second steps. Typed values retain millisecond precision.

### Validation

- Nineteen unit tests and seventeen on-device checks passed, covering live swipe seeking, native 200% gain in HW+, subtitle selection and Off, transparent right-side panels, typed audio offsets and reset, enlarged transport controls, touch lock, and background recovery. Lint reported no errors.
- Signed APK verification, 16 KB alignment, and the offline permission check passed.

## 1.4.7 — 2026-11-26

### Added

- Add HW+ alongside software and hardware decoder modes.
- Add external audio track loading, per-video audio sync correction, and 100–200% audio boost.
- Open the subtitle selection list with Off selected every time.

### Changed

- Keep the lock/unlock control fixed at the bottom-left corner, separate from the transport buttons.
- Spread Previous, Play/Pause, and Next across the bottom bar with rectangular buttons and thin blue borders.

## 1.4.6 — 2026-11-25

### Added

- Add persistent software/hardware video decoding selection to the player. Changing modes reopens the current video at its current position and restores its play/pause state and speed.
- Move playback touch lock into the bottom bar and center Previous, Play/Pause, Next, and Lock with moderate spacing.
- Accelerate horizontal seeking during a continued drag: the first screen fifth seeks five seconds, and each additional fifth seeks ten seconds.

### Validation

- Unit tests cover the initial seek rate, continued-drag acceleration, reverse seeking, and invalid screen widths.
- Updated device smoke checks to locate and operate the lock control in the bottom bar.

## 1.4.5 — 2026-11-17

### Changed

- Only the most recently played folder is orange on the home screen. Playing a different folder resets the previous folder's title to white.
- Keep individual resume positions and the orange last played video inside each folder.
- Remember the latest folder independently from resume position so completing a video keeps the correct folder highlighted.

### Validation

- Eight unit tests and eleven device smoke checks passed, including changing the latest folder, resetting reused row colors, keeping individual resume positions, and retaining the latest folder highlight after completion. Lint reported no errors.

## 1.4.4 — 2026-11-17

### Added

- Orange titles mark each folder's last played video and the folder itself. Individual positions and highlights remain saved when playing a different folder, including brief playback sessions. Completed videos retain their last played highlight.
- A playback touch lock blocks accidental taps, double taps, swipes, and Back. A small lock at the top left unlocks the controls.
- Touch lock persists across background recovery, activity state restoration, and automatic video changes.

### Changed

- Refresh resume targets and their colors immediately when returning to the library, including videos with unknown duration.
- Reset title colors when list rows are reused, and track last played highlights independently from resume positions.
- Arrange top playback controls in two rows on narrow screens to keep the lock and track buttons accessible.
- Extend the device smoke test to cover touch lock, background lock recovery, and resume highlighting with reused rows.

### Validation

- Eight unit tests and eleven device smoke checks passed, including touch lock, background recovery, preview recovery, and independent folder/video highlights. Lint reported no errors.

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
