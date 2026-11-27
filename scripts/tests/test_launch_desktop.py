from pathlib import Path
import unittest
import contextlib
import io
from unittest.mock import patch

import sys

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import launch_desktop


class DesktopLauncherTest(unittest.TestCase):
    def test_checkout_and_runtime_are_independent_argv_values(self):
        command = launch_desktop.desktop_command(Path("/checkout with spaces"),
                                                Path("/runtime with spaces"), offline=True)
        self.assertEqual(command, ["/checkout with spaces/mvnw", "-q", "-o",
                                  "-Djavafx.workingDirectory=/runtime with spaces", "compile", "javafx:run"])

    def test_startup_check_uses_the_existing_smoke_flag(self):
        command = launch_desktop.desktop_command(Path("/project"), Path("/runtime"), smoke=True)
        self.assertIn("-Dclosure.smokeTest=true", command)
        self.assertNotIn("-o", command)

    def test_direct_workbench_option_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            launch_desktop.main(["--workbench"])
        self.assertEqual(error.exception.code, 2)

    def test_desktop_command_does_not_override_workbench_startup(self):
        command = launch_desktop.desktop_command(Path("/project"), Path("/runtime"))
        self.assertFalse(any("closure.openWorkbench" in argument for argument in command))

    def test_pet_only_cli_reaches_application(self):
        with patch.object(launch_desktop, "java_home", return_value=Path("/jdk21")), \
                patch.object(launch_desktop.subprocess, "call", return_value=0) as call:
            self.assertEqual(launch_desktop.main(["--runtime-directory", str(SCRIPTS), "--pet-only"]), 0)
        self.assertFalse(any("closure.openWorkbench" in argument for argument in call.call_args.args[0]))

    def test_main_launches_current_sources_from_selected_runtime(self):
        with patch.object(launch_desktop, "java_home", return_value=Path("/jdk21")), \
                patch.object(launch_desktop.subprocess, "call", return_value=0) as call:
            self.assertEqual(launch_desktop.main(["--runtime-directory", str(SCRIPTS)]), 0)
        positional, named = call.call_args
        self.assertEqual(positional[0][0], str(SCRIPTS.parent / "mvnw"))
        self.assertIn("-Djavafx.workingDirectory=" + str(SCRIPTS), positional[0])
        self.assertEqual(named["cwd"], SCRIPTS.parent)
        self.assertEqual(named["env"]["JAVA_HOME"], "/jdk21")
