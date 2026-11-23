"""Exercise the real voice engine and wire framing with synthetic CPU-only modules."""
import concurrent.futures
import importlib.util
import io
import json
from pathlib import Path
import queue
import struct
import sys
import threading
import types
import unittest
from unittest import mock
import wave


TTS = Path(__file__).resolve().parents[2] / "deploy" / "tts"
sys.path.insert(0, str(TTS))
try:
    spec = importlib.util.spec_from_file_location("closure_qwen_consistency_server", TTS / "serve-qwen.py")
    server_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(server_module)
    from qwen_runtime import GENERATION_MAX_CHARS, utterance_segments
finally:
    sys.path.pop(0)


class Samples:
    def __init__(self, values):
        self.values = list(values)
        self.size = len(self.values)


class SyntheticGenerator:
    def __init__(self, results):
        self.results = iter(results)
        self.closed = False

    def __iter__(self):
        return self

    def __next__(self):
        result = next(self.results)
        if isinstance(result, Exception):
            raise result
        return result

    def close(self):
        self.closed = True


class SyntheticModel:
    def __init__(self, outputs=None):
        self.calls = []
        self.generators = []
        self.outputs = outputs or (lambda _index: [types.SimpleNamespace(
            audio=[0.25, -0.25, 0.125], sample_rate=24_000)])

    def generate(self, **options):
        self.calls.append(options)
        generator = SyntheticGenerator(self.outputs(len(self.calls) - 1))
        self.generators.append(generator)
        return generator


