#!/usr/bin/env python3
"""Generate the test-only H.264 seek fixture and its machine-readable frame clock."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
output = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/src/androidTest/assets/seek-stress.mp4"
output.parent.mkdir(parents=True, exist_ok=True)
filters = []
for band_y in (320, 656):
    filters.append(f"drawbox=x=0:y={band_y}:w=1280:h=64:color=black:t=fill")
    for bit in range(10):
        filters.append(
            f"drawbox=x={bit * 128}:y={band_y}:w=128:h=64:color=white:t=fill:"
            f"enable='bitand(floor(t*24+0.5),{1 << bit})'"
        )
subprocess.run([
    "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=24:duration=40",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=40",
    "-vf", ",".join(filters), "-c:v", "libx264", "-preset", "veryfast", "-crf", "36",
    "-g", "240", "-keyint_min", "240", "-sc_threshold", "0", "-bf", "3",
    "-pix_fmt", "yuv420p", "-af", "volume=0.01", "-c:a", "aac", "-b:a", "64k",
    "-shortest", "-movflags", "+faststart", str(output),
], check=True)
