"""Local reference profiles and PCM encoding shared by the Qwen service/tools."""
import io
import json
import re
import wave
from pathlib import Path

MODEL_ID = "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-8bit"
MODEL_REVISION = "50f45ef0047cde7e84c2ef04326acb8ada2436a7"
PROJECT_ROOT = Path(__file__).resolve().parents[2]
RUNTIME = PROJECT_ROOT / ".runtime" / "qwen3-tts"
DEFAULT_SPEED_FACTOR = 0.88
PROMPTS = {
    "neutral": "六号无人机起飞，视野正常……可以出发。",
    "question": "嗯？你带我来这里，是想给这里置办一点新装饰吗？",
    "teasing": "给，咖啡。看你那黑眼圈，昨晚又熬夜了吧？",
}


def profiles(runtime=RUNTIME):
    return {
        name: {"path": str((runtime / "references" / f"{name}.wav").resolve()), "text": text}
        for name, text in PROMPTS.items()
    }


def wav_bytes(audio, rate):
    import numpy as np
    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if not len(samples) or not np.isfinite(samples).all():
        raise ValueError("The model returned empty or non-finite audio")
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes()
    return pcm_wav(pcm, rate)


def pcm_wav(pcm, rate):
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(pcm)
    return output.getvalue()


def generation_segments(text, limit=120):
    """Bound long prefills and token budgets while preserving every input character."""
    segments = []
    while len(text) > limit:
        split = max(text.rfind(mark, 0, limit) + 1 for mark in "。！？!?；;，,\n")
        split = split if split >= limit // 3 else limit
        segments.append(text[:split])
        text = text[split:]
    if text.strip():
        segments.append(text)
    return segments


def sentence_segments(text, limit=80):
    """Keep sentence punctuation and question context; split only long clauses further."""
    result = []
    for sentence in re.findall(r'[^。！？!?；;\n]+[。！？!?；;]*[”’」』"]*|[。！？!?；;]+', text):
        if sentence.strip():
            result.extend(generation_segments(sentence.strip(), limit))
    return result


def validate_request(body, known_profiles):
    if not isinstance(body, dict):
        raise ValueError("Expected a JSON object")
    text = body.get("text", "")
    if not isinstance(text, str) or not text.strip() or len(text) > 2000:
        raise ValueError("text must contain 1–2000 characters")
    reference = body.get("ref_audio_path", "")
    if not isinstance(reference, str) or not reference:
        raise ValueError("A configured reference audio path is required")
    requested = str(Path(reference).resolve())
    profile = next((p for p in known_profiles.values() if p["path"] == requested), None)
    if profile is None:
        raise ValueError("Reference audio is not one of the configured voice profiles")
    if body.get("prompt_text") != profile["text"]:
        raise ValueError("Reference transcript does not match the configured voice profile")
    language = body.get("text_lang", "zh")
    language = {"zh": "chinese", "en": "english"}.get(language, language)
    if language not in {"chinese", "english", "auto"}:
        raise ValueError("Supported text languages: zh, en, auto")
    seed = body.get("seed", 42)
    if isinstance(seed, bool) or not isinstance(seed, int) or not 0 <= seed < 2**32:
        raise ValueError("seed must be an unsigned 32-bit integer")
    speed = body.get("speed_factor", DEFAULT_SPEED_FACTOR)
    if isinstance(speed, bool) or not isinstance(speed, (float, int)) or not 0.7 <= speed <= 1.15:
        raise ValueError("speed_factor must be between 0.7 and 1.15")
    return {"text": text.strip(), "ref_audio": profile["path"],
            "ref_text": profile["text"], "lang_code": language, "seed": seed, "speed_factor": speed}


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
