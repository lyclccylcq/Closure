#!/usr/bin/env python3
"""Synthesize real WAVs through Closure's remote configuration."""
import argparse
import array
import io
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import wave
from pathlib import Path


def properties(path):
    values = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        key, value = line.split("=", 1)
        def unescape(match):
            token = match.group(1)
            if token.startswith("u") and len(token) == 5:
                return chr(int(token[1:], 16))
            return {"n": "\n", "t": "\t", "r": "\r", "f": "\f"}.get(token, token)
        values[key.strip()] = re.sub(r"\\(u[0-9a-fA-F]{4}|.)", unescape, value.strip())
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--quick", action="store_true")
    args = parser.parse_args()
    settings = properties(args.config)
    base = settings["gptSovits.baseUrl"].rstrip("/")
    args.output.mkdir(parents=True, exist_ok=True)
    for endpoint, key in (("set_gpt_weights", "gptSovits.gptWeightPath"),
                          ("set_sovits_weights", "gptSovits.sovitsWeightPath")):
        url = base + "/" + endpoint + "?" + urllib.parse.urlencode({"weights_path": settings[key]})
        with urllib.request.urlopen(url, timeout=120) as response:
            response.read()
    cases = [("neutral", "博士，服务器语音服务已准备好了。")]
    if not args.quick:
        cases.extend([
            ("question", "博士，现在需要我帮你检查一下吗？"),
            ("teasing", "给，咖啡。博士，今天也要早点休息哦。"),
            ("neutral", "博士，语音模型已经迁移到服务器。参考音频和训练记录也已保存。接下来我会通过网络为你朗读回复，本地只需要保留桌宠程序和连接配置。服务器会在初次测试开始四小时后自动关机，后续使用时可以按需启动。"),
        ])
    results = []
    for index, (profile, text) in enumerate(cases, 1):
        prefix = f"gptSovits.profile.{profile}."
        payload = {
            "text": text, "text_lang": "zh",
            "ref_audio_path": settings[prefix + "refAudioPath"],
            "prompt_text": settings[prefix + "promptText"],
            "prompt_lang": settings.get(prefix + "promptLang", "zh"),
            "top_k": int(settings.get(prefix + "topK", "8")),
            "top_p": float(settings.get(prefix + "topP", "0.85")),
            "temperature": float(settings.get(prefix + "temperature", "0.7")),
            "seed": int(settings.get("gptSovits.seed", "42")),
            "repetition_penalty": float(settings.get(prefix + "repetitionPenalty", "1.35")),
            "text_split_method": settings.get("gptSovits.shortTextSplitMethod" if len(text) <= 80 else "gptSovits.longTextSplitMethod", "cut5"),
            "batch_size": 1, "speed_factor": float(settings.get(prefix + "speedFactor", "0.95")),
            "fragment_interval": 0.12, "media_type": "wav", "streaming_mode": False,
            "parallel_infer": True, "split_bucket": True,
        }
        request = urllib.request.Request(base + "/tts", data=json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json"})
        started = time.perf_counter()
        with urllib.request.urlopen(request, timeout=120) as response:
            content = response.read()
        elapsed = time.perf_counter() - started
        with wave.open(io.BytesIO(content), "rb") as wav:
            duration = wav.getnframes() / wav.getframerate()
            if wav.getsampwidth() != 2 or duration <= 0:
                raise RuntimeError("Expected nonempty PCM16 WAV")
            samples = array.array("h", wav.readframes(wav.getnframes()))
            if sys.byteorder != "little":
                samples.byteswap()
            if not samples or max(abs(sample) for sample in samples) < 100:
                raise RuntimeError("Generated WAV is empty or nearly silent")
            rate = wav.getframerate()
        output = args.output / f"{index:02d}-{profile}.wav"
        output.write_bytes(content)
        result = {"profile": profile, "text_chars": len(text), "request_seconds": round(elapsed, 2),
                  "audio_seconds": round(duration, 2), "sample_rate": rate, "file": str(output)}
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    (args.output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
