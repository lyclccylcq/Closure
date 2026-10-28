"""Observable startup verifier guards, without opening windows in unit tests."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

MODULE = Path(__file__).resolve().parents[1] / "verify_startup.py"
spec = importlib.util.spec_from_file_location("verify_startup", MODULE)
startup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(startup)


class StartupTests(unittest.TestCase):
    def test_requires_app_marker_and_successful_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            for body in ("print('BUILD SUCCESS')", "print('prefix CLOSURE_STARTUP_SMOKE_PASS suffix')",
                         "print('CLOSURE_STARTUP_SMOKE_PASS'); raise SystemExit(2)"):
                with self.subTest(body=body), self.assertRaises(startup.StartupError):
                    startup.execute([sys.executable, "-c", body], directory, os.environ.copy(), 3)
            result = startup.execute([sys.executable, "-c", "print('CLOSURE_STARTUP_SMOKE_PASS')"],
                                     directory, os.environ.copy(), 3)
            self.assertIn(startup.MARKER, result)

    def test_timeout_stops_the_process_group(self):
        with tempfile.TemporaryDirectory() as directory:
            start = time.monotonic()
            with self.assertRaisesRegex(startup.StartupError, "process tree stopped"):
                startup.execute([sys.executable, "-c", "import time; time.sleep(30)"],
                                directory, os.environ.copy(), 0.1)
            self.assertLess(time.monotonic() - start, 4)

    def test_linux_without_display_requires_xvfb(self):
        with self.assertRaisesRegex(startup.StartupError, "DISPLAY or xvfb-run"):
            startup.smoke_command(Path("/repo"), system="Linux", environ={}, which=lambda _: None)
        command = startup.smoke_command(Path("/repo"), system="Linux", environ={},
                                        which=lambda _: "/usr/bin/xvfb-run")
        self.assertEqual(command[0], "/usr/bin/xvfb-run")
        self.assertIn("-Dclosure.smokeTest=true", command)
        self.assertEqual(command[-2:], ["compile", "javafx:run"])
        command = startup.smoke_command(Path("/repo"), offline=True, system="Linux", environ={"DISPLAY": ":1"})
        self.assertEqual(command[0], "/repo/mvnw")
        self.assertIn("-o", command)

    def test_explicit_jdk_must_be_version_21(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bin" / "java"
            path.parent.mkdir()
            path.touch()
            with mock.patch.object(startup, "java_major", return_value=25):
                with self.assertRaisesRegex(startup.StartupError, "JDK 21"):
                    startup.java_home(directory)
            with mock.patch.object(startup, "java_major", return_value=21):
                self.assertEqual(startup.java_home(directory), Path(directory).resolve())


if __name__ == "__main__":
    unittest.main()
