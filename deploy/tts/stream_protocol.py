"""Closure WAV stream: magic, big-endian length + WAV frames, zero-length EOF."""
import struct

MAGIC = b"CTTS1\n"
ERROR = 0xFFFFFFFF


def frame_audio(fragments, encode):
    yield MAGIC
    try:
        for rate, audio in fragments:
            wav = encode(rate, audio)
            if not 44 <= len(wav) <= 32 * 1024 * 1024:
                raise ValueError("Invalid WAV fragment length")
            yield struct.pack(">I", len(wav)) + wav
        yield struct.pack(">I", 0)
    except Exception as error:
        message = str(error).encode("utf-8")[:8192]
        yield struct.pack(">II", ERROR, len(message)) + message
    finally:
        close = getattr(fragments, "close", None)
        if close:
            close()
