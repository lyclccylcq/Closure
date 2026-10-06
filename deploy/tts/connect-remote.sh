#!/usr/bin/env bash
# Keep the SSH tunnel in the foreground so Closure can own its lifetime.
set -euo pipefail

TTS_PROJECT="${CLOSURE_TTS_PROJECT:?Set CLOSURE_TTS_PROJECT to your Google Cloud project ID}"
TTS_ZONE="${CLOSURE_TTS_ZONE:?Set CLOSURE_TTS_ZONE to your VM zone}"
TTS_INSTANCE="${CLOSURE_TTS_INSTANCE:?Set CLOSURE_TTS_INSTANCE to your VM name}"
if command -v gcloud >/dev/null 2>&1; then
  TTS_GCLOUD="$(command -v gcloud)"
elif [[ -x /opt/homebrew/bin/gcloud ]]; then
  TTS_GCLOUD=/opt/homebrew/bin/gcloud
else
  printf 'Google Cloud CLI is required to connect remote TTS.\n' >&2
  exit 1
fi

read -r TTS_STATUS TTS_INSTANCE_ID TTS_ADDRESS <<< "$("${TTS_GCLOUD}" compute instances describe "${TTS_INSTANCE}" \
  --project="${TTS_PROJECT}" --zone="${TTS_ZONE}" \
  --format='value(status,id,networkInterfaces[0].accessConfigs[0].natIP)')"
if [[ "${TTS_STATUS}" != RUNNING ]]; then
  printf 'TTS server is %s. Start it explicitly with:\n' "${TTS_STATUS}" >&2
  printf 'gcloud compute instances start %s --project=%s --zone=%s\n' \
    "${TTS_INSTANCE}" "${TTS_PROJECT}" "${TTS_ZONE}" >&2
  exit 1
fi

TTS_SSH_KEY="${CLOSURE_TTS_SSH_KEY:-${HOME}/.ssh/google_compute_engine}"
TTS_KNOWN_HOSTS="${CLOSURE_TTS_KNOWN_HOSTS:-${HOME}/.ssh/google_compute_known_hosts}"
TTS_SSH_USER="${CLOSURE_TTS_SSH_USER:-$(id -un)}"
[[ -n "${TTS_INSTANCE_ID}" && -n "${TTS_ADDRESS}" && -r "${TTS_SSH_KEY}" && -r "${TTS_KNOWN_HOSTS}" ]] || {
  printf 'TTS address or previously registered Google Cloud SSH credentials are missing.\n' >&2
  exit 1
}
# Exec SSH directly, so Closure can close the tunnel without an orphaned CLI child.
# Use the host key recorded during the authenticated gcloud setup connection.
exec /usr/bin/ssh -i "${TTS_SSH_KEY}" \
  -o "HostKeyAlias=compute.${TTS_INSTANCE_ID}" -o "UserKnownHostsFile=${TTS_KNOWN_HOSTS}" \
  -o StrictHostKeyChecking=yes -o CheckHostIP=no -o BatchMode=yes \
  -N -L 127.0.0.1:19880:127.0.0.1:9880 \
  -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  "${TTS_SSH_USER}@${TTS_ADDRESS}"
