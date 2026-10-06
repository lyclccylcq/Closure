# Closure remote TTS

Closure can run its GPT-SoVITS v2ProPlus voice model on a remote Linux CPU or
GPU server and connect through an SSH tunnel. The speech API binds to server
`127.0.0.1:9880`; the desktop uses `127.0.0.1:19880`. Do not expose the API port
to the public internet: the adapter does not provide public-facing authentication.

These are reusable deployment instructions, not a record of a live deployment.
Cloud identifiers, credentials, billing records and local configuration belong
outside the repository. See [RESUME.md](RESUME.md) for the validation checklist
and [latency-report.md](latency-report.md) for historical benchmark results.

## Runtime and model layout

The installers target Ubuntu 22.04 x86_64 with Python 3.10. The example server
root is `/opt/closure-tts`:

```text
/opt/closure-tts/
├── GPT-SoVITS/          # upstream source, pretrained dependencies and trained weights
├── reference-audio/     # reference WAV files
├── venv/               # CPU Python environment
├── venv-gpu/           # optional CUDA Python environment
├── tts_infer.cpu.yaml
├── tts_infer.gpu.yaml
└── serve-tts.py         # Closure streaming adapter
```

The supplied configuration expects `closure_zh-e15.ckpt` and
`closure_zh_e8_s304.pth`, plus the upstream Chinese BERT and HuBERT models. Adapt
the configuration and installer validation together when using different
weights. The inference configurations use CPU/FP32 or CUDA/FP16 respectively.
The GPU environment uses PyTorch 2.7.1 with CUDA 12.6.

Provide the required reference profiles (`neutral`, `question`, `cheerful`,
`teasing`, `serious`) and check their durations before deployment. The model
requires references of 3–10 seconds; an unsuitable optional profile should use
a valid fallback until its reference is replaced.

## Prepare and verify the server

Upload the model, upstream dependencies, references and deployment files to
the server. Preserve a verified backup before changing an existing deployment.
If using `transfer-manifest.py`, verify every source, model and reference file:

```bash
python3 /opt/closure-tts/transfer-manifest.py \
  /opt/closure-tts/transfer-manifest.json --verify /opt/closure-tts \
  --allow-regenerated-bytecode
```

The manifest must be generated for your transfer. The optional bytecode flag
permits regenerated Python bytecode, not changes to models or reference audio.

On the server, `install-linux-cpu.sh` creates the CPU environment and adapter
service. Its `--check-only` mode verifies the prepared runtime. For a GPU
deployment, `install-linux-gpu.sh --drivers` installs driver packages, followed
by a reboot; `--runtime` creates a separate CUDA environment using an existing
verified CPU environment, and `--check-only` validates the GPU and model paths.
Use a driver compatible with the selected GPU and CUDA runtime.

Preparing the GPU environment does not switch the active service. After
preflight passes, install `closure-tts.gpu.service` as `closure-tts.service` and
reload systemd. Keep the CPU environment and configuration available until the
GPU service, data integrity and desktop playback have been verified. An already
verified recovery image may contain the complete runtime; check it before
reinstalling packages.

The service runs as the dedicated `closure-tts` system user rather than root.
Review its filesystem permissions and keep the API loopback-only.

## Start and connect

Set your own deployment identifiers in a local shell session. The placeholders
below must be replaced before running any cloud command:

```bash
export PROJECT_ID="your-project-id"
export ZONE="your-instance-zone"
export INSTANCE="your-instance-name"

: "${PROJECT_ID:?Set PROJECT_ID}"
: "${ZONE:?Set ZONE}"
: "${INSTANCE:?Set INSTANCE}"

gcloud compute instances start "$INSTANCE" \
  --project="$PROJECT_ID" --zone="$ZONE"

# Authenticate and register the server's SSH host key before using the tunnel.
gcloud compute ssh "$INSTANCE" \
  --project="$PROJECT_ID" --zone="$ZONE"
```

Starting an instance incurs cloud charges. Choose your own runtime limit and
budget, and configure an automatic stop policy as a backstop. A resource-pool
exhaustion error indicates unavailable capacity; check the provider's guidance
before retrying or provisioning a replacement.

Set the tunnel's deployment values explicitly:

```bash
export CLOSURE_TTS_PROJECT="$PROJECT_ID"
export CLOSURE_TTS_ZONE="$ZONE"
export CLOSURE_TTS_INSTANCE="$INSTANCE"
bash deploy/tts/connect-remote.sh
```

`connect-remote.sh` reads the instance address and uses the SSH host key
registered by `gcloud`. It keeps SSH in the foreground so Closure can manage
the tunnel's lifetime. It does not start a stopped VM. If port `19880` is already
in use, identify its owner before opening another tunnel; use a separate port
for isolated tests and match the test client's URL to it.

Keep machine-specific settings in ignored `config/local-voice.properties`.
`server.autoStart` starts only the local tunnel. Environment variables override
properties, so check `CLOSURE_GPT_SOVITS_*` overrides when changing deployments.
Restart Closure after rebuilding or changing configuration.

## Streaming adapter and checks

`serve-tts.py` wraps the upstream API, serializes mutable model operations,
avoids reloading unchanged weights, and adds `/closure/health` and
`/closure/tts`. Streaming uses complete-WAV sentence fragments from the upstream
best-quality mode, encoded as `CTTS1` frames with an explicit success/error
terminator. The upstream `/tts` endpoint remains available for compatible
clients and warmup.

`reference_cache.py` provides a bounded cache of reference prompt and speaker
features, invalidated when the reference or model changes.
`verify-reference-cache.py` compares three profiles under both reference miss
and warmed hit conditions. Reference encoding consumes Torch RNG in upstream,
so compare misses with misses and warmed hits with warmed hits.

`benchmark-stream.py` measures the first complete WAV and total synthesis
through the configured tunnel. `RemoteStreamingSmoke.java` exercises the Java
client, ordered fragment playback and temporary WAV cleanup; `--play` includes
native JavaFX playback timing, and an optional fourth argument supplies a
longer text. `RemoteClientSmoke.java` also checks the managed tunnel lifecycle.
See the validation checklist before activating a GPU configuration with
`gptSovits.streaming=true`.

## Shutdown and cost

Stop the cloud instance explicitly when finished:

```bash
gcloud compute instances stop "$INSTANCE" \
  --project="$PROJECT_ID" --zone="$ZONE"
```

Compute, disks, recovery images, IP addresses and network transfer have separate
costs. Stopping an instance does not remove persistent storage charges. Check
current prices for your region and account, and store billing notes privately.

- [GPU VM pricing](https://cloud.google.com/products/compute/pricing/accelerator-optimized)
- [Disk and image pricing](https://cloud.google.com/compute/disks-image-pricing)
- [IP and network pricing](https://cloud.google.com/vpc/network-pricing)
