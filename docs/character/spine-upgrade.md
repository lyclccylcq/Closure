# Spine character evaluation

This is a local evaluation candidate. Runtime licensing is **unconfirmed**: the user answered “不确定” on 2026-11-23. The current everyday application and its private configuration have not been replaced. Technical tests do not establish a distribution licence or product acceptance.

## Run and inspect

With JDK 21, double-click `docs/character/preview-spine.command`, or run:

```sh
./docs/character/preview-spine.command
./docs/character/preview-spine.command --export /private/tmp/closure-spine-preview
```

The standalone window runs only the model renderer and action scheduler. It does not create ChatService, AI, TTS, weather, reminders, an audio player or a server. The speech/thinking controls simulate lifecycle events; they are explicitly labelled as simulated. Test a tap while speech is active, end speech before the tap finishes, and check recovery to IDLE. Repeat with speech starting during a tap and verify recovery to TALKING. Inspect both backgrounds and the sleep pose. Closing the window terminates its renderer.

The exporter produces contact sheets of all six source actions at four times, individual transparent PNGs, and interaction/talk/thinking GIFs. `preview-metrics.json` records the measured camera and render time. It writes to the chosen directory, outside tracked resources. If Maven uses a custom local repository, set `CLOSURE_MAVEN_REPOSITORY` to that repository directory before launching.

For the normal assistant, the evaluation checkout defaults to Spine:

```sh
./mvnw javafx:run
./mvnw -Dclosure.animation=frames javafx:run
python3 scripts/verify_startup.py
```

Normal startup uses the existing assistant integrations and is distinct from the standalone evaluation. The startup verifier uses the real transparent desktop window but skips external services and user reminders. The console must identify `Spine 3.8.99 (CPU, evaluation)` to establish that native startup used Spine rather than fallback. Missing or corrupt resources produce a diagnostic and select the existing PNG player. The explicit `frames` switch selects it directly.

## Model and behaviour

| Application action | Exported action | Playback |
| --- | --- | --- |
| IDLE | Relax | loop |
| TAP / GREETING / HAPPY | Interact | one shot, then recover current requested state |
| TALK | Relax | loop plus restrained procedural mouth/head movement |
| THINKING | Sit | loop plus a small procedural head tilt |
| SLEEP | Sleep | loop; speech or a chat request wakes the character |

Move, Sit and the zero-duration Default pose are also included in the all-action exports. The source contains **no authored Talk, Thinking or Happy animation**. Program-driven variants do not create an editable Spine project and are not phoneme-level or audio-envelope lip sync. Actual speech start/finish callbacks gate the TALK state. Existing parsed CHEERFUL/TEASING emotions request the interaction-based HAPPY variant without changing AI prompts or response schemas.

`CharacterController` retains talking/thinking/sleep as the requested base while an interaction plays. TAP has priority over GREETING and HAPPY. Generation tokens reject obsolete completion callbacks; overlapping chat requests have individually paired, idempotent completions. Successful, failed and disposed requests all release thinking state. Idle/sleep actions are available in the existing pet context menu.

The renderer uses the pinned 3.8 runtime for bones, IK/transform constraints and weighted mesh deformations, with a CPU triangle rasterizer for the existing Swing window. It supports this model's normal and additive slots, rotated/trimmed atlas regions, PMA input and draw order. A top-left fill rule prevents shared triangle edges being blended twice. It renders at twice the display resolution before downsampling. A sampled visible-pixel envelope is fixed across all actions, avoiding per-frame zoom or recentering. Rendering runs on one daemon worker with a bounded cadence and at most one pending Swing publication; stop/dispose invalidate pending frames and callbacks.

This renderer deliberately rejects clipping attachments, two-colour slot tint, other slot blend modes and atlas rotations other than 0/90 degrees. These features are absent from this pinned model. A different skeleton needs its own compatibility assessment; it must not silently render unsupported features incorrectly.

## Provenance and permission

The three unmodified assets are from [isHarryh/Ark-Models](https://github.com/isHarryh/Ark-Models/tree/b799ffe8ee30bbb1b04f3c551234edfab8d5549a/models/4228_closur), pinned commit `b799ffe8ee30bbb1b04f3c551234edfab8d5549a`. `source-manifest.json` records SHA-256 and Git blob hashes. The export is Spine **3.8.99**, with 297 bones, 117 slots, 74 mesh and 53 region attachments, one 624×624 PMA texture, and six actions. No `.spine` editor project is supplied.

The upstream repository states that the artwork belongs to Shanghai Hypergryph Network Technology Co., Ltd. and is not for commercial use. See the pinned [upstream notice](https://github.com/isHarryh/Ark-Models/blob/b799ffe8ee30bbb1b04f3c551234edfab8d5549a/README.md).

The runtime dependency is `com.esotericsoftware.spine:spine-libgdx:3.8.99.1` with libGDX core `1.10.0`, without an OpenGL or native backend. The [3.8 runtime README](https://github.com/EsotericSoftware/spine-runtimes/blob/3.8/spine-libgdx/README.md) permits evaluation and describes licensing requirements for integration/distribution. The applicable runtime notice is included with the resources. The [official runtime agreement](https://esotericsoftware.com/spine-runtimes-license) and [Editor agreement](https://esotericsoftware.com/spine-editor-license) must be considered before everyday use or distribution. No licence purchase or claim of an existing licence is made by this task. Publication/merge and replacement of the current application remain held pending confirmation of that condition.

## Execution record

The initial Claude attempt failed before implementation because its service could not connect. On 2026-11-23 the user expressly authorized Codex direct implementation and temporarily suspended the Claude-guided mode for the coming months. This candidate is implemented by Codex. Existing isolation, real checks, evidence review and authorized engineering completion requirements remain in effect; no Claude implementation or user acceptance is claimed.