class QwenVoiceConsistencyTest(unittest.TestCase):
    def setUp(self):
        self.mx = types.ModuleType("mlx.core")
        self.mx.random = types.SimpleNamespace(seed=mock.Mock())
        self.mx.get_peak_memory = lambda: 0
        mlx = types.ModuleType("mlx")
        mlx.__path__ = []
        mlx.core = self.mx
        numpy = types.ModuleType("numpy")
        numpy.float32 = "synthetic-float32"
        numpy.asarray = lambda values, dtype=None: Samples(values)
        self.modules = mock.patch.dict(sys.modules, {"mlx": mlx, "mlx.core": self.mx, "numpy": numpy})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        self.tempo = mock.patch.object(server_module, "tempo", side_effect=lambda audio, _rate, _speed: audio)
        self.tempo.start()
        self.addCleanup(self.tempo.stop)
        self.encoder = mock.patch.object(server_module, "wav_bytes", side_effect=lambda audio, rate:
                                        server_module.pcm_wav(struct.pack("<" + "h" * audio.size,
                                                                        *(round(v * 1000) for v in audio.values)), rate))
        self.encoder.start()
        self.addCleanup(self.encoder.stop)
        self.reference = {"path": "/synthetic/neutral.wav", "text": "Synthetic neutral reference."}
        self.engine = server_module.Engine.__new__(server_module.Engine)
        self.engine.sentence_limit = 80  # Legacy launch argument must not split 120-character replies.
        self.engine.profiles = {"neutral": self.reference}
        self.engine.model = SyntheticModel()
        self.engine.metrics = {}
        self.engine.loading = concurrent.futures.Future()
        self.engine.loading.set_result(None)
        self.engine.executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.engine.slots = threading.BoundedSemaphore(2)
        self.addCleanup(self.engine.executor.shutdown, wait=True, cancel_futures=True)

    def request(self, text):
        return {"text": text, "ref_audio": self.reference["path"], "ref_text": self.reference["text"],
                "lang_code": "chinese", "seed": 42, "speed_factor": 0.88}

    def assert_wav(self, value, expected_samples):
        with wave.open(io.BytesIO(value), "rb") as wav:
            self.assertEqual((wav.getnchannels(), wav.getsampwidth(), wav.getframerate()), (1, 2, 24_000))
            self.assertEqual(wav.readframes(wav.getnframes()), expected_samples)

    def test_short_multi_sentence_reply_uses_one_generation_without_losing_punctuation(self):
        text = "先看看桌上的零件。你想先处理哪一个？\n我在这里等你，不急！"
        result = list(self.engine.generate(self.request(text)))
        self.assertEqual(len(result), 1)
        self.assertEqual(len(self.engine.model.calls), 1)
        options = self.engine.model.calls[0]
        self.assertEqual(options["text"], text)
        self.assertEqual(options["ref_audio"], self.reference["path"])
        self.assertEqual(options["ref_text"], self.reference["text"])
        self.assertIs(options["stream"], False)
        self.assertNotIn("seed", options)
        self.mx.random.seed.assert_called_once_with(42)
        self.assertTrue(self.engine.model.generators[0].closed)

    def test_exactly_120_characters_stay_whole_despite_the_legacy_80_limit(self):
        text = "甲。乙？" * 30
        self.assertEqual(len(text), GENERATION_MAX_CHARS)
        list(self.engine.generate(self.request(text)))
        self.assertEqual([c["text"] for c in self.engine.model.calls], [text])

    def test_long_auxiliary_text_is_bounded_and_every_character_reaches_the_model(self):
        text = ("甲" * 91 + "。\n第二段？" + "乙" * 127 + "！" + "丙" * 95)
        list(self.engine.generate(self.request(text)))
        calls = self.engine.model.calls
        self.assertGreater(len(calls), 1)
        self.assertTrue(all(0 < len(c["text"]) <= 120 for c in calls))
        self.assertEqual("".join(c["text"] for c in calls), text)
        self.assertTrue(all(c["ref_audio"] == self.reference["path"] for c in calls))
        self.assertTrue(all(g.closed for g in self.engine.model.generators))
        self.assertEqual(utterance_segments(text), [c["text"] for c in calls])

    def test_complete_wav_is_built_from_one_normal_reply_generation(self):
        value = self.engine.complete(self.request("第一句。第二句？第三句！"))
        self.assertEqual(len(self.engine.model.calls), 1)
        self.assert_wav(value, struct.pack("<hhh", 250, -250, 125))

    def test_complete_wav_preserves_all_long_text_audio_blocks(self):
        text = "甲" * 241
        value = self.engine.complete(self.request(text))
        self.assertEqual("".join(c["text"] for c in self.engine.model.calls), text)
        self.assertEqual(len(self.engine.model.calls), 3)
        self.assert_wav(value, struct.pack("<hhh", 250, -250, 125) * 3)

    def test_result_below_token_budget_is_accepted_without_raising_the_budget(self):
        self.engine.model = SyntheticModel(lambda _i: [types.SimpleNamespace(
            audio=[0.25], sample_rate=24_000, token_count=767)])
        value = self.engine.complete(self.request("预算内完成。"))
        self.assert_wav(value, struct.pack("<h", 250))
        self.assertEqual(self.engine.model.calls[0]["max_tokens"], 768)
        self.assertTrue(self.engine.model.generators[0].closed)

    def test_token_budget_exhaustion_rejects_audio_and_closes_the_generator(self):
        for token_count in (768, 769):
            with self.subTest(token_count=token_count):
                self.engine.model = SyntheticModel(lambda _i: [types.SimpleNamespace(
                    audio=[0.25], sample_rate=24_000, token_count=token_count)])
                with mock.patch.object(server_module, "tempo") as tempo:
                    with self.assertRaisesRegex(RuntimeError, "token limit \\(768\\)"):
                        next(self.engine.generate(self.request("预算耗尽。")))
                    tempo.assert_not_called()
                self.assertEqual(self.engine.model.calls[0]["max_tokens"], 768)
                self.assertTrue(self.engine.model.generators[0].closed)

    def test_complete_wav_does_not_return_partial_long_text_on_token_exhaustion(self):
        self.engine.model = SyntheticModel(lambda i: [types.SimpleNamespace(
            audio=[0.25], sample_rate=24_000, token_count=10 if i == 0 else 768)])
        with self.assertRaisesRegex(RuntimeError, "potentially truncated audio"):
            self.engine.complete(self.request("甲" * 241))
        self.assertEqual(len(self.engine.model.calls), 2)
        self.assertTrue(all(g.closed for g in self.engine.model.generators))

    def test_changed_sample_rate_is_an_error_instead_of_a_corrupted_join(self):
        self.engine.model = SyntheticModel(lambda i: [types.SimpleNamespace(
            audio=[0.25], sample_rate=24_000 if i == 0 else 22_050)])
        with self.assertRaisesRegex(RuntimeError, "sample rate"):
            self.engine.complete(self.request("甲" * 121))
        self.assertTrue(all(g.closed for g in self.engine.model.generators))

    def test_cancel_before_generation_does_not_touch_model_or_rng(self):
        cancelled = threading.Event()
        cancelled.set()
        self.assertEqual(list(self.engine.generate(self.request("已取消。"), cancelled)), [])
        self.assertEqual(self.engine.model.calls, [])
        self.mx.random.seed.assert_not_called()
        outgoing = queue.Queue()
        self.engine.stream(self.request("已取消。"), outgoing, cancelled)
        self.assertTrue(outgoing.empty())

    def test_cancel_between_chunks_closes_generator_and_never_starts_the_next_chunk(self):
        cancelled = threading.Event()
        generated = self.engine.generate(self.request("甲" * 241), cancelled)
        next(generated)
        cancelled.set()
        self.assertEqual(list(generated), [])
        self.assertEqual(len(self.engine.model.calls), 1)
        self.assertTrue(self.engine.model.generators[0].closed)

    def test_closing_the_consumer_closes_the_current_model_generator(self):
        generated = self.engine.generate(self.request("甲" * 241))
        next(generated)
        generated.close()
        self.assertEqual(len(self.engine.model.calls), 1)
        self.assertTrue(self.engine.model.generators[0].closed)

    def test_stream_cancellation_during_delivery_suppresses_later_audio_and_closes_generator(self):
        cancelled = threading.Event()

        class CancelOnAudio(queue.Queue):
            def put(self, item, *args, **kwargs):
                super().put(item, *args, **kwargs)
                if isinstance(item, bytes):
                    cancelled.set()

        outgoing = CancelOnAudio()
        self.engine.stream(self.request("甲" * 241), outgoing, cancelled)
        self.assertIsInstance(outgoing.get_nowait(), bytes)
        self.assertTrue(outgoing.empty())
        self.assertEqual(len(self.engine.model.calls), 1)
        self.assertTrue(self.engine.model.generators[0].closed)

    def handler(self, text):
        class Handler(server_module.Handler):
            def send_response(self, status):
                self.status = status

            def send_header(self, name, value):
                self.response_headers[name] = value

            def end_headers(self):
                pass

        handler = Handler.__new__(Handler)
        body = json.dumps({"text": text, "ref_audio_path": self.reference["path"],
                           "prompt_text": self.reference["text"]}).encode()
        handler.path = "/closure/tts"
        handler.headers = {"Content-Length": str(len(body))}
        handler.response_headers = {}
        handler.rfile = io.BytesIO(body)
        handler.wfile = io.BytesIO()
        handler.server = types.SimpleNamespace(engine=self.engine)
        handler.close_connection = False
        return handler

    def test_real_stream_handler_frames_one_wav_and_a_terminal_marker(self):
        handler = self.handler("第一句。第二句？第三句！")
        handler.do_POST()
        self.assertEqual(handler.status, 200)
        self.assertEqual(handler.response_headers["Content-Type"], "application/vnd.closure.wav-stream")
        wire = io.BytesIO(handler.wfile.getvalue())
        self.assertEqual(wire.read(len(server_module.MAGIC)), server_module.MAGIC)
        size = struct.unpack(">I", wire.read(4))[0]
        self.assert_wav(wire.read(size), struct.pack("<hhh", 250, -250, 125))
        self.assertEqual(wire.read(), struct.pack(">I", 0))
        self.assertEqual(len(self.engine.model.calls), 1)
        self.assertTrue(handler.close_connection)

    def test_real_stream_handler_frames_errors_without_false_success(self):
        self.engine.model = SyntheticModel(lambda _i: [RuntimeError("synthetic decode failure")])
        handler = self.handler("合成测试。")
        handler.do_POST()
        wire = io.BytesIO(handler.wfile.getvalue())
        self.assertEqual(wire.read(len(server_module.MAGIC)), server_module.MAGIC)
        self.assertEqual(struct.unpack(">I", wire.read(4))[0], 0xFFFFFFFF)
        length = struct.unpack(">I", wire.read(4))[0]
        self.assertEqual(wire.read(length), b"synthetic decode failure")
        self.assertEqual(wire.read(), b"")
        self.assertTrue(self.engine.model.generators[0].closed)

    def test_real_stream_handler_frames_token_exhaustion_without_audio_or_success(self):
        self.engine.model = SyntheticModel(lambda _i: [types.SimpleNamespace(
            audio=[0.25], sample_rate=24_000, token_count=768)])
        handler = self.handler("预算耗尽。")
        handler.do_POST()
        wire = io.BytesIO(handler.wfile.getvalue())
        self.assertEqual(wire.read(len(server_module.MAGIC)), server_module.MAGIC)
        self.assertEqual(struct.unpack(">I", wire.read(4))[0], 0xFFFFFFFF)
        length = struct.unpack(">I", wire.read(4))[0]
        self.assertIn(b"token limit (768)", wire.read(length))
        self.assertEqual(wire.read(), b"")
        self.assertEqual(self.engine.model.calls[0]["max_tokens"], 768)
        self.assertTrue(self.engine.model.generators[0].closed)


if __name__ == "__main__":
    unittest.main()
