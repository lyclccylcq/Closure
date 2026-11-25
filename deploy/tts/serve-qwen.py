#!/usr/bin/env python3
"""Qwen3-TTS on Apple Silicon; Closure's existing complete-WAV stream transport."""
import argparse
import concurrent.futures
import json
import queue
import signal
import struct
import threading
import time
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from qwen_runtime import MODEL_ID, MODEL_REVISION, RUNTIME, DEFAULT_SPEED_FACTOR, GENERATION_MAX_CHARS, utterance_segments, pcm_wav, profiles, validate_request, wav_bytes
from qwen_audio import tempo
from qwen_adapter import adapter_config_path, apply_adapter
from stream_protocol import MAGIC

END = object()
VOICE_CONSISTENCY = "utterance-v1"
GENERATION_MAX_TOKENS = 768


class Engine:
    """Keep model loading, lazy GPU evaluation and every generator on one thread."""
    def __init__(self, model_path, sentence_limit, known_profiles, adapter_config=None):
        self.model_path = model_path
        self.adapter_config = adapter_config
        # Retain the positional argument for existing launchers/callers. Normal replies
        # now share one fixed 120-character context regardless of this legacy hint.
        self.sentence_limit = sentence_limit
        self.profiles = known_profiles
        self.executor = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="qwen-metal")
        self.slots = threading.BoundedSemaphore(2)
        self.metrics = {"adapter_loaded": False}
        self.loading = self.executor.submit(self._load)

    @property
    def ready(self):
        return self.loading.done() and not self.loading.cancelled() and self.loading.exception() is None

    def _load(self):
        import mlx.core as mx
        from mlx_audio.tts.utils import load_model
        if not mx.metal.is_available():
            raise RuntimeError("Apple Metal GPU is unavailable; refusing to silently use CPU")
        mx.set_default_device(mx.gpu)
        mx.set_cache_limit(512 * 1024**2)
        started = time.monotonic()
        self.model = load_model(str(self.model_path))
        if self.adapter_config is not None:
            self.metrics.update(apply_adapter(self.model, self.adapter_config, MODEL_ID, MODEL_REVISION))
        for name, profile in self.profiles.items():
            if not Path(profile["path"]).is_file():
                raise FileNotFoundError(profile["path"])
            request = {"text": "工程部在线。", "ref_audio": profile["path"],
                       "ref_text": profile["text"], "lang_code": "chinese", "seed": 42,
                       "speed_factor": DEFAULT_SPEED_FACTOR}
            self.complete(request)
            print(f"Qwen voice warmed: {name}", flush=True)
        self.metrics["startup_seconds"] = round(time.monotonic() - started, 3)

    def generate(self, request, cancelled=None):
        if cancelled is not None and cancelled.is_set():
            return
        import mlx.core as mx
        import numpy as np
        options = dict(request)
        mx.random.seed(options.pop("seed"))
        speed = options.pop("speed_factor", DEFAULT_SPEED_FACTOR)
        started = time.monotonic()
        first = None
        count = 0
        text = options.pop("text")
        for segment in utterance_segments(text):
            if cancelled is not None and cancelled.is_set():
                return
            # A short multi-sentence reply must not restart ICL at punctuation.
            generator = self.model.generate(text=segment, **options, stream=False,
                                            max_tokens=GENERATION_MAX_TOKENS, verbose=False)
            try:
                for result in generator:
                    if cancelled is not None and cancelled.is_set():
                        return
                    # mlx-audio also yields a normal result when its loop exhausts
                    # the budget. EOS exits before appending a token, so reaching
                    # this count means completion was not confirmed.
                    if getattr(result, "token_count", 0) >= GENERATION_MAX_TOKENS:
                        raise RuntimeError(
                            f"Qwen3-TTS reached the generation token limit ({GENERATION_MAX_TOKENS}); "
                            "refusing to return potentially truncated audio")
                    samples = np.asarray(result.audio, dtype=np.float32)
                    if samples.size == 0:
                        continue
                    samples = tempo(samples, result.sample_rate, speed)
                    if first is None:
                        first = time.monotonic() - started
                    count += 1
                    yield result.sample_rate, wav_bytes(samples, result.sample_rate)
            finally:
                generator.close()
        if count == 0:
            raise RuntimeError("Qwen3-TTS returned an empty audio stream")
        self.metrics.update(first_audio_seconds=round(first, 3),
                            synthesis_seconds=round(time.monotonic() - started, 3),
                            peak_mlx_memory_gb=round(mx.get_peak_memory() / 1024**3, 3))

    def complete(self, request):
        import io
        import wave
        frames = []
        rate = None
        with closing(self.generate(request)) as generated:
            for next_rate, audio in generated:
                if rate is not None and rate != next_rate:
                    raise RuntimeError("Qwen3-TTS changed sample rate within an utterance")
                rate = next_rate
                with wave.open(io.BytesIO(audio), "rb") as wav:
                    frames.append(wav.readframes(wav.getnframes()))
        return pcm_wav(b"".join(frames), rate)

    def stream(self, request, outgoing, cancelled):
        def put(value):
            while not cancelled.is_set():
                try:
                    outgoing.put(value, timeout=0.1)
                    return
                except queue.Full:
                    pass
        try:
            if cancelled.is_set():
                return
            with closing(self.generate(request, cancelled)) as generated:
                for _rate, audio in generated:
                    put(audio)
                    if cancelled.is_set():
                        break
        except Exception as error:
            put(error)
        finally:
            put(END)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if urlsplit(self.path).path not in {"/", "/closure/health"}:
            self.respond(404, {"error": "Unknown endpoint"})
            return
        self.respond(200, {"ready": self.server.engine.ready, "backend": "qwen3-tts",
                           "model_id": MODEL_ID, "device": "metal", "stream_protocol": "CTTS1",
                           "synthesis_mode": "full-icl-utterance", "default_speed_factor": DEFAULT_SPEED_FACTOR,
                           "voice_consistency": VOICE_CONSISTENCY,
                           "generation_max_chars": GENERATION_MAX_CHARS,
                           "pitch_preserving_tempo": True,
                           "profiles": list(self.server.engine.profiles), **self.server.engine.metrics})

    def do_POST(self):
        route = urlsplit(self.path).path
        if route not in {"/tts", "/closure/tts"}:
            self.respond(404, {"error": "Unknown endpoint"})
            return
        engine = self.server.engine
        if not engine.ready:
            self.close_connection = True
            self.respond(503, {"error": "Qwen3-TTS is warming up", "ready": False})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 65536:
                raise ValueError("Expected a JSON request smaller than 64 KiB")
            body = json.loads(self.rfile.read(size))
            request = validate_request(body, self.server.engine.profiles)
        except (ValueError, TypeError, OSError) as error:
            self.respond(400, {"error": str(error)})
            return
        if not engine.slots.acquire(blocking=False):
            self.respond(429, {"error": "The speech queue is full"})
            return
        if route == "/tts":
            future = engine.executor.submit(engine.complete, request)
            future.add_done_callback(lambda _: engine.slots.release())
            try:
                self.respond(200, future.result(), "audio/wav")
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as error:
                self.respond(500, {"error": str(error)})
            return
        outgoing = queue.Queue(maxsize=2)
        cancelled = threading.Event()
        future = engine.executor.submit(engine.stream, request, outgoing, cancelled)
        future.add_done_callback(lambda _: engine.slots.release())
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.closure.wav-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(MAGIC)
            self.wfile.flush()
            while True:
                item = outgoing.get()
                if item is END:
                    self.wfile.write(struct.pack(">I", 0))
                    break
                if isinstance(item, Exception):
                    message = str(item).encode("utf-8")[:8192]
                    self.wfile.write(struct.pack(">II", 0xFFFFFFFF, len(message)) + message)
                    break
                self.wfile.write(struct.pack(">I", len(item)) + item)
                self.wfile.flush()
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            cancelled.set()
            self.close_connection = True

    def respond(self, status, body, content_type="application/json"):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=19882)
    parser.add_argument("--model", type=Path, default=RUNTIME / "model")
    parser.add_argument("--runtime", type=Path, default=RUNTIME)
    adapter = parser.add_mutually_exclusive_group()
    adapter.add_argument("--adapter-config", type=Path, help="Local adapter manifest (defaults to runtime/active-adapter.json when present)")
    adapter.add_argument("--no-adapter", action="store_true", help="Use the original base voice even when a local adapter is activated")
    parser.add_argument("--sentence-max-chars", type=int, default=80,
                        help="Legacy launch compatibility; generation now uses fixed 120-character utterances")
    args = parser.parse_args()
    if not 40 <= args.sentence_max_chars <= 160:
        parser.error("sentence max chars must be between 40 and 160")
    # Serve health immediately, including while the Metal worker warms the profiles.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    try:
        selected_adapter = adapter_config_path(args.runtime, args.adapter_config, args.no_adapter)
        server.engine = Engine(args.model, args.sentence_max_chars, profiles(args.runtime), selected_adapter)
        signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())

        def watch_model():
            try:
                server.engine.loading.result()
            except Exception:
                server.shutdown()
                return
            print(json.dumps({"ready": True, "backend": "qwen3-tts", "model_id": MODEL_ID,
                              "device": "metal", **server.engine.metrics}), flush=True)

        threading.Thread(target=watch_model, name="qwen-startup-status", daemon=True).start()
        print(f"Serving Qwen3-TTS on http://127.0.0.1:{server.server_port}", flush=True)
        server.serve_forever(poll_interval=0.2)
        if server.engine.loading.done():
            server.engine.loading.result()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if hasattr(server, "engine"):
            server.engine.executor.shutdown(wait=True, cancel_futures=True)


if __name__ == "__main__":
    main()
