# Spine character and actions

The assistant uses the Spine character and action scheduler by default. On 2026-11-23 the user selected the previewed solution for adoption. Runtime licence ownership remains **unconfirmed**; selecting the solution does not establish a licence. Activation preserves the existing daily runtime's local chat customizations, private settings and voice services, with backups of the affected files.

## Run and inspect

With JDK 21, double-click `docs/character/preview-spine.command`, or run:

```sh
./docs/character/preview-spine.command
./docs/character/preview-spine.command --export /private/tmp/closure-spine-preview
```

The standalone window runs only the model renderer and action scheduler. It does not create ChatService, AI, TTS, weather, reminders, an audio player or a server. The speech/thinking/chat-visible controls simulate lifecycle events; they are explicitly labelled as simulated. Test a tap while speech is active, end speech before the tap finishes, and check recovery to IDLE. Repeat with speech starting during a tap and verify recovery to TALKING. Select chat-visible and verify that speech uses the same sitting body while the mouth moves, then returns to thinking. Inspect Walk, both backgrounds and the sleep pose. Closing the window terminates its renderer.

The exporter produces contact sheets of all six source actions and procedural variants at four times, individual transparent PNGs, and interaction/talk/thinking/seated-speech/walking GIFs. `preview-metrics.json` records the measured camera and render time. It writes to the chosen directory, outside tracked resources. If Maven uses a custom local repository, set `CLOSURE_MAVEN_REPOSITORY` to that repository directory before launching.

For the normal assistant, startup defaults to Spine:

```sh
./mvnw javafx:run
./mvnw -Dclosure.animation=frames javafx:run
python3 scripts/verify_startup.py
```

Normal startup uses the existing assistant integrations and is distinct from the standalone evaluation. The startup verifier uses the real transparent desktop window but skips external services and user reminders. The console must identify `Spine 3.8.99 (CPU)` to establish that native startup used Spine rather than fallback. Missing or corrupt resources produce a diagnostic and select the existing PNG player. The explicit `frames` switch selects it directly. The original desktop launcher remains the daily entry point after scoped activation; no new voice setup is required.

For a scoped activation, require each affected existing file to match the reviewed base, back it up, and record old/new hashes plus newly added paths. Keep every other source file and all private configuration/service data untouched. If activation fails, restore the backed-up files and remove only manifest-listed additions after verifying their installed hashes; rebuild the preserved runtime. The `frames` option above also restores the old visuals without removing the feature.

## Model and behaviour

| Application action | Exported action | Playback |
| --- | --- | --- |
| IDLE | Relax | awake standby loop |
| WALK | Move | native 8-second loop with movement and facing applied by the desktop window |
| PRESSED / DRAGGING / LANDING | Relax | procedural brace, nape-supported hold and release recovery |
| TAP / GREETING / HAPPY | Interact | one shot, then recover current requested state |
| TALK | Relax | speech outside the visible chat window; procedural mouth/head movement |
| THINKING | Sit | persistent while chat is visible, or while a request is pending |
| THINKING_TALK | Sit | the same seated body plus procedural mouth/head movement during chat speech |
| SLEEP | Sleep | default after two minutes without pet interaction; background speech temporarily masks sleep |

The zero-duration Default pose is also included in the all-action exports. Move and Sit are now used by WALK and the conversation poses respectively. The source contains **no authored Talk, Thinking or Happy animation**. Program-driven variants do not create an editable Spine project and are not phoneme-level or audio-envelope lip sync. Actual speech start/finish callbacks gate the TALK state. Existing parsed CHEERFUL/TEASING emotions request the interaction-based HAPPY variant without changing AI prompts or response schemas.

`ChatWindow` holds an idempotent conversation-visible lease from show until hide/dispose. This seated base survives request completion, errors, cancellation, speech and emotion feedback. Opening chat preempts an old one-shot and invalidates its callback; interactions cannot replace the body while chat remains visible. Outside chat, TAP retains priority over GREETING and HAPPY, and pending requests remain individually paired. Explicit Idle/Walk/Sleep commands preempt an older one-shot; late startup-audio completion cannot replace the newer mode. Closing the last visible chat starts a fresh inactivity interval.

