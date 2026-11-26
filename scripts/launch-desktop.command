#!/bin/bash
set -euo pipefail
closure_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
exec python3 "$closure_root/scripts/launch_desktop.py" "$@"
