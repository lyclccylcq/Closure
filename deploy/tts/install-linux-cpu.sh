#!/usr/bin/env bash
# Run explicitly on the uploaded Ubuntu 22.04 x86_64 server with sudo.
# Adapted from install.sh at GPT-SoVITS d523079fc05d9a8028d6085bffe4a2757c32abb6.
# This installs server packages, not a copy of the macOS Conda environment.
set -Eeuo pipefail

BASE_DIR=/opt/closure-tts
REPO_DIR="${BASE_DIR}/GPT-SoVITS"
VENV_DIR="${BASE_DIR}/venv"
REFERENCE_DIR="${BASE_DIR}/reference-audio"
CONFIG_PATH="${BASE_DIR}/tts_infer.cpu.yaml"
SERVICE_USER=closure-tts
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

validate_uploaded_files() {
  local relative_file audio_profile
  local -a missing=()
  local -a required_files=(
    api_v2.py requirements.txt extra-req.txt
    GPT_weights_v2ProPlus/closure_zh-e15.ckpt
    SoVITS_weights_v2ProPlus/closure_zh_e8_s304.pth
    GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large/config.json
    GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large/tokenizer.json
    GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large/pytorch_model.bin
    GPT_SoVITS/pretrained_models/chinese-hubert-base/config.json
    GPT_SoVITS/pretrained_models/chinese-hubert-base/preprocessor_config.json
    GPT_SoVITS/pretrained_models/chinese-hubert-base/pytorch_model.bin
    GPT_SoVITS/pretrained_models/sv/pretrained_eres2netv2w24s4ep4.ckpt
    GPT_SoVITS/text/G2PWModel/config.py
    GPT_SoVITS/text/G2PWModel/g2pW.onnx
    GPT_SoVITS/text/G2PWModel/POLYPHONIC_CHARS.txt
    GPT_SoVITS/text/G2PWModel/MONOPHONIC_CHARS.txt
    GPT_SoVITS/text/G2PWModel/bopomofo_to_pinyin_wo_tune_dict.json
    GPT_SoVITS/text/G2PWModel/char_bopomofo_dict.json
  )
  for relative_file in "${required_files[@]}"; do
    [[ -s "${REPO_DIR}/${relative_file}" ]] || missing+=("${REPO_DIR}/${relative_file}")
  done
  for audio_profile in neutral question cheerful teasing serious; do
    [[ -s "${REFERENCE_DIR}/${audio_profile}.wav" ]] || missing+=("${REFERENCE_DIR}/${audio_profile}.wav")
  done
  if (( ${#missing[@]} > 0 )); then
    printf 'Missing or empty required files:\n' >&2
    printf '  %s\n' "${missing[@]}" >&2
    fail 'Complete and verify the upload before installing or starting the service.'
  fi
  [[ "$(head -c 2 "${REPO_DIR}/SoVITS_weights_v2ProPlus/closure_zh_e8_s304.pth")" == 06 ]] ||
    fail 'The selected SoVITS file does not have the expected v2ProPlus header.'
}

validate_runtime() {
  validate_uploaded_files
  [[ -s "${CONFIG_PATH}" ]] || fail "Missing ${CONFIG_PATH}"
  [[ -x "${VENV_DIR}/bin/python" ]] || fail 'The Linux virtual environment is not installed.'
  for adapter_file in serve-tts.py stream_protocol.py reference_cache.py; do
    [[ -s "${BASE_DIR}/${adapter_file}" ]] || fail "Missing streaming adapter ${adapter_file}"
  done
  "${VENV_DIR}/bin/python" - "${CONFIG_PATH}" "${REPO_DIR}" "${REFERENCE_DIR}" <<'PY'
import pathlib
import sys
import wave
import yaml

config_path, repo_path, reference_path = map(pathlib.Path, sys.argv[1:])
with config_path.open(encoding="utf-8") as stream:
    config = yaml.safe_load(stream)
expected = {
    "device": "cpu",
    "is_half": False,
    "version": "v2ProPlus",
    "t2s_weights_path": str(repo_path / "GPT_weights_v2ProPlus/closure_zh-e15.ckpt"),
    "vits_weights_path": str(repo_path / "SoVITS_weights_v2ProPlus/closure_zh_e8_s304.pth"),
    "bert_base_path": str(repo_path / "GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large"),
    "cnhuhbert_base_path": str(repo_path / "GPT_SoVITS/pretrained_models/chinese-hubert-base"),
}
if not isinstance(config, dict) or config.get("custom") != expected:
    raise SystemExit("Refusing startup: custom inference settings differ from the selected Closure CPU model.")
for profile in ("neutral", "question", "cheerful", "teasing", "serious"):
    audio_path = reference_path / f"{profile}.wav"
    with wave.open(str(audio_path), "rb") as audio:
        duration = audio.getnframes() / audio.getframerate()
        if not 3 <= duration <= 10:
            message = f"{audio_path} must be 3–10 seconds for inference (got {duration:.2f})."
            if profile == "neutral":
                raise SystemExit(f"Refusing startup: {message}")
            print(f"Warning: {message} Replace this reference before using its voice profile.", file=sys.stderr)
print("Closure custom weights, CPU configuration, five reference files, and neutral reference duration validated.")
PY
}

if [[ "${1:-}" == --check-only && $# == 1 ]]; then
  validate_runtime
  exit 0
fi
[[ $# == 0 ]] || fail 'Usage: sudo bash install-linux-cpu.sh | install-linux-cpu.sh --check-only'
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || fail 'This installer targets Ubuntu 22.04 x86_64 only.'
[[ $EUID == 0 ]] || fail 'Run this installer with sudo on the server.'
[[ -r /etc/os-release ]] || fail 'Cannot identify the server operating system.'
# shellcheck disable=SC1091
source /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 22.04 ]] || fail 'This installer targets Ubuntu 22.04 only.'
[[ -s "${SCRIPT_DIR}/tts_infer.cpu.yaml" && -s "${SCRIPT_DIR}/closure-tts.service" ]] ||
  fail 'Keep the YAML and systemd service next to this installer.'
for adapter_file in serve-tts.py stream_protocol.py reference_cache.py; do
  [[ -s "${SCRIPT_DIR}/${adapter_file}" ]] || fail "Missing ${adapter_file}"
done
# Required trained weights and references are checked before any package/user setup.
validate_uploaded_files
if systemctl is-active --quiet closure-tts.service; then
  fail 'Stop closure-tts.service before updating its environment.'
fi
if [[ -e "${VENV_DIR}" && ! -x "${VENV_DIR}/bin/python" ]]; then
  fail "${VENV_DIR} exists but is not a Linux virtual environment; move it aside first."
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  python3.10 python3.10-venv python3.10-dev build-essential cmake pkg-config \
  ffmpeg libsndfile1 libmecab-dev ca-certificates

getent group "${SERVICE_USER}" >/dev/null || groupadd --system "${SERVICE_USER}"
if ! id "${SERVICE_USER}" >/dev/null 2>&1; then
  useradd --system --gid "${SERVICE_USER}" --home-dir /var/lib/closure-tts \
    --shell /usr/sbin/nologin "${SERVICE_USER}"
fi
[[ "$(id -u "${SERVICE_USER}")" != 0 ]] || fail 'The service account must not be root.'
install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_USER}" \
  /var/lib/closure-tts /var/lib/closure-tts/nltk_data /var/cache/closure-tts
chown "root:${SERVICE_USER}" "${BASE_DIR}"
chmod 0750 "${BASE_DIR}"

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  python3.10 -m venv "${VENV_DIR}"
fi
"${VENV_DIR}/bin/python" -c 'import platform, sys; assert sys.version_info[:2] == (3, 10) and platform.system() == "Linux", "Expected a fresh Linux Python 3.10 venv"'
"${VENV_DIR}/bin/python" -m pip install --upgrade pip setuptools wheel

# Official matched CPU wheels; torch >= 2.6 also supports Transformers' .bin loader.
# https://pytorch.org/get-started/previous-versions/
"${VENV_DIR}/bin/python" -m pip install \
  torch==2.7.1 torchvision==0.22.1 torchaudio==2.7.1 \
  --index-url https://download.pytorch.org/whl/cpu
cat > "${BASE_DIR}/constraints-cpu.txt" <<'CONSTRAINTS'
torch==2.7.1+cpu
torchvision==0.22.1+cpu
torchaudio==2.7.1+cpu
numpy==1.26.4
transformers==4.50.0
librosa==0.10.2
onnxruntime==1.23.2
pytorch-lightning==2.6.5
torchmetrics==1.5.0
peft==0.17.1
pydantic==2.10.6
fastapi==0.115.2
gradio==4.44.1
gradio-client==1.3.0
huggingface-hub==0.36.2
tokenizers==0.21.4
CONSTRAINTS

# Keep the uploaded checkout's requirements and replace its x86 GPU ONNX package.
"${VENV_DIR}/bin/python" - "${REPO_DIR}/requirements.txt" "${BASE_DIR}/requirements-cpu.txt" <<'PY'
from pathlib import Path
import sys
source, destination = map(Path, sys.argv[1:])
lines = [line for line in source.read_text().splitlines() if not line.strip().startswith("onnxruntime")]
destination.write_text("\n".join(lines + ["onnxruntime==1.23.2"]) + "\n")
PY
"${VENV_DIR}/bin/python" -m pip install \
  -c "${BASE_DIR}/constraints-cpu.txt" -r "${BASE_DIR}/requirements-cpu.txt"
"${VENV_DIR}/bin/python" -m pip install \
  -c "${BASE_DIR}/constraints-cpu.txt" -r "${REPO_DIR}/extra-req.txt"
"${VENV_DIR}/bin/python" -m pip check

# g2p_en/NLTK needs these small dictionaries for text containing English.
chown -R "${SERVICE_USER}:${SERVICE_USER}" /var/lib/closure-tts /var/cache/closure-tts
chmod -R u+rwX,go-w /var/lib/closure-tts/nltk_data
runuser -u "${SERVICE_USER}" -- "${VENV_DIR}/bin/python" - <<'PY'
import nltk
for dataset in ("cmudict", "averaged_perceptron_tagger", "averaged_perceptron_tagger_eng"):
    if not nltk.download(dataset, download_dir="/var/lib/closure-tts/nltk_data", raise_on_error=True):
        raise SystemExit(f"Could not prepare NLTK resource: {dataset}")
PY
chown -R "root:${SERVICE_USER}" "${REPO_DIR}" "${REFERENCE_DIR}"
chmod -R g+rX,o-rwx "${REPO_DIR}" "${REFERENCE_DIR}"
# Pronunciation modules create/update caches alongside their source files.
chown -R "${SERVICE_USER}:${SERVICE_USER}" "${REPO_DIR}/GPT_SoVITS/text"
chmod -R u+rwX "${REPO_DIR}/GPT_SoVITS/text"

if [[ "$(readlink -f "${SCRIPT_DIR}/tts_infer.cpu.yaml")" != "${CONFIG_PATH}" ]]; then
  install -m 0640 "${SCRIPT_DIR}/tts_infer.cpu.yaml" "${CONFIG_PATH}"
fi
chown "${SERVICE_USER}:${SERVICE_USER}" "${CONFIG_PATH}"
chmod 0640 "${CONFIG_PATH}"
if [[ "$(readlink -f "${BASH_SOURCE[0]}")" != "${BASE_DIR}/install-linux-cpu.sh" ]]; then
  install -m 0755 "${BASH_SOURCE[0]}" "${BASE_DIR}/install-linux-cpu.sh"
fi
chown root:root "${BASE_DIR}/install-linux-cpu.sh"
chmod 0755 "${BASE_DIR}/install-linux-cpu.sh"
for adapter_file in serve-tts.py stream_protocol.py reference_cache.py; do
  if [[ "$(readlink -f "${SCRIPT_DIR}/${adapter_file}")" != "${BASE_DIR}/${adapter_file}" ]]; then
    install -o root -g "${SERVICE_USER}" -m 0640 "${SCRIPT_DIR}/${adapter_file}" "${BASE_DIR}/${adapter_file}"
  fi
done
install -m 0644 "${SCRIPT_DIR}/closure-tts.service" /etc/systemd/system/closure-tts.service
runuser -u "${SERVICE_USER}" -- "${BASE_DIR}/install-linux-cpu.sh" --check-only
systemctl daemon-reload
printf '\nInstalled. Start explicitly with: sudo systemctl enable --now closure-tts.service\n'
printf 'The API listens only on 127.0.0.1:9880; use the SSH tunnel described in README.md.\n'
