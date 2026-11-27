"""Generate explicitly synthetic RGBA pages for offline CI; contains no character artwork."""
from hashlib import sha256
from pathlib import Path
import struct
import zlib

CLIPS = ("idle", "talk", "think_begin", "think_idle", "think_exit", "sad_begin", "sad_idle",
         "sad_exit", "happy", "celebrate", "interact_1", "interact_2")


def fixture_assets(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    width, height, columns, page_frames = 32, 28, 4, 16
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = bytearray()
    for y in range(height * 4):
        raw.append(0)
        for x in range(width * columns):
            tile = (y // height) * columns + x // width
            radius = ((x % width) - 16) ** 2 + ((y % height) - 14) ** 2
            raw.extend((154 + tile * 3, 126, 210, 255 if radius < 80 else 100 if radius < 100 else 0))
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width * columns,
           height * 4, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
    (directory / "synthetic.png").write_bytes(png)
    data = ["format=1", "fps=30", f"width={width}", f"height={height}", "columns=4", "pageFrames=16",
            "synthetic=true", "commit=synthetic-ci-fixture"]
    for name in CLIPS:
        data += [f"{name}.frames=16", f"{name}.pages=1", f"{name}.page.0=synthetic.png",
                 f"{name}.sha256.0={sha256(png).hexdigest()}"]
    (directory / "model.properties").write_text("\n".join(data) + "\n", encoding="ascii")
    return directory
