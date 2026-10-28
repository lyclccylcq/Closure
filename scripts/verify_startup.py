#!/usr/bin/env python3
"""Verify Closure's real desktop window/model startup without starting services.

Requires JDK 21 and a desktop display, or xvfb-run on Linux. The opt-in application
smoke mode omits voice, HTTP, reminders and user configuration. This verifies
window/resource initialization, not AI, weather, TTS or audio integration.
"""
import argparse
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
from pathlib import Path

MARKER = "CLOSURE_STARTUP_SMOKE_PASS"


class StartupError(RuntimeError):
    pass


def java_major(executable):
    result = subprocess.run([str(executable), "-version"], capture_output=True, text=True, timeout=10)
    match = re.search(r'(?:openjdk|java) version "([0-9]+)', result.stderr + result.stdout)
    return int(match[1]) if result.returncode == 0 and match else None


def java_home(explicit=None):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    else:
        if os.environ.get("JAVA_HOME"):
            candidates.append(Path(os.environ["JAVA_HOME"]))
        if platform.system() == "Darwin":
            result = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                candidates.append(Path(result.stdout.strip()))
        if shutil.which("java"):
            candidates.append(Path(shutil.which("java")).resolve().parent.parent)
    for home in candidates:
        executable = home / "bin" / ("java.exe" if os.name == "nt" else "java")
        if executable.is_file() and java_major(executable) == 21:
            return home.resolve()
    raise StartupError("JDK 21 is required; set JAVA_HOME or pass --java-home to its installation")


def smoke_command(repo, offline=False, system=None, environ=None, which=shutil.which):
    system = system or platform.system()
    environ = os.environ if environ is None else environ
    if system not in ("Darwin", "Linux"):
        raise StartupError("Startup verifier currently supports macOS and Linux")
    command = [str(repo / "mvnw"), "-B"]
    if offline:
        command.append("-o")
    command += ["-DskipTests", "-Dclosure.smokeTest=true", "compile", "javafx:run"]
    if system == "Linux" and not environ.get("DISPLAY"):
        xvfb = which("xvfb-run")
        if not xvfb:
            raise StartupError("Linux startup verification needs DISPLAY or xvfb-run (install xvfb and xauth)")
        command = [xvfb, "--auto-servernum", "--server-args=-screen 0 1280x800x24 -nolisten tcp", *command]
    return command


def execute(command, cwd, env, timeout):
    process = subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt) as failure:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.communicate(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
        raise StartupError("Desktop startup timed out or was interrupted; process tree stopped") from failure
    text = output.decode(errors="replace")
    # A live process or Maven's own BUILD SUCCESS cannot substitute for the app's
    # marker emitted only after the actual window and image have initialized.
    if process.returncode != 0 or MARKER not in {line.strip() for line in text.splitlines()}:
        raise StartupError("Desktop startup did not verify (exit=" + str(process.returncode) + ")\n" + text[-10000:])
    return text


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home")
    parser.add_argument("--offline", action="store_true", help="use only cached Maven dependencies")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--log", help="optional startup output file")
    args = parser.parse_args(argv)
    try:
        if not 0 < args.timeout <= 600:
            raise StartupError("timeout must be greater than 0 and no more than 600 seconds")
        repo = Path(__file__).resolve().parents[1]
        selected_home = java_home(args.java_home)
        env = {key: value for key, value in os.environ.items()
               if not re.search(r'TOKEN|SECRET|PASSWORD|API_KEY|CREDENTIAL', key, re.I)
               and not key.startswith(("CLOSURE_", "ANTHROPIC_", "OPENAI_", "AWS_", "AZURE_", "GOOGLE_"))}
        env["JAVA_HOME"] = str(selected_home)
        env["PATH"] = str(selected_home / "bin") + os.pathsep + env.get("PATH", "")
        text = execute(smoke_command(repo, args.offline, environ=env), repo, env, args.timeout)
        if args.log:
            Path(args.log).write_text(text)
        print(json.dumps({"status": "PASS", "marker": MARKER, "java_home": str(selected_home),
                          "scope": "Real window/model startup; external services and user reminders skipped"}))
        return 0
    except (StartupError, OSError, subprocess.SubprocessError) as failure:
        print(json.dumps({"status": "FAIL", "reason": str(failure)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
