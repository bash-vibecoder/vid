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

The dark blue interface places Previous, Play/Pause, and Next in a slim glass bar along the bottom edge. Two thin blue pill dividers separate the buttons. Each button has a minimum 48 dp touch target, and Previous/Next follow the folder and current sorting order.

The launcher icon uses a solid black background with a blue V. Adaptive and round launcher shapes use the same black background.

| Action | Control |
| --- | --- |
| Show or hide playback controls | Tap the video once |
| Seek backward or forward | Swipe horizontally; about one fifth of the screen width moves five seconds |
| Seek without opening the full interface | Swipe; only the seek indicator appears |
| Skip ten seconds | Double-tap the left or right side |
| Play or pause | Use the bottom center button, or double-tap the center of the video |
| Adjust brightness | Swipe vertically on the left |
| Adjust volume | Swipe vertically on the right |
| Change playback speed | Tap the speed control |
| Select an embedded audio track | Tap Audio |
| Select or load subtitles | Tap CC |
| Change orientation | Use the orientation button |
| Lock playback touches | Tap the lock in the top bar |
| Unlock playback touches | Tap the small lock at the top left |

Portrait and landscape videos are detected automatically. Controls stay visible when paused. When the app leaves the foreground, it saves the current position; returning recreates the video output and restores playback state.

Touch lock hides the playback controls and prevents taps, double taps, swipes, or Back from operating them. Only the top-left unlock button accepts player touches while locked. The lock stays active after background recovery and when the player advances to another video. Returning to the library clears it. The top controls use two rows on narrow screens so all buttons remain accessible.

## Audio, video, and subtitles

LibVLC 3.7.7 supplies bundled software audio/video codecs. Hardware decoding and encoded audio passthrough are disabled. Android supplies display surfaces and decoded PCM audio output; no separate codec pack is required.

Common supported codecs include H.264, HEVC, VP9, AV1, AAC, AC-3/E-AC-3, DTS, MP3, FLAC, and Opus. Actual support depends on the container, profile, and condition of the file. Software decoding can use substantial CPU on demanding videos.

Select embedded subtitle tracks or load a subtitle file from local storage. Supported formats include SRT, ASS/SSA, and WebVTT. Opening the subtitle picker keeps the current video running. The subtitle choice is remembered when the storage provider allows continued access.

## Startup and previews

The playback engine initializes in the background and is reused between videos. File access and native player shutdown run away from the UI thread.

Thumbnail extraction uses a separate private process with bundled FFmpeg libraries. It reads local file descriptors with seek support, scales decoded frames, and saves successful JPEGs. A failed preview is skipped, and repeated worker crashes stop additional native attempts for the session. The player can also save a preview from a decoded playback frame.

## Compatibility and permissions

- Android 8 or later, with ARM64 support.
- Video access is requested on first launch.
- A first-run battery optimization prompt provides the requested battery setting.
- No Internet permission.

Download APKs from [GitHub Releases](https://github.com/bash-vibecoder/vid/releases/latest). Version history is in [CHANGELOG.md](CHANGELOG.md).
