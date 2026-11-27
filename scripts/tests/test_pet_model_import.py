import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import import_pet_model as importer
from pet_smoke_assets import fixture_assets


class PetImportTest(unittest.TestCase):
    def test_rejects_wrong_content_before_running_a_decoder(self):
        with tempfile.TemporaryDirectory() as directory:
            video = Path(directory) / "idle.webm"
            video.write_bytes(b"unexpected video")
            with patch.object(importer.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "Unexpected source content"):
                    importer.inspect(video, "ffprobe")
                run.assert_not_called()

    def test_missing_clip_preserves_an_existing_install(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"; source.mkdir()
            output = Path(directory) / "output"; output.mkdir()
            manifest = output / "model.properties"; manifest.write_text("existing")
            with patch.object(importer.shutil, "which", return_value="tool"):
                with self.assertRaisesRegex(ValueError, "Missing or invalid"):
                    importer.import_model(source, output)
            self.assertEqual(manifest.read_text(), "existing")
            self.assertEqual(list(output.iterdir()), [manifest])

    def test_decoder_failure_does_not_replace_the_install(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"; source.mkdir()
            for name in importer.BLOBS: (source / (name + ".webm")).write_bytes(b"test")
            output = Path(directory) / "output"; output.mkdir()
            manifest = output / "model.properties"; manifest.write_text("existing")
            with patch.object(importer.shutil, "which", return_value="tool"), \
                    patch.object(importer, "inspect", return_value={"frames": 1}), \
                    patch.object(importer.subprocess, "run", side_effect=OSError("decoder failed")):
                with self.assertRaisesRegex(OSError, "decoder failed"):
                    importer.import_model(source, output)
            self.assertEqual(manifest.read_text(), "existing")
            self.assertEqual(list(output.iterdir()), [manifest])

    def test_fixture_is_explicitly_synthetic_and_has_no_remote_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture_assets(directory)
            manifest = (Path(directory) / "model.properties").read_text()
            self.assertIn("synthetic=true", manifest)
            self.assertIn("fps=30", manifest)
            self.assertNotIn("https://", manifest)
            self.assertEqual(len(list(Path(directory).glob("*.png"))), 1)
