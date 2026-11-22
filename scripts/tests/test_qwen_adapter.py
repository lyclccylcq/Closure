"""Adapter configuration and startup contracts; no MLX or model calls in CI."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock

TTS = Path(__file__).resolve().parents[2] / "deploy" / "tts"
sys.path.insert(0, str(TTS))
try:
    import qwen_adapter
    from qwen_runtime import MODEL_ID, MODEL_REVISION
    spec = importlib.util.spec_from_file_location("closure_qwen_adapter_server", TTS / "serve-qwen.py")
    server_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(server_module)
finally:
    sys.path.pop(0)


class AdapterManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.weights = self.root / "adapters" / "voice.safetensors"
        self.weights.parent.mkdir()
        self.weights.write_bytes(b"fake binary; never passed to MLX")
        self.path = self.root / "active-adapter.json"
        self.config = {"format": qwen_adapter.FORMAT, "id": "test-voice", "base_model_id": MODEL_ID,
                       "base_model_revision": MODEL_REVISION, "rank": 8, "alpha": 16,
                       "targets": ["q_proj", "v_proj"], "weights": "adapters/voice.safetensors",
                       "sha256": hashlib.sha256(self.weights.read_bytes()).hexdigest()}
        self.write()

    def write(self):
        self.path.write_text(json.dumps(self.config))

    def read(self):
        return qwen_adapter.read_adapter_config(self.path, MODEL_ID, MODEL_REVISION)

    def test_selection_and_explicit_opt_out(self):
        self.assertEqual(qwen_adapter.adapter_config_path(self.root), self.path)
        self.assertIsNone(qwen_adapter.adapter_config_path(self.root, disabled=True))
        self.path.unlink()
        self.assertIsNone(qwen_adapter.adapter_config_path(self.root))
        requested = self.root / "missing.json"
        self.assertEqual(qwen_adapter.adapter_config_path(self.root, requested), requested)
        with self.assertRaises(FileNotFoundError):
            qwen_adapter.read_adapter_config(requested, MODEL_ID, MODEL_REVISION)
        self.path.symlink_to(requested)
        self.assertEqual(qwen_adapter.adapter_config_path(self.root), self.path)
        with self.assertRaises(FileNotFoundError):
            self.read()

    def test_valid_manifest_and_relocated_runtime(self):
        config = self.read()
        self.assertEqual(config["weights_path"], self.weights)
        self.assertEqual(config["sha256"], self.config["sha256"])
        moved = self.root / "runnable-link"
        moved.symlink_to(self.path)
        self.assertEqual(qwen_adapter.read_adapter_config(moved, MODEL_ID, MODEL_REVISION)["weights_path"], self.weights)

    def test_rejects_wrong_base_and_adapter_layout(self):
        for key, value in (("base_model_id", "other/model"), ("base_model_revision", "0" * 40),
                           ("format", "python-module"), ("targets", ["q_proj", "code_predictor"]),
                           ("rank", True), ("rank", 0), ("rank", 129),
                           ("alpha", float("nan")), ("alpha", True), ("alpha", 0), ("id", "../voice")):
            with self.subTest(key=key, value=value):
                original = self.config[key]
                self.config[key] = value
                self.write()
                with self.assertRaises(ValueError):
                    self.read()
                self.config[key] = original
        self.write()

    def test_rejects_changed_or_missing_weights(self):
        self.weights.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.read()
        self.weights.unlink()
        with self.assertRaises(FileNotFoundError):
            self.read()

    def test_joint_manifest_requires_both_backbones_and_exact_targets(self):
        self.config.update(format=qwen_adapter.JOINT_FORMAT, rank=32, alpha=64,
                           targets=list(qwen_adapter.JOINT_TARGETS), scopes=list(qwen_adapter.JOINT_SCOPES))
        self.write()
        self.assertEqual(self.read()["format"], qwen_adapter.JOINT_FORMAT)
        for key, value in (("scopes", ["talker.model"]), ("scopes", ["speaker_encoder"]),
                           ("targets", ["q_proj", "v_proj"])):
            with self.subTest(key=key, value=value):
                original = self.config[key]
                self.config[key] = value
                self.write()
                with self.assertRaises(ValueError):
                    self.read()
                self.config[key] = original

    def test_joint_paths_cannot_include_embeddings_heads_or_other_networks(self):
        for name in ("model.layers.0.self_attn.q_proj", "code_predictor.model.layers.4.mlp.down_proj"):
            self.assertTrue(qwen_adapter.joint_module_path(name))
        for name in ("model.codec_embedding", "speaker_encoder.layers.0.self_attn.q_proj",
                     "codec_head", "code_predictor.lm_head.0", "model.layers.0.mlp.unreviewed",
                     "model.layers.0.self_attn.q_proj.base", "model.layers.-1.self_attn.q_proj"):
            self.assertFalse(qwen_adapter.joint_module_path(name))

    def test_rejects_absolute_escaping_and_linked_outside_weights(self):
        outside = self.root.parent / (self.root.name + "-outside.safetensors")
        outside.write_bytes(b"outside")
        self.addCleanup(lambda: outside.unlink(missing_ok=True))
        for name in (str(outside), "../" + outside.name):
            self.config["weights"] = name
            self.write()
            with self.assertRaises(ValueError):
                self.read()
        self.weights.unlink()
        self.weights.symlink_to(outside)
        self.config["weights"] = "adapters/voice.safetensors"
        self.write()
        with self.assertRaises(ValueError):
            self.read()

    def test_manifest_limit_and_safetensors_only(self):
        self.path.write_text(" " * 16385)
        with self.assertRaises(ValueError):
            self.read()
        binary = self.root / "voice.pkl"
        binary.write_bytes(b"pickle must never be loaded")
        self.config["weights"] = "voice.pkl"
        self.write()
        with self.assertRaises(ValueError):
            self.read()


class AdapterWarmupTest(unittest.TestCase):
    def test_adapter_loads_before_all_profiles_and_failure_blocks_readiness(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            audio = root / "reference.wav"
            audio.touch()
            profiles = {name: {"path": str(audio), "text": "reference"}
                        for name in ("neutral", "question", "teasing")}
            mx = types.ModuleType("mlx.core")
            mx.metal = types.SimpleNamespace(is_available=lambda: True)
            mx.gpu = object()
            mx.set_default_device = lambda _: None
            mx.set_cache_limit = lambda _: None
            mlx = types.ModuleType("mlx")
            mlx.core = mx
            utils = types.ModuleType("mlx_audio.tts.utils")
            order = []
            model = object()
            utils.load_model = lambda _: (order.append("base") or model)
            modules = {"mlx": mlx, "mlx.core": mx, "mlx_audio": types.ModuleType("mlx_audio"),
                       "mlx_audio.tts": types.ModuleType("mlx_audio.tts"), "mlx_audio.tts.utils": utils}
            activation = root / "active-adapter.json"
            def load(model_arg, path, model_id, revision):
                self.assertIs(model_arg, model)
                self.assertEqual((path, model_id, revision), (activation, MODEL_ID, MODEL_REVISION))
                order.append("adapter")
                return {"adapter_loaded": True, "adapter_id": "test-voice"}
            with mock.patch.dict(sys.modules, modules), mock.patch.object(server_module, "apply_adapter", side_effect=load), \
                 mock.patch.object(server_module.Engine, "complete", side_effect=lambda _: order.append("warm")):
                engine = server_module.Engine(root, 80, profiles, activation)
                try:
                    engine.loading.result(timeout=2)
                    self.assertTrue(engine.ready)
                    self.assertEqual(order, ["base", "adapter", "warm", "warm", "warm"])
                    self.assertTrue(engine.metrics["adapter_loaded"])
                    self.assertEqual(engine.metrics["adapter_id"], "test-voice")
                finally:
                    engine.executor.shutdown(wait=True)
            with mock.patch.dict(sys.modules, modules), mock.patch.object(server_module, "apply_adapter", side_effect=ValueError("bad adapter")), \
                 mock.patch.object(server_module.Engine, "complete") as complete:
                engine = server_module.Engine(root, 80, profiles, activation)
                try:
                    with self.assertRaisesRegex(ValueError, "bad adapter"):
                        engine.loading.result(timeout=2)
                    self.assertFalse(engine.ready)
                    self.assertFalse(engine.metrics["adapter_loaded"])
                    complete.assert_not_called()
                finally:
                    engine.executor.shutdown(wait=True)


if __name__ == "__main__":
    unittest.main()
