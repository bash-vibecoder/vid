#!/usr/bin/env python3
"""Create a small, test-only video with sixteen embedded subtitle tracks."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
assets = root / "app/src/androidTest/assets"
with tempfile.TemporaryDirectory(prefix="apocketvid-subtitles-") as directory:
    subtitle = Path(directory) / "track.srt"
    subtitle.write_text("1\n00:00:00,000 --> 00:00:29,900\nSubtitle list validation\n")
    command = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
               "-i", str(assets / "preview-fixture.mp4"), "-i", str(subtitle),
               "-map", "0:v:0", "-map", "0:a:0?"]
    for number in range(16):
        command += ["-map", "1:s:0", f"-metadata:s:s:{number}", f"title=Subtitle {number + 1}",
                    f"-disposition:s:{number}", "0"]
    subprocess.run(command + ["-c", "copy", str(assets / "subtitle-tracks.mkv")], check=True)
