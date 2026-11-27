# APocketVid features

APocketVid plays videos stored on your Android device. It is fully offline and has no streaming, advertisements, account system, or analytics. The APK does not request Internet access.

## Library and resume

- The home screen shows folders instead of combining everything into an All Videos view.
- Sort videos by name, newest, oldest, duration, or size, and search the library.
- Video previews are saved in app storage and reused on later launches, with a 96 MB limit.
- A folder's Resume button continues that folder's saved video.
- The orange circular play button resumes the most recently played video.
- Only the latest played folder is orange on the home screen. Playing a different folder turns the previous folder white. Inside each folder, its own last played video stays orange and keeps its saved resume position. Brief playback sessions are remembered, and last played highlights remain after completion.
- Playback positions and track choices are remembered per video.

## Player controls

The dark blue interface places Previous, Play/Pause, and Next inside tall rectangles that fill nearly the entire bottom bar beside the lock corner. Thin blue borders and small gaps separate the rectangles, with each playback icon centered inside its rectangle. Lock stays separately anchored at the bottom-left corner and appears with the playback controls overlay. Previous/Next follow the folder and current sorting order.

The launcher icon uses a solid black background with a blue V. Adaptive and round launcher shapes use the same black background.

| Action | Control |
| --- | --- |
| Show or hide playback controls | Tap the video once |
| Seek backward or forward | Swipe horizontally; the first fifth of the screen moves five seconds, then a continued swipe moves ten seconds per fifth |
| Seek without opening the full interface | Swipe through cached video previews; the timer follows the displayed frame and playback seeks on release |
| Skip ten seconds | Double-tap the left or right side |
| Play or pause | Use the bottom center button, or double-tap the center of the video |
| Adjust brightness | Swipe vertically on the left |
| Adjust volume | Swipe vertically on the right, up to 200% with audio boost |
| Change playback speed | Tap the speed control |
| Select an embedded or external audio track | Tap Audio |
| Correct audio sync | Audio > Audio sync correction |
| Boost audio volume | Audio > Audio boost |
| Select or load subtitles | Tap CC |
| Change orientation | Use the orientation button |
| Lock playback touches | Tap the bottom-left lock |
| Unlock playback touches | Tap the bottom-left lock while locked |

Portrait and landscape videos are detected automatically. Controls stay visible when paused. When the app leaves the foreground, it saves the current position; returning recreates the video output and restores playback state.

While seeking, previews use a lower resolution of at most 384 pixels per edge. Audio continues from the original playback timeline during the drag; releasing seeks video and audio to the chosen position and restores full quality video. The player does not automatically pause or mute. Moving beyond the cached frames or seeking through demanding files can still take time.

Touch lock hides the playback controls and prevents taps, double taps, swipes, or Back from operating them. Only the bottom-left unlock button accepts player touches while locked. The lock stays in that corner, remains active after background recovery and when the player advances to another video, and clears when returning to the library.

## Audio, video, and subtitles

LibVLC 3.7.7 supplies bundled software audio/video codecs. Choose SW, HW, or HW+ video decoding with the decoder control. Hardware mode availability depends on the device and codec. Encoded audio passthrough remains disabled. Android supplies display surfaces and decoded PCM audio output; no separate codec pack is required.

Common supported codecs include H.264, HEVC, VP9, AV1, AAC, AC-3/E-AC-3, DTS, MP3, FLAC, and Opus. Actual support depends on the container, profile, and condition of the file. Software decoding can use substantial CPU on demanding videos.

Select embedded subtitle tracks or load a subtitle file from local storage. Audio and subtitle panels have transparent backgrounds, sit at the right edge, and do not dim the video. The subtitle list always opens at the top with Off first, including lists with many tracks; the radio selection reflects the actual active track. Choosing Off disables subtitles. Supported formats include SRT, ASS/SSA, and WebVTT. Opening the subtitle picker keeps the current video running. The subtitle choice is remembered when the storage provider allows continued access.

Load a separate audio track from the document picker. External audio and its storage permission are remembered per video. Audio sync correction accepts typed offsets from -10 to +10 seconds with millisecond precision, plus −/+ buttons for 0.1-second steps. Changes preview immediately; Apply saves them, Cancel restores the previous value, and Reset sets zero. Audio boost ranges from 100% to 200% through the Audio menu or by continuing a right-side volume swipe above 100%. Boost works with SW, HW, and HW+ video decoding.

## Startup and previews

The playback engine initializes in the background and is reused between videos. File access and native player shutdown run away from the UI thread.

Thumbnail extraction uses a separate private process with bundled FFmpeg libraries. It reads local file descriptors with seek support, scales decoded frames, and saves successful JPEGs. A failed preview is skipped, and repeated worker crashes stop additional native attempts for the session. The player can also save a preview from a decoded playback frame.

## Compatibility and permissions

- Android 8 or later, with ARM64 support.
- Video access is requested on first launch.
- A first-run battery optimization prompt provides the requested battery setting.
- No Internet permission.

Download APKs from [GitHub Releases](https://github.com/bash-vibecoder/vid/releases/latest). Version history is in [CHANGELOG.md](CHANGELOG.md).
