"""Pitch-preserving tempo adjustment on complete sentences, never on tiny audio chunks."""
import os
import shutil
import subprocess
from pathlib import Path


def tempo(audio, rate, factor):
    import numpy as np
    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if factor == 1.0:
        return samples
    executable = os.environ.get("CLOSURE_FFMPEG") or shutil.which("ffmpeg")
    if not executable and Path("/opt/homebrew/bin/ffmpeg").is_file():
        executable = "/opt/homebrew/bin/ffmpeg"
    if not executable:
        raise RuntimeError("ffmpeg is required for pitch-preserving speech speed adjustment")
    process = subprocess.run([
        executable, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-f", "f32le", "-ar", str(rate), "-ac", "1", "-i", "pipe:0",
        "-af", f"atempo={factor:.6f}", "-f", "f32le", "pipe:1",
    ], input=samples.astype("<f4").tobytes(), stdout=subprocess.PIPE,
       stderr=subprocess.PIPE, timeout=20, check=True)
    adjusted = np.frombuffer(process.stdout, dtype="<f4").copy()
    if not len(adjusted) or not np.isfinite(adjusted).all():
        raise RuntimeError("Tempo processing returned invalid audio")
    return adjusted
