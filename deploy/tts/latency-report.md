# TTS latency benchmark

These historical development measurements describe an anonymized test
environment, not the state of a live deployment. The performance goal was
roughly 1–2 seconds from submitting a complete reply to available speech after
model warmup. Server boot, cold model startup and native audio playback are
separate measurements.

## Test environment and method

The GPU environment used Ubuntu 22.04, an NVIDIA L4, 4 vCPU, 16 GiB RAM,
GPT-SoVITS v2ProPlus weights and reference audio, PyTorch 2.7.1 with CUDA 12.6,
and FP16 inference. Requests passed through a desktop SSH tunnel.

The timer starts when the TTS request is sent. “First WAV” means the first
complete WAV fragment is available to play; it does not measure when the
sound device produces audio. Complete-WAV sentence fragments use the upstream
best-quality `return_fragment` mode and are played in order by the client.
The client starts synthesis when the final reply arrives, alongside the text
animation.

## GPU measurements

| Text / profile | First WAV | Total synthesis | Audio duration |
| --- | ---: | ---: | ---: |
| 16 characters, neutral, first recorded request | 2.134 s | 2.144 s | 2.86 s |
| 16 characters, neutral, repeated requests | 1.738 s | 1.929 s | 2.86 s |
| 16 characters, question | 2.362 s | 2.373 s | 3.88 s |
| 18 characters, teasing, 2 fragments | 1.239 s | 2.590 s | 3.58 s |
| 95 characters, neutral, 6 fragments | 2.169 s | 7.692 s | 17.84 s |

First audio availability ranged from about 1.2–2.4 seconds in these cases; some
requests exceeded the 2-second goal. The same long reply took 50.8 seconds for
complete generation on the CPU baseline. These few samples are not a latency
guarantee, a statistically characterized distribution or a native playback
benchmark. Network location, text, reference state, hardware and runtime
versions can change the result. The raw local development samples are not
included in this repository; reproduce measurements with
`benchmark-stream.py` in your own environment.

## Reference cache comparison

The bounded reference cache stores prompt and speaker features, with invalidation
keys for the reference file, model and language. Cache misses rebuild upstream
reference state, including updates to audio at an unchanged path.

Upstream reference encoding consumes Torch RNG; its own warmed cache skips
that work. Consequently, cold and warmed references can produce different
audio lengths even before adding Closure's cache. Compare miss against miss
and warmed hit against warmed hit when checking for regressions.

CPU comparisons for neutral, question and teasing passed all six miss/hit
cases with byte-identical PCM. In earlier GPU samples, steady-state neutral,
teasing and the long reply were identical before and after caching. The question
sample had equal length and a 0.356% relative PCM RMS difference. A controlled
GPU comparison under matched reference conditions was still pending when these
results were recorded; the historical samples do not establish complete GPU
cache equivalence.

The recorded 53 focused Java tests covered early fragment delivery, playback
order, cancellation, stream errors, truncation, whole-stream timeouts, remote
reference paths and temporary WAV cleanup. They do not replace a native
playback check on the release environment.

## CPU diagnostics

The CPU baseline used an Intel Xeon at 2.20 GHz, four logical CPUs across two
physical cores, no AVX-512, the same weights and FP32 inference. A warmed
16-character reply took 7.4–8.8 seconds to produce the complete WAV.

| Inference threads | Complete WAV latency |
| --- | ---: |
| 1 | 12.48–12.88 s |
| 2 | 7.60–7.62 s |
| 4 | 7.15–7.41 s |

A lower-quality fixed-token streaming experiment delivered its first fragment
in 3.16 seconds, but subsequent processing generated only about half a second
of audio every 1.4 seconds. This was insufficient for continuous playback and
was not enabled.

## Reproduction and release checks

Use the prepared remote voice properties with `benchmark-stream.py` and save
results outside version control. Record the hardware, inference precision,
runtime versions, warmup state, test text and tunnel conditions with each run.
Measure the first JavaFX `PLAYING` event with `RemoteStreamingSmoke.java --play`
to include native playback in the user-visible latency measurement.

Follow [RESUME.md](RESUME.md) for the full cache, startup, playback and cleanup
checklist. Capacity failures and cloud pricing are operational concerns for
the chosen deployment; they are not part of these measured inference results.
