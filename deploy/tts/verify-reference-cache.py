#!/usr/bin/env python3
"""Compare warmed GPU synthesis with/without reference caches on the real model.

Run while closure-tts.service is stopped, using its CUDA venv and service user.
"""
import argparse
import hashlib
import json
import os
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/opt/closure-tts"))
    parser.add_argument("--config", type=Path)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = args.root / "GPT-SoVITS"
    os.chdir(repo)
    sys.path[:0] = [str(repo), str(repo / "GPT_SoVITS"), str(args.root)]
    import numpy as np
    import torch
    from TTS_infer_pack.TTS import TTS, TTS_Config
    from reference_cache import install_reference_cache

    torch.set_num_threads(args.threads)
    pipeline = TTS(TTS_Config(str(args.config or args.root / "tts_infer.gpu.yaml")))
    cases = [
        ("neutral", "博士，服务器语音服务已准备好了。", "六号无人机起飞，视野正常……可以出发。"),
        ("question", "博士，现在需要我帮你检查一下吗？", "嗯？你带我来这里，是想给这里置办一点新装饰吗？"),
        ("teasing", "给，咖啡。博士，今天也要早点休息哦。", "给，咖啡。看你那黑眼圈，昨晚又熬夜了吧？"),
    ]

    def synthesize(case):
        profile, text, prompt = case
        parts = list(pipeline.run(dict(
            text=text, text_lang="zh", prompt_text=prompt, prompt_lang="zh",
            ref_audio_path=str(args.root / "reference-audio" / f"{profile}.wav"),
            seed=42, top_k=8, top_p=0.85, temperature=0.7,
            repetition_penalty=1.35, speed_factor=0.95, text_split_method="cut5",
            parallel_infer=True, batch_size=1, split_bucket=False,
            return_fragment=True, streaming_mode=False, fragment_interval=0.12,
        )))
        assert parts and len({rate for rate, _ in parts}) == 1
        return parts[0][0], np.concatenate([audio for _, audio in parts])

    # Warm each exact shape before the uncached baseline; CUDA's initial kernel
    # selection can otherwise introduce small FP16 rounding differences.
    for _ in range(2):
        for case in cases:
            synthesize(case)
    cold_baseline = []
    warm_baseline = []
    for case in cases:
        cold_baseline.append(synthesize(case))
        warm_baseline.append(synthesize(case))
    install_reference_cache(pipeline)
    results = []
    for round_number in range(2):
        # Switching voices exercises restored per-profile prompt caches as well
        # as speaker embeddings. The second round uses cache hits for every voice.
        # Upstream's reference encoding consumes Torch RNG; its own warm cache
        # skips that work. Compare each miss with an upstream reference miss,
        # and each hit with upstream's already-warmed reference, respectively.
        baseline = cold_baseline if round_number == 0 else warm_baseline
        for case, (expected_rate, expected) in zip(cases, baseline):
            rate, actual = synthesize(case)
            assert rate == expected_rate and actual.shape == expected.shape, "Audio duration/format changed"
            difference = actual.astype(np.float64) - expected.astype(np.float64)
            rms = float(np.sqrt(np.mean(difference ** 2)))
            signal = float(np.sqrt(np.mean(expected.astype(np.float64) ** 2)))
            relative = rms / max(signal, 1)
            # Permit only tiny FP16 numerical differences, below 1% of signal RMS.
            assert relative < 0.01, f"Reference cache changed speech: {case[0]} RMS={relative}"
            results.append(dict(profile=case[0], cache_state="miss" if round_number == 0 else "hit",
                                samples=len(actual), sample_rate=rate,
                                identical=bool(np.array_equal(actual, expected)),
                                relative_pcm_rmse=relative,
                                pcm_sha256=hashlib.sha256(actual.tobytes()).hexdigest()))
    report = {"passed": True, "device": str(pipeline.configs.device),
              "half": pipeline.configs.is_half, "comparisons": results}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": True, "device": report["device"],
                      "comparisons": len(results)}), flush=True)


if __name__ == "__main__":
    main()
