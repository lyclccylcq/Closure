#!/usr/bin/env python3
"""Launch this checkout's desktop app with an explicit local runtime directory."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

from verify_startup import StartupError, java_home


def desktop_command(checkout, runtime, offline=False, smoke=False):
    command = [str(checkout / "mvnw"), "-q"]
    if offline:
        command.append("-o")
    command.append("-Djavafx.workingDirectory=" + str(runtime))
    if smoke:
        command.append("-Dclosure.smokeTest=true")
    command.extend(["compile", "javafx:run"])
    return command


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-directory", type=Path,
                        default=Path(os.environ.get("CLOSURE_RUNTIME_DIRECTORY", Path(__file__).resolve().parents[1])))
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--smoke", action="store_true", help="run the existing window startup self-check")
    parser.add_argument("--pet-only", action="store_true", help="compatibility option; startup always shows only the pet")
    args = parser.parse_args(argv)
    checkout = Path(__file__).resolve().parents[1]
    try:
        runtime = args.runtime_directory.resolve(strict=True)
        if not runtime.is_dir():
            raise StartupError("Runtime directory must be a directory")
        environment = dict(os.environ)
        environment["JAVA_HOME"] = str(java_home())
        print(f"CLOSURE_DESKTOP_LAUNCH source={checkout} runtime={runtime}", flush=True)
        return subprocess.call(desktop_command(checkout, runtime, args.offline, args.smoke),
                               cwd=checkout, env=environment)
    except (OSError, StartupError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