`CharacterController` uses a monotonic clock for the two-minute long-idle policy. The normal pet window checks it once per second. Clicking or dragging the pet, choosing an explicit action or using chat wakes it. Sleeping suppresses spontaneous idle chatter. Necessary background speech may temporarily use TALK, then returns to sleep if no interaction occurred. The Actions menu exposes Idle, Walk, Sleep and Autonomous Walk. Desktop walking uses the current screen's usable area and pauses while chat is visible or the pet is held. Automatic walking leaves the inactivity clock running. The standalone preview plays Move in place. See [daily character behavior](../character-behavior.md) for the desk movement and drag controls.

The renderer uses the pinned 3.8 runtime for bones, IK/transform constraints and weighted mesh deformations, with a CPU triangle rasterizer for the existing Swing window. It supports this model's normal and additive slots, rotated/trimmed atlas regions, PMA input and draw order. A top-left fill rule prevents shared triangle edges being blended twice. It renders at twice the display resolution before downsampling. A sampled visible-pixel envelope is fixed across all actions, avoiding per-frame zoom or recentering. Rendering runs on one daemon worker with a bounded cadence and at most one pending Swing publication; stop/dispose invalidate pending frames and callbacks.

This renderer deliberately rejects clipping attachments, two-colour slot tint, other slot blend modes and atlas rotations other than 0/90 degrees. These features are absent from this pinned model. A different skeleton needs its own compatibility assessment; it must not silently render unsupported features incorrectly.

## Transparent-window presentation

On macOS, painting an entirely nonopaque Swing hierarchy can expose a blank interval: AWT clears the native window before Swing paints its buffered children. The running JDK 21 implementation and the [OpenJDK discussion](https://github.com/openjdk/jdk/pull/12993) identify this path. A solid-background preview does not exercise it. Whole-character flashing must be distinguished from animated eyes or effects; source frames can remain correct while the native window flashes.

`PetWindow` composes its complete transparent scene into a reusable premultiplied back buffer, then copies that completed image with source replacement. Clearing and partial drawing stay off the displayed surface. Transparent pixels replace the previous pose without trails; the buffer follows HiDPI scale and size, and presentation retains the destination clip. Animation cadence, model pixels and state/speech callbacks stay unchanged.

Regression checks cover destination preservation during preparation, transparent replacement, scale/resize/clipping and preparation failure. An offline native-window probe checks the real `PetWindow.paint` path; source-frame sampling separately checks for empty model frames. These checks and native startup do not constitute a screen recording or user visual acceptance.

## Provenance and permission

The three unmodified assets are from [isHarryh/Ark-Models](https://github.com/isHarryh/Ark-Models/tree/b799ffe8ee30bbb1b04f3c551234edfab8d5549a/models/4228_closur), pinned commit `b799ffe8ee30bbb1b04f3c551234edfab8d5549a`. `source-manifest.json` records SHA-256 and Git blob hashes. The export is Spine **3.8.99**, with 297 bones, 117 slots, 74 mesh and 53 region attachments, one 624×624 PMA texture, and six actions. No `.spine` editor project is supplied.

The upstream repository states that the artwork belongs to Shanghai Hypergryph Network Technology Co., Ltd. and is not for commercial use. See the pinned [upstream notice](https://github.com/isHarryh/Ark-Models/blob/b799ffe8ee30bbb1b04f3c551234edfab8d5549a/README.md).

The runtime dependency is `com.esotericsoftware.spine:spine-libgdx:3.8.99.1` with libGDX core `1.10.0`, without an OpenGL or native backend. The pinned runtime copyright/licence is included with the resources. Under the [official runtime agreement](https://esotericsoftware.com/spine-runtimes-license), users outside integration/evaluation need their own Spine Editor licence unless the integrator has an applicable licence under the [Editor agreement](https://esotericsoftware.com/spine-editor-license). Normal everyday use is distinct from evaluation. Licence ownership remains unconfirmed; this engineering change neither purchases a licence nor establishes commercial permission. Preserve the included runtime and artwork notices when sharing the project.

## Execution record

The initial Claude attempt failed before implementation because its service could not connect. On 2026-11-23 the user expressly authorized Codex direct implementation and temporarily suspended the Claude-guided mode. Codex implemented this feature. After the preview, the user said “没啥问题，先用套方案吧”, selecting this solution for adoption. Exact-head review, CI, merge and merged delivery checks still apply; preview feedback does not substitute for those checks or licence ownership.

## Local motion update

TASK-0013 follows the user’s explicit local-validation/no-push restriction. Its report, complete review packet, exact-file backups and runtime receipt are stored under `.ai-workflow/motion-local/`. No PR, remote CI or merge is requested for this iteration. [Local validation steps](motion-local-validation.md) describe the visible behavior and limits.
