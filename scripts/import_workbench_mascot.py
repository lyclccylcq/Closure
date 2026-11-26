#!/usr/bin/env python3
"""Import a local VP9-alpha model for personal use in Closure's workbench."""
import argparse
from hashlib import sha256
import math
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid

FPS = 12
WIDTH = 222
HEIGHT = 187
COLUMNS = 8
MAX_FRAMES = 240


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path, help="local dsh-closure-pet assets/video/idle.webm")
    parser.add_argument("--output", type=Path, default=Path.home() / ".closure/assets/workbench")
    args = parser.parse_args(argv)
    video = args.video.resolve(strict=True)
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        parser.error("ffmpeg with the libvpx-vp9 decoder is required")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".import-", dir=output) as temporary:
        directory = Path(temporary)
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-c:v", "libvpx-vp9",
                        "-i", str(video), "-vf", f"fps={FPS},scale={WIDTH}:{HEIGHT}:flags=lanczos",
                        "-frames:v", str(MAX_FRAMES + 1), str(directory / "%04d.png")],
                       check=True, timeout=120)
        count = len(list(directory.glob("*.png")))
        if not 1 <= count <= MAX_FRAMES:
            raise ValueError("Model must contain between 1 and 240 frames at 12 fps")
        rows = math.ceil(count / COLUMNS)
        sheet_name = "idle-" + uuid.uuid4().hex + ".png"
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-framerate", str(FPS),
                        "-i", str(directory / "%04d.png"), "-vf", f"tile={COLUMNS}x{rows}",
                        "-frames:v", "1", str(directory / sheet_name)], check=True, timeout=60)
        metadata = (f"image={sheet_name}\nwidth={WIDTH}\nheight={HEIGHT}\ncolumns={COLUMNS}\n"
                    f"frames={count}\nfps={FPS}\nsource_sha256={sha256(video.read_bytes()).hexdigest()}\n")
        (directory / "idle.properties").write_text(metadata, encoding="utf-8")
        os.replace(directory / sheet_name, output / sheet_name)
        os.replace(directory / "idle.properties", output / "idle.properties")
    print(f"Imported {count} transparent frames to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
