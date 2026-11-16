#!/bin/bash
set -euo pipefail
QWEN_PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
QWEN_RUNTIME="$QWEN_PROJECT_ROOT/.runtime/qwen3-tts"
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo "This runtime requires macOS on Apple Silicon." >&2
  exit 1
fi
if [[ ! -x "$QWEN_RUNTIME/venv/bin/python" ]]; then
  QWEN_INSTALL_PYTHON="${CLOSURE_QWEN_PYTHON:-}"
  if [[ -z "$QWEN_INSTALL_PYTHON" ]]; then
    for candidate in python3.12 python3.13 python3.11; do
      if command -v "$candidate" >/dev/null 2>&1; then
        QWEN_INSTALL_PYTHON="$(command -v "$candidate")"
        break
      fi
    done
  fi
  if [[ -z "$QWEN_INSTALL_PYTHON" ]]; then
    echo "Set CLOSURE_QWEN_PYTHON to an ARM64 Python 3.11–3.13 executable." >&2
    exit 1
  fi
  mkdir -p "$QWEN_RUNTIME"
  "$QWEN_INSTALL_PYTHON" -m venv "$QWEN_RUNTIME/venv"
fi
export HF_HOME="$QWEN_RUNTIME/huggingface"
"$QWEN_RUNTIME/venv/bin/python" -m pip install --cache-dir "$QWEN_RUNTIME/pip-cache" \
  -r "$QWEN_PROJECT_ROOT/deploy/tts/qwen-requirements.txt"
"$QWEN_RUNTIME/venv/bin/python" "$QWEN_PROJECT_ROOT/deploy/tts/prepare-qwen.py" --download "$@"
