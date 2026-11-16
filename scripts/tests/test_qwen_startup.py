"""Exercise the cold listener and whole-WAV path without loading MLX in CI."""
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import sys
import threading
import unittest


TTS = Path(__file__).resolve().parents[2] / "deploy" / "tts"
sys.path.insert(0, str(TTS))
try:
    spec = importlib.util.spec_from_file_location("closure_qwen_server", TTS / "serve-qwen.py")
    server_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(server_module)
finally:
    sys.path.pop(0)


class QwenStartupTest(unittest.TestCase):
    def test_health_answers_during_warmup_and_synthesis_waits(self):
        warmup = threading.Event()
        sample = server_module.pcm_wav(b"\x01\x00" * 12, 24_000)

        class TestEngine(server_module.Engine):
            def _load(self):
                if not warmup.wait(timeout=5):
                    raise TimeoutError("Test did not release startup")

            def complete(self, request):
                return sample

        profiles = server_module.profiles(TTS)
        engine = TestEngine(TTS, 80, profiles)
        server = server_module.ThreadingHTTPServer(("127.0.0.1", 0), server_module.Handler)
        server.engine = engine
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def request(method, path, body=b""):
            with socket.create_connection(server.server_address, timeout=2) as connection:
                headers = (f"{method} {path} HTTP/1.1\r\nHost: localhost\r\n"
                           f"Connection: close\r\nContent-Length: {len(body)}\r\n\r\n")
                connection.sendall(headers.encode("ascii") + body)
                with http.client.HTTPResponse(connection) as response:
                    response.begin()
                    return response.status, response.getheader("Content-Type"), response.read()

        reference = profiles["neutral"]
        body = json.dumps({"text": "工程部在线。", "ref_audio_path": reference["path"],
                           "prompt_text": reference["text"]}).encode("utf-8")
        try:
            status, _, raw = request("GET", "/closure/health")
            health = json.loads(raw)
            self.assertEqual(status, 200)
            self.assertEqual(health["backend"], "qwen3-tts")
            self.assertIs(health["ready"], False)
            for endpoint in ("/tts", "/closure/tts"):
                status, _, raw = request("POST", endpoint, body)
                self.assertEqual(status, 503)
                self.assertIs(json.loads(raw)["ready"], False)

            warmup.set()
            engine.loading.result(timeout=2)
            status, _, raw = request("GET", "/closure/health")
            self.assertEqual(status, 200)
            self.assertIs(json.loads(raw)["ready"], True)
            self.assertEqual(request("POST", "/tts", body), (200, "audio/wav", sample))
        finally:
            warmup.set()
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
            engine.executor.shutdown(wait=True)


if __name__ == "__main__":
    unittest.main()
