#!/usr/bin/env python3
"""Download a pinned model, stage local voice settings, optionally activate them."""
import argparse
import shutil
from pathlib import Path

from qwen_runtime import MODEL_ID, MODEL_REVISION, PROJECT_ROOT, RUNTIME, PROMPTS, DEFAULT_SPEED_FACTOR, profiles, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-dir", type=Path)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--activate", action="store_true")
    parser.add_argument("--speed-factor", type=float, default=DEFAULT_SPEED_FACTOR)
    args = parser.parse_args()
    if not 0.7 <= args.speed_factor <= 1.15:
        parser.error("speed factor must be between 0.7 and 1.15")
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if args.download:
        from huggingface_hub import snapshot_download
        path = snapshot_download(MODEL_ID, revision=MODEL_REVISION, local_dir=RUNTIME / "model", max_workers=4)
        write_json(RUNTIME / "model-source.json", {"repo_id": MODEL_ID, "revision": MODEL_REVISION, "path": str(path)})
    references = RUNTIME / "references"
    references.mkdir(exist_ok=True)
    if args.reference_dir:
        for name in PROMPTS:
            source = args.reference_dir / f"{name}.wav"
            if not source.is_file():
                parser.error(f"Missing reference audio: {source}")
            shutil.copy2(source, references / source.name)
    for profile in profiles().values():
        if not Path(profile["path"]).is_file():
            parser.error("Provide --reference-dir with neutral.wav, question.wav and teasing.wav")
    settings = [
        "# Local Qwen3-TTS 0.6B Base 8-bit on Apple Metal.",
        "# Legacy gptSovits.* keys preserve Closure's existing profile and process settings.",
        "voice.backend=qwen3-tts", "voice.autoSpeak=true",
        "gptSovits.baseUrl=http://127.0.0.1:19882", "gptSovits.server.remote=false",
        "gptSovits.streaming=true", "gptSovits.seed=42", "gptSovits.server.autoStart=true",
        f'gptSovits.server.command="{PROJECT_ROOT / "deploy/tts/start-qwen.sh"}"',
        f"gptSovits.server.workingDir={PROJECT_ROOT}", "",
    ]
    for name, profile in profiles().items():
        prefix = f"gptSovits.profile.{name}."
        settings += [prefix + "refAudioPath=" + profile["path"],
                     prefix + "promptText=" + profile["text"],
                     prefix + "promptLang=zh", prefix + f"speedFactor={args.speed_factor}", ""]
    staged = PROJECT_ROOT / "config/local-voice.qwen.properties"
    staged.write_text("\n".join(settings), encoding="utf-8")
    print("Staged:", staged)
    if args.activate:
        active = PROJECT_ROOT / "config/local-voice.properties"
        backup = PROJECT_ROOT / "config/local-voice.before-qwen.properties"
        if active.exists() and not backup.exists():
            shutil.copy2(active, backup)
        temporary = active.with_suffix(".properties.tmp")
        shutil.copy2(staged, temporary)
        temporary.replace(active)
        print("Activated:", active, "\nPrevious configuration:", backup)


if __name__ == "__main__":
    main()
