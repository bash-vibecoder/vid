# Seek test fixture

`seek-stress.mp4` is a synthetic, test-only 1280×720 H.264 video at 24 fps,
with keyframes ten seconds apart. Regenerate it with:

```sh
python3 scripts/generate-seek-fixture.py
```

Two 64-pixel-high bands, at y=320 and y=656, contain ten black/white blocks.
From left to right they encode the frame number at 24 fps, least significant
bit first. Device tests locate the video inside any black side bars, read
both bands from the decoded surface, require matching frame numbers, and
compare that picture time with the seek timer.
This fixture is included only in the instrumentation APK.

A very quiet AAC tone exercises audio decoding during live seeks. Tests verify
that seeking leaves the playback intent and native volume unchanged.

`subtitle-tracks.mkv` copies the synthetic preview fixture and adds sixteen
embedded subtitle tracks. It checks that selecting the last track and repeatedly
opening the menu keeps Off visible at the top while preserving the active radio
selection. Regenerate it with `python3 scripts/generate-subtitle-fixture.py`.
