#!/usr/bin/env python3
"""Measure first complete audio and total synthesis through Closure's WAV stream."""
import argparse
import array
import importlib.util
import io
import json
import struct
import time
import urllib.parse
import urllib.request
import wave
from pathlib import Path

spec = importlib.util.spec_from_file_location("smoke", Path(__file__).with_name("smoke-test.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.read(size - len(data))
        if not part:
            raise EOFError("Truncated Closure stream")
        data.extend(part)
    return bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--quick", action="store_true")
    args = parser.parse_args()
    settings = smoke.properties(args.config)
    base = settings["gptSovits.baseUrl"].rstrip("/")
    args.output.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(base + "/closure/health", timeout=10) as response:
        health = json.load(response)
    print(json.dumps({"health": health}), flush=True)
    for endpoint, key in (("set_gpt_weights", "gptSovits.gptWeightPath"),
                          ("set_sovits_weights", "gptSovits.sovitsWeightPath")):
        with urllib.request.urlopen(base + "/" + endpoint + "?" + urllib.parse.urlencode(
                {"weights_path": settings[key]}), timeout=120) as response:
            response.read()
    cases = [("neutral", "博士，服务器语音服务已准备好了。")]
    if not args.quick:
        cases = cases * 3 + [
            ("question", "博士，现在需要我帮你检查一下吗？"),
            ("teasing", "给，咖啡。博士，今天也要早点休息哦。"),
            ("neutral", "博士，语音模型已经迁移到服务器。参考音频和训练记录也已保存。接下来我会通过网络为你朗读回复，本地只需要保留桌宠程序和连接配置。服务器会在初次测试开始四小时后自动关机，后续使用时可以按需启动。"),
        ]
    results = []
    for index, (profile, text) in enumerate(cases, 1):
        prefix = f"gptSovits.profile.{profile}."
        payload = dict(text=text, text_lang="zh",
                       ref_audio_path=settings[prefix + "refAudioPath"],
                       prompt_text=settings[prefix + "promptText"],
                       prompt_lang=settings.get(prefix + "promptLang", "zh"),
                       top_k=int(settings.get(prefix + "topK", "8")),
                       top_p=float(settings.get(prefix + "topP", "0.85")),
                       temperature=float(settings.get(prefix + "temperature", "0.7")),
                       seed=int(settings.get("gptSovits.seed", "42")),
                       repetition_penalty=float(settings.get(prefix + "repetitionPenalty", "1.35")),
                       speed_factor=float(settings.get(prefix + "speedFactor", "0.95")),
                       text_split_method="cut5", fragment_interval=0.12,
                       batch_size=1, parallel_infer=True, split_bucket=False, media_type="wav")
        request = urllib.request.Request(base + "/closure/tts", data=json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json"})
        started = time.perf_counter()
        fragments = []
        events = []
        format = None
        with urllib.request.urlopen(request, timeout=120) as response:
            if read_exact(response, 6) != b"CTTS1\n":
                raise RuntimeError("Expected Closure WAV streaming protocol")
            while True:
                length, = struct.unpack(">I", read_exact(response, 4))
                if length == 0:
                    break
                if length == 0xffffffff:
                    size, = struct.unpack(">I", read_exact(response, 4))
                    raise RuntimeError(read_exact(response, size).decode())
                if not 44 <= length <= 32 * 1024 * 1024:
                    raise RuntimeError("Invalid WAV frame length")
                content = read_exact(response, length)
                received = time.perf_counter() - started
                with wave.open(io.BytesIO(content), "rb") as wav:
                    current = wav.getnchannels(), wav.getsampwidth(), wav.getframerate()
                    if format is not None and format != current:
                        raise RuntimeError("Inconsistent fragment audio format")
                    format = current
                    pcm = wav.readframes(wav.getnframes())
                    samples = array.array("h", pcm)
                    if not samples or max(abs(value) for value in samples) < 100:
                        raise RuntimeError("Silent or empty generated audio")
                    duration = wav.getnframes() / wav.getframerate()
                    fragments.append(pcm)
                    events.append({"received_seconds": round(received, 3),
                                   "audio_seconds": round(duration, 3)})
        if not events:
            raise RuntimeError("Empty audio stream")
        elapsed = time.perf_counter() - started
        output = args.output / f"{index:02d}-{profile}.wav"
        with wave.open(str(output), "wb") as wav:
            wav.setnchannels(format[0]); wav.setsampwidth(format[1]); wav.setframerate(format[2])
            wav.writeframes(b"".join(fragments))
        result = {"profile": profile, "text_chars": len(text),
                  "first_audio_seconds": events[0]["received_seconds"],
                  "total_synthesis_seconds": round(elapsed, 3),
                  "audio_seconds": round(sum(event["audio_seconds"] for event in events), 3),
                  "fragment_count": len(fragments), "events": events, "file": str(output)}
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    (args.output / "results.json").write_text(json.dumps({"health": health, "results": results},
                                                       ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
