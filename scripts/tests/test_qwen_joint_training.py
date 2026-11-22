"""Dataset separation and deterministic sampling; no MLX, audio or model in CI."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

TTS = Path(__file__).resolve().parents[2] / "deploy" / "tts"
sys.path.insert(0, str(TTS))
try:
    import qwen_joint_training as training
    from qwen_runtime import MODEL_ID, MODEL_REVISION
finally:
    sys.path.pop(0)


class JointTrainingContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / "features").mkdir()
        (self.root / "codecs").mkdir()
        self.rows = []
        for ident, split in (("one", "train"), ("two", "val")):
            feature = self.root / "features" / (ident + ".npz")
            feature.write_bytes(ident.encode())
            (self.root / "codecs" / (ident + ".npy")).write_bytes(ident.encode())
            self.rows.append({"id": ident, "split": split, "frames": 10, "feature_path": str(feature)})

    def read(self):
        (self.root / "feature-index.json").write_text(json.dumps(self.rows))
        return training.load_index(self.root)

    def test_existing_source_split_is_preserved(self):
        self.assertEqual(self.read(), self.rows)
        self.rows[1]["split"] = "train"
        with self.assertRaisesRegex(ValueError, "held-out"):
            self.read()

    def test_duplicate_ids_features_and_codec_content_cannot_cross_splits(self):
        original = self.rows[1].copy()
        self.rows[1]["id"] = "one"
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.read()
        self.rows[1] = original.copy()
        self.rows[1]["feature_path"] = self.rows[0]["feature_path"]
        with self.assertRaisesRegex(ValueError, "both splits"):
            self.read()
        self.rows[1] = original
        (self.root / "codecs/two.npy").write_bytes((self.root / "codecs/one.npy").read_bytes())
        with self.assertRaisesRegex(ValueError, "leaks"):
            self.read()

    def test_prepared_input_cannot_escape_through_a_symlink(self):
        outside = self.root / "outside.npz"
        outside.write_bytes(b"outside")
        feature = Path(self.rows[0]["feature_path"])
        feature.unlink()
        feature.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "escapes"):
            self.read()

    def test_frame_sampling_is_bounded_reproducible_and_retains_boundaries_without_eos(self):
        selected = training.frame_indices(300, 16, 42)
        self.assertEqual(selected, training.frame_indices(300, 16, 42))
        self.assertNotEqual(selected, training.frame_indices(300, 16, 43))
        self.assertEqual((len(selected), selected[0], selected[-1]), (16, 0, 299))
        self.assertEqual(len(set(selected)), 16)
        self.assertEqual(training.frame_indices(1, 16, 42), [0])
        self.assertEqual(training.frame_indices(3, 16, 42), [0, 1, 2])
        for frames, budget in [(0, 16), (True, 16), (20, 1), (20, 0)]:
            with self.assertRaises(ValueError):
                training.frame_indices(frames, budget, 42)

    def test_base_identity_and_source_path_must_match_pinned_preparation(self):
        model = self.root / "model"
        model.mkdir()
        marker = self.root / "model-source.json"
        source = {"repo_id": MODEL_ID, "revision": MODEL_REVISION, "path": str(model)}
        marker.write_text(json.dumps(source))
        self.assertEqual(len(training.validate_base(model)), 64)
        source["revision"] = "other-revision"
        marker.write_text(json.dumps(source))
        with self.assertRaisesRegex(ValueError, "pinned"):
            training.validate_base(model)


if __name__ == "__main__":
    unittest.main()
