#!/usr/bin/env bash
set -euo pipefail
BASE_DIR=/opt/closure-tts
SERVICE_USER=closure-tts
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${BASE_DIR}/venv-gpu"
mode="${1:---runtime}"

if [[ "$mode" == --check-only ]]; then
  "${VENV_DIR}/bin/python" - "${BASE_DIR}" <<'PY'
import pathlib, sys, torch, yaml
base = pathlib.Path(sys.argv[1])
repo = base / "GPT-SoVITS"
expected = {
    "device": "cuda", "is_half": True, "version": "v2ProPlus",
    "t2s_weights_path": str(repo / "GPT_weights_v2ProPlus/closure_zh-e15.ckpt"),
    "vits_weights_path": str(repo / "SoVITS_weights_v2ProPlus/closure_zh_e8_s304.pth"),
    "bert_base_path": str(repo / "GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large"),
    "cnhuhbert_base_path": str(repo / "GPT_SoVITS/pretrained_models/chinese-hubert-base"),
}
config = yaml.safe_load((base / "tts_infer.gpu.yaml").read_text())
assert config.get("custom") == expected, "Unexpected GPU model configuration"
for key in ("t2s_weights_path", "vits_weights_path", "bert_base_path", "cnhuhbert_base_path"):
    assert pathlib.Path(expected[key]).exists(), f"Missing {key}"
assert torch.__version__.startswith("2.7.1") and torch.version.cuda == "12.6", "Unexpected CUDA PyTorch build"
assert torch.cuda.is_available(), "CUDA GPU/driver unavailable"
assert torch.cuda.get_device_properties(0).total_memory >= 14 * 1024**3, "Expected at least T4-sized GPU memory"
for profile in ("neutral", "question", "cheerful", "teasing", "serious"):
    assert (base / "reference-audio" / f"{profile}.wav").is_file(), f"Missing {profile} reference"
print(f"Closure GPU runtime validated: {torch.cuda.get_device_name(0)}, CUDA {torch.version.cuda}.")
PY
  exit 0
fi

[[ "$EUID" == 0 ]] || { echo "Run with sudo." >&2; exit 1; }
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || exit 1
[[ -r /etc/os-release ]] || exit 1
source /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 ]] || exit 1

if [[ "$mode" == --drivers ]]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  # Ubuntu's signed prebuilt GCP modules keep the VM's security settings intact.
  apt-get install -y --no-install-recommends linux-modules-nvidia-570-gcp nvidia-driver-570
  echo "Driver packages installed. Reboot before the GPU runtime preflight."
  exit 0
fi
[[ "$mode" == --runtime ]] || { echo "Usage: $0 --runtime|--drivers|--check-only" >&2; exit 1; }
[[ -x "${BASE_DIR}/venv/bin/python" ]] || { echo "Existing verified CPU runtime is required." >&2; exit 1; }
for file in tts_infer.gpu.yaml closure-tts.gpu.service serve-tts.py stream_protocol.py reference_cache.py; do
  [[ -s "${SCRIPT_DIR}/$file" ]] || { echo "Missing $file" >&2; exit 1; }
done
if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  [[ ! -e "$VENV_DIR" ]] || { echo "Unexpected existing GPU venv directory." >&2; exit 1; }
  python3.10 -m venv "$VENV_DIR"
fi
"${VENV_DIR}/bin/python" -m pip install --upgrade pip
"${BASE_DIR}/venv/bin/python" -m pip freeze | "${BASE_DIR}/venv/bin/python" -c '
import sys
for line in sys.stdin:
    if line.split("==", 1)[0].lower() not in {"torch", "torchaudio", "torchvision"}:
        print(line.rstrip())
' > "${BASE_DIR}/requirements.gpu-base.txt"
"${VENV_DIR}/bin/python" -m pip install torch==2.7.1 torchvision==0.22.1 torchaudio==2.7.1 \
  --index-url https://download.pytorch.org/whl/cu126
"${VENV_DIR}/bin/python" -m pip install -r "${BASE_DIR}/requirements.gpu-base.txt"
"${VENV_DIR}/bin/python" -m pip check
for file in closure-tts.gpu.service serve-tts.py stream_protocol.py reference_cache.py install-linux-gpu.sh; do
  if [[ "${SCRIPT_DIR}/$file" != "${BASE_DIR}/$file" ]]; then
    install -o root -g "$SERVICE_USER" -m 0640 "${SCRIPT_DIR}/$file" "${BASE_DIR}/$file"
  fi
done
chmod 0750 "${BASE_DIR}/install-linux-gpu.sh"
if [[ "${SCRIPT_DIR}/tts_infer.gpu.yaml" != "${BASE_DIR}/tts_infer.gpu.yaml" ]]; then
  install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0640 \
    "${SCRIPT_DIR}/tts_infer.gpu.yaml" "${BASE_DIR}/tts_infer.gpu.yaml"
fi
"${VENV_DIR}/bin/python" -m pip freeze > "${BASE_DIR}/runtime-linux-gpu.txt"
echo "GPU runtime prepared. The current systemd service has not been switched."
