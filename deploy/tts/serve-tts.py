#!/usr/bin/env python3
"""Keep the upstream API and add serialized, complete-WAV fragment streaming."""
import asyncio
import io
import os
import sys
from pathlib import Path

ROOT = Path("/opt/closure-tts/GPT-SoVITS")
os.chdir(ROOT)
sys.path[:0] = [str(ROOT), str(ROOT / "GPT_SoVITS")]
import torch
torch.set_num_threads(int(os.environ.get("CLOSURE_TTS_CPU_THREADS", "4")))
import api_v2 as upstream
import uvicorn
from fastapi.responses import StreamingResponse
from stream_protocol import frame_audio
from reference_cache import install_reference_cache

MODEL_PATHS = {"/tts", "/closure/tts", "/set_gpt_weights", "/set_sovits_weights",
               "/set_refer_audio", "/control"}


class SerializedModel:
    """The model has mutable prompt/inference state; lock through the whole stream."""
    def __init__(self, app):
        self.app = app
        self.lock = asyncio.Lock()

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope.get("path") in MODEL_PATHS:
            async with self.lock:
                await self.app(scope, receive, send)
        else:
            await self.app(scope, receive, send)


upstream.APP.add_middleware(SerializedModel)


def avoid_reloading_unchanged_weights(method_name, config_field, model_field):
    pipeline = upstream.tts_pipeline
    original = getattr(pipeline, method_name)

    def signature(path):
        path = Path(path).resolve()
        stat = path.stat()
        return str(path), stat.st_size, stat.st_mtime_ns

    loaded = signature(getattr(pipeline.configs, config_field))

    def load(path):
        nonlocal loaded
        requested = signature(path)
        if requested == loaded and getattr(pipeline, model_field) is not None:
            return
        original(path)
        loaded = requested

    setattr(pipeline, method_name, load)


# The desktop verifies its configured weights on launch. Preserve warmed models
# when those exact files are already loaded, while still allowing changed files.
avoid_reloading_unchanged_weights("init_t2s_weights", "t2s_weights_path", "t2s_model")
avoid_reloading_unchanged_weights("init_vits_weights", "vits_weights_path", "vits_model")
install_reference_cache(upstream.tts_pipeline)


@upstream.APP.post("/closure/tts")
async def closure_tts(request: upstream.TTS_Request):
    inputs = request.model_dump()
    error = upstream.check_params(inputs)
    if error is not None:
        return error
    # Upstream's best-quality sentence-fragment mode; no token-chunk quality reduction.
    inputs.update(streaming_mode=False, return_fragment=True, fixed_length_chunk=False,
                  batch_size=1, split_bucket=False)

    def encode(rate, audio):
        return upstream.pack_audio(io.BytesIO(), audio, rate, "wav").getvalue()

    return StreamingResponse(frame_audio(upstream.tts_pipeline.run(inputs), encode),
                             media_type="application/vnd.closure.wav-stream",
                             headers={"Cache-Control": "no-store"})


@upstream.APP.get("/closure/health")
async def closure_health():
    config = upstream.tts_pipeline.configs
    return {"ready": True, "device": str(config.device), "half": config.is_half,
            "stream_protocol": "CTTS1", "cuda_available": torch.cuda.is_available(),
            "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None}


if __name__ == "__main__":
    # Absorb JIT/kernel warmup on the server before accepting desktop requests.
    warmup = {"text": "博士，服务器语音服务已准备好了。", "text_lang": "zh",
              "ref_audio_path": "/opt/closure-tts/reference-audio/neutral.wav",
              "prompt_text": "六号无人机起飞，视野正常……可以出发。", "prompt_lang": "zh",
              "seed": 42, "top_k": 8, "top_p": 0.85, "temperature": 0.7,
              "repetition_penalty": 1.35, "speed_factor": 0.95,
              "text_split_method": "cut0", "parallel_infer": True,
              "batch_size": 1, "fragment_interval": 0.12}
    profiles = [
        ("neutral", "六号无人机起飞，视野正常……可以出发。"),
        ("question", "嗯？你带我来这里，是想给这里置办一点新装饰吗？"),
        ("teasing", "给，咖啡。看你那黑眼圈，昨晚又熬夜了吧？"),
    ]
    for _ in range(2):
        for profile, prompt in profiles:
            inputs = dict(warmup, ref_audio_path=f"/opt/closure-tts/reference-audio/{profile}.wav",
                          prompt_text=prompt)
            for _rate, _audio in upstream.tts_pipeline.run(inputs):
                pass
    print("Closure TTS warmup complete; serving complete WAV fragments.", flush=True)
    uvicorn.run(upstream.APP, host=upstream.host, port=upstream.port)
