#!/usr/bin/env python3
"""Import the pinned Closure v2 character animations for personal local use.

Requires ffmpeg with libvpx-vp9 and ffprobe. No network access or DSH code is used.
Restricted artwork stays outside the checkout; only the importer is distributed.
"""
import argparse
from hashlib import sha1, sha256
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid

COMMIT = "e1008aae230eeb65917593dae7de602d4c8e6204"
SOURCE = "https://github.com/fengshiyangrouguan/dsh-closure-pet"
BLOBS = {
    "idle": "7cf8a0c4191a458500bdced7776db23821c95a0a",
    "talk": "64bc97d01ebbe349862997c7b92e2bc4151d9049",
    "think_begin": "b7dad772dbba8ef3443c5b09fbe7c0732d49c6be",
    "think_idle": "1b8547c66d5c45df60956057638d7a1a89e60b76",
    "think_exit": "f588b733e0a57525e4c7bfc25b2281c05468cce8",
    "sad_begin": "afb89e47484b8bc6dd988273a06edcf1393ebd06",
    "sad_idle": "e22a5456a50f04d5818bca4aa7d67482d0020f75",
    "sad_exit": "a934c070ea7ab5e9a8612ba5b1c9d15c881995a3",
    "happy": "49817fb86eff99aeb070ff227ac1e95dd037a44c",
    "celebrate": "7158308672dea714e2a6cdb22b71c039355b32c0",
    "interact_1": "3949439d226d94421815d9011234adb604a58f31",
    "interact_2": "d490a526f49141997d661cee17714b550122fd7e",
}
FPS, WIDTH, HEIGHT, COLUMNS, PAGE_FRAMES = 30, 444, 374, 4, 16


def inspect(video, ffprobe):
    data = video.read_bytes()
    blob = sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if blob != BLOBS[video.stem]:
        raise ValueError(f"Unexpected source content: {video.name}; use commit {COMMIT}")
    result = subprocess.run([ffprobe, "-v", "error", "-count_frames", "-show_streams", "-of", "json", str(video)],
                            capture_output=True, text=True, check=True, timeout=30)
    streams = json.loads(result.stdout)["streams"]
    if len(streams) != 1:
        raise ValueError(f"Expected a single transparent video stream: {video.name}")
    stream = streams[0]
    if (stream.get("codec_name"), stream.get("width"), stream.get("height"),
            stream.get("r_frame_rate"), stream.get("tags", {}).get("alpha_mode")) != ("vp9", 888, 748, "30/1", "1"):
        raise ValueError(f"Expected 888x748 30fps VP9-alpha: {video.name}")
    frames = int(stream["nb_read_frames"])
    if not 1 <= frames <= 300:
        raise ValueError(f"Unexpected frame count: {video.name}")
    return {"file": video.name, "git_blob": blob, "sha256": sha256(data).hexdigest(), "frames": frames}


def import_model(source, output):
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise ValueError("ffmpeg with libvpx-vp9 and ffprobe are required")
    source = source.resolve(strict=True)
    videos = [source / (name + ".webm") for name in BLOBS]
    for video in videos:
        if video.is_symlink() or not video.is_file() or video.stat().st_size > 8_000_000:
            raise ValueError(f"Missing or invalid source file: {video.name}")
    # Check every source before modifying the local installation.
    provenance = [inspect(video, ffprobe) for video in videos]
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    edition = "v2-" + uuid.uuid4().hex
    metadata = ["format=1", "fps=30", f"width={WIDTH}", f"height={HEIGHT}",
                f"columns={COLUMNS}", f"pageFrames={PAGE_FRAMES}", "source=" + SOURCE,
                "commit=" + COMMIT, "synthetic=false"]
    published = None
    try:
        with tempfile.TemporaryDirectory(prefix=".import-", dir=output) as temporary:
            directory = Path(temporary)
            for video, record in zip(videos, provenance):
                name, frames = video.stem, record["frames"]
                subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-c:v", "libvpx-vp9",
                                "-i", str(video), "-an", "-vf",
                                f"fps={FPS},scale={WIDTH}:{HEIGHT}:flags=lanczos,format=rgba,"
                                f"tile={COLUMNS}x{PAGE_FRAMES // COLUMNS}:nb_frames={PAGE_FRAMES}:color=black@0",
                                str(directory / (name + "-%04d.png"))], check=True, timeout=120)
                pages = sorted(directory.glob(name + "-*.png"))
                if len(pages) != math.ceil(frames / PAGE_FRAMES):
                    raise ValueError(f"Incomplete frame pages: {name}")
                metadata += [f"{name}.frames={frames}", f"{name}.pages={len(pages)}"]
                for i, page in enumerate(pages):
                    metadata.append(f"{name}.page.{i}={edition}/{page.name}")
                    metadata.append(f"{name}.sha256.{i}={sha256(page.read_bytes()).hexdigest()}")
            (directory / "provenance.json").write_text(json.dumps({"source": SOURCE, "commit": COMMIT,
                    "files": provenance}, indent=2) + "\n", encoding="utf-8")
            (directory / "NOTICE.txt").write_text(
                "Character artwork derived from Arknights; rights remain with the original rights holders.\n"
                "These character animations are for personal local entertainment only and are not MIT artwork.\n"
                "Do not redistribute without the necessary rights.\n" + SOURCE + "/blob/" + COMMIT + "/NOTICE.md\n",
                encoding="utf-8")
            published = output / edition
            os.replace(directory, published)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output,
                                         prefix=".model-", delete=False) as manifest:
            manifest.write("\n".join(metadata) + "\n")
            manifest_path = Path(manifest.name)
        try:
            os.replace(manifest_path, output / "model.properties")
        finally:
            manifest_path.unlink(missing_ok=True)
    except BaseException:
        if published is not None:
            shutil.rmtree(published, ignore_errors=True)
        raise
    return {"directory": str(output), "edition": edition, "fps": FPS,
            "clips": len(provenance), "frames": sum(row["frames"] for row in provenance), "commit": COMMIT}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="local assets/video directory from the pinned source repository")
    parser.add_argument("--output", type=Path, default=Path.home() / ".closure/assets/pet")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(import_model(args.source, args.output)))
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
