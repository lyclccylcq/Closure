#!/bin/bash
set -euo pipefail
QWEN_PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
QWEN_RUNTIME="$QWEN_PROJECT_ROOT/.runtime/qwen3-tts"
if [[ ! -x "$QWEN_RUNTIME/venv/bin/python" || ! -f "$QWEN_RUNTIME/model/model.safetensors" ]]; then
  echo "Qwen3-TTS is not installed. See deploy/tts/QWEN.md." >&2
  exit 1
fi
export HF_HOME="$QWEN_RUNTIME/huggingface"
export HF_HUB_OFFLINE=1
export TOKENIZERS_PARALLELISM=false
export PYTHONUNBUFFERED=1
export PYTHONDONTWRITEBYTECODE=1
exec "$QWEN_RUNTIME/venv/bin/python" "$QWEN_PROJECT_ROOT/deploy/tts/serve-qwen.py" "$@"
