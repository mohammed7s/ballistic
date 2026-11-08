# Ballistic: current decisions and handoff

Updated 7 November 2026. This file holds current decisions and the next work, not the run diary.
Read [README](../README.md), [SPEC](../SPEC.md), then this page. The complete earlier handoff is
preserved in the [log](log/2026-11-07-before-reorganization-context.md).

## 1. Product and ownership

Ballistic is an Android assistant app, not an operating system. Its Rust core owns planning,
reading, policy, provenance, evidence and workflows. Kotlin supplies native UI and platform ports.
The Python harness supplies a development environment and independent task checks.

[ballistic-os](https://github.com/mohammed7s/ballistic-os) owns the GrapheneOS patch/build work.
The app's [platform contract](PLATFORM.md) describes what it consumes without prescribing how an
OS provides it. Stock Android support is a future integration goal, conditional on usable access.

## 2. Decisions in force

| Date | Decision | Reason / boundary |
|---|---|---|
| 6–7 Nov 2026 | Separate app and OS repositories | The app can evolve independently and adopt other platform adapters. |
| 7 Nov 2026 | Ballistic always remains an app | No OS policy service or OS model host; earlier v2 proposal is dropped. |
| 7 Nov 2026 | Rust core, Kotlin shell, future Swift shell | One agent implementation; Python stays harness plumbing. |
| 7 Nov 2026 | Screen, Hands, Models and Confirm ports | Hosts expose capabilities, never make agent decisions. |
| 7 Nov 2026 | Advertised typed operations with evidence | Preserve the requested app, required grants and exact operation semantics. |
| 6–7 Nov 2026 | Local inference is the product default; cloud is opt-in | Current HTTP-backed development runs are not proof of on-device execution. Home GPU remains parked. |
| 7 Nov 2026 | Separate planner and reader, code-tracked provenance | General facts, taint/Rule of Two and deny-only judge remain unfinished; do not claim injection immunity. |
| 6–7 Nov 2026 | Exact-operation biometric approval | Sensitive actions require approval bound to values and targets; real-device boundary still needs testing. Payments/login automation remain out of scope. |
| 7 Nov 2026 | Execution and verification are distinct | Opening UI or accepting an operation is not completed work; unknown effects require inspection before retry. |
| 7 Nov 2026 | Keep AndroidWorld environment and benchmark | Replace its reference agent; benchmark inspection stays outside production capabilities. |
| 7 Nov 2026 | Record agent done and state outcome separately | State success alone does not establish absence of duplicate or unrelated effects. |
| 7 Nov 2026 | Recorded skills retain policy checks | Record only validated success or owner acceptance; offline trace replay is not live skill execution. |
| 7 Nov 2026 | Tree-only current core work | Screenshots and a separate GUI worker are deferred. |
| 6–7 Nov 2026 | Terminal development, native product UI | No localhost/browser product UI. |
| 6 Nov 2026 | No stealth or attestation spoofing | Expose unsupported/blocked apps rather than conceal automation. |
| 6 Nov 2026 | One name: Ballistic | No GrapheneOS product branding; no implied affiliation. |

## 3. Implementation boundary

The Rust core has a tree agent, schema validation, filtered observations, host capability checks,
confirmation hooks, model verification, an element reader, and terminal owner-only source excerpts.
Readback and repeat guards exist; they do not prove delivery or prevent every duplicate.
General reader facts, complete provenance/taint policy, the deny-only judge and live recorded
skills are still design work. [Core README](../core/README.md) gives the detailed boundary.

The native APK contains an in-process Rust engine, Ask/Activity/Settings UI, command gate,
approval code and Stop. A debug APK demonstrated Clock launch and Stop on an emulator. That
APK predates current core, cannot observe other apps' screens, and has no GUI input adapter.
Biometric operation approval is implemented but not security-validated on a device. Local model
runtimes, voice and the proposed development bridge remain unfinished.
See [Android milestones](ANDROID_APP.md) and [app evidence](../apps/assistant/README.md).

## 4. Latest evaluation and next work

The latest complete core suite remains **8/20 combined, 11/20 independent state**, at `05ab6eb`:
8 PASS, 10 FAIL, 2 ERROR, no final false-done acceptance. It is not directly comparable as a
controlled improvement over the earlier T3A 13/20 baseline.

The subsequent fixed three-task round at `77bc36b` produced **1 PASS, 1 FAIL, 1 ERROR**, state
1/3: Stopwatch passed, Markor deletion failed after reader feedback, and Wi-Fi observation failed
before a model call. The observation cause is unresolved. These are targeted results, not a new
full-suite score. See [benchmark records](BENCHMARK_RUNS.md) and [dated investigations](log/README.md).

Next bounded work from the handoff: acquisition-only Wi-Fi diagnosis with zero model calls and
unchanged rules; compare old/new predicates on the same observations offline. Investigate the
newly reached Markor menu failure separately. Do not interpret parked history/numeric prototypes
as integrated improvements or rerun a full suite before understanding observation compatibility.
This document records the plan; it does not assert any old session or job is still active.

## 5. Evidence and history

- [FACTS.md](FACTS.md): dated source research and reported claims, with their original qualifiers.
- [log/README.md](log/README.md): experiments, earlier handoffs and pre-reorganization snapshots.
- [AGENT_SECURITY_SURVEY.md](AGENT_SECURITY_SURVEY.md): safety design research.
- [OS context](https://github.com/mohammed7s/ballistic-os/blob/main/docs/CONTEXT.md): platform decisions and validation.

The legacy HTML/PDF spec exports are stale; SPEC.md is authoritative. Old log entries can contain
superseded plans and old section numbers. Do not promote them into current decisions.

## 6. Open questions

- Which permitted native operations should ship first, and which devices will be supported?
- Which local model/runtime meets latency, memory, thermal and tool-call integrity requirements?
- Does the approval window hold against injected input on the target device?
- When can a usable Computer Control adapter be validated end to end?
- How should skill templates, compatibility checks and recovery checkpoints be introduced?
- Which banking/read-only apps matter, and what do their actual platform restrictions allow?
- What is the app distribution audience: personal prototype, contributors or public release?

OS upstream acceptance, keys, OTA and platform race tests belong to the OS repository.

## 7. Documentation maintenance

Keep decisions here, contracts in SPEC/PLATFORM, implementation details beside the code, and dated
measurements in the log. Do not append session transcripts to this page. Update the appropriate
repo when ownership changes; link across repos instead of duplicating build instructions.

The owner's correction about project start dates and Mumbai Standard-track submission remains in
[the hackathon notes](HACKATHON_MUMBA.md) and original handoff. Do not infer eligibility dates from
old research headings.

## App focus — 7 November 2026

The owner paused harness reliability work and benchmark fix/test loops to prioritize the native
app, embedded inference and Local AI UX. Do not resume benchmark work without a new request.
The previous live worker reports emulator ownership released; the parent SSH attempt on
7 November timed out, so no new emulator installation or device validation is claimed.

Local inference fits under the existing Models port: the Rust core still owns planning,
reader separation, validation and approval requests; Kotlin routes model calls to embedded
llama.cpp through JNI or the explicitly selected HTTP backend. One local model serves serial
role calls with fresh context. The runtime is in the APK, while GGUF weights are imported into
private storage. No automatic cloud fallback is permitted. Phone profiles remain suggestions
until measured on the target phone. See [Local AI](../apps/assistant/LOCAL_AI.md).

The updated debug APK has now built for ARM64 and x86_64. Host validation: 62 JVM tests passed
without skips, including real local JNI generation/cancellation; 9 affected tests passed after
the final context UI change. Parent checked native packaging and APK signature. These are host
and build results, not device validation or model-quality evidence. Exact artifact digest,
limitations and provenance: [delivery record](log/2026-11-07-local-ai-apk.md).

## Model setup and tuning direction — 7 November 2026

The owner authorized in-app model download/verification/loading. Local AI now offers an explicit
setup flow for Qwen's Qwen3-0.6B Q8 GGUF (639,446,688 bytes), pinned to a publisher revision and
SHA-256. It is a starter preview for all current profiles, not a validated per-phone agent model.
Downloads require confirmation; imports remain available. There is no silent remote fallback.
This first version is foreground-only and retries interrupted downloads from the beginning.

No Ballistic fine-tuning has occurred. The proposed later sequence is a stable base-model
comparison, verified training examples, a bounded LoRA experiment, then held-out evaluation after
export/quantization. Training cannot provide missing observations or platform permissions, and
approval rules remain in code. Harness/benchmark work stays paused. See the
[model setup record](log/2026-11-07-model-download.md) for evidence and limitations.

## Physical-phone installation — 7 November 2026

The model-setup debug APK was transferred through the ThinkPad, hash-verified, installed on
the owner's USB-connected Pixel 10 Pro (Android 17, ARM64), and launched successfully as far
as Android's notification permission prompt. Biometric unlock, model setup/inference and an
agent task on this phone are still unverified. See [installation evidence](log/2026-11-07-phone-install.md).

## Phone setup diagnosis — 7 November 2026

The first physical-phone model download failed because the Pixel had no active internet
connection, despite Ballistic having network permission. After the owner connected Wi-Fi, the
download progressed. Authentication and platform permission are distinct; no Computer Control
grant is needed for model setup. Direct setup authentication and clearer wording were installed;
adjacent download errors, MB/percent progress and setup screen-awake handling were built, with
installation deferred to avoid interrupting the active download. See [setup evidence](log/2026-11-07-phone-setup-ux.md).

## Command access and task lifecycle — 8 November 2026

The owner requested elapsed time and background execution, and clarified that waking/unlocking
the phone should authorize commands without an additional app fingerprint prompt. Manual app
locking and sensitive-operation approval remain separate. The app now checks secure device
unlock/interactive state at command admission; switching apps does not relock access. It trusts
Android's configured unlock method, not a claim of fingerprint-only phone unlock.

Ask has a monotonic elapsed timer and Continue in background. Owner-started Ask/local operations
use a foreground service with a Stop notification and CPU wake lock; no automatic task restart.
Notification permission was disabled on the test phone; the app now links to its notification
settings. Alarm creation remains unsupported by the core-facing Android adapter. The owner's
alarm trace also showed the small model repeatedly launching Settings. See
[validation and limitations](log/2026-11-08-phone-background.md).

Physical Pixel validation: short local speed check passed (0.4 s first token, 21.8 tokens/s)
while backgrounded. Clock visibly opened but repeated because the adapter cannot verify the
foreground app. Final installed APK's notification Stop ended the task, froze elapsed at 2:19,
removed its foreground service and released its CPU wake lock. Six session tests passed.
Model download is complete; normal phone sleep was restored after testing.

## Compact run progress — 8 November 2026

The owner requested live one-line updates and collapsed past activity. Ask now shows the latest
six timestamped lifecycle/action events while running, plus elapsed time since the latest event.
Completed runs show summaries with expandable details; Activity groups each run into a collapsed
card. Model requests expose waiting/reply-received events, not raw model reasoning. Progress is
bounded to 250 in-process entries per run; existing persisted action logs remain unchanged.
The updated APK is installed and six session tests pass. Collapsed Activity cards were observed
on the Pixel; connection timeouts interrupted the remaining expansion/live-feed device checks.

## Access-control cleanup — 8 November 2026

System permission grants remain in Android/GrapheneOS Settings; Ballistic's per-app policy is
an additional app-side restriction, as specified in SPEC §4.3–4.4. Settings now separates those
controls, links to the app's system settings and offers only currently supported app rules.
Unconfigured/unreadable policies deny reading and actions and default to device-only routing.
Existing explicit serialized grants are retained. Saving an immutable rule change requires a
fresh BIOMETRIC_STRONG prompt, with no PIN fallback, and rechecks idle/unlocked state afterward.

Notification rules are now applied before node construction; device-only sources are omitted
for connected inference. With no readable sources, opening apps no longer requires notification
access. Only granted launchable apps are advertised; dispatch independently checks the grant,
every-action approval, cancellation and revocation. These restrictions cover the current Android
adapter, not unimplemented routes or an OS policy service. See the
[access-control record](log/2026-11-08-app-access.md).

## Model versus harness diagnosis — 8 November 2026

The owner reopened bounded diagnostic evaluation: frontier-model controls first, then possible
small-model specialization. The 13/20 baseline was Gemini Flash-Lite/T3A, not phone-local Qwen3
0.6B. A frontier swap cannot supply the APK's missing screen/input adapters. Ask also lacks a
normal conversational path; the current core rejects ordinary answer actions as unsupported.
CPU-only inference, per-call prompt processing and non-streamed output are established; the
short speed check does not measure agent-length prefill. A controlled pilot and latency protocol
are recorded in [model/harness diagnosis](log/2026-11-08-model-harness-diagnosis.md).
The owner selected the existing Gemini API with a Pro model. No new generation or task runs
were started: two ThinkPad SSH attempts timed out, so exact model access and quota remain
unverified. No fine-tuning or distillation has occurred.

## Tensor G5 inference route — 8 November 2026

Official Google documentation now provides precompiled Pixel 10/Tensor_G5 LiteRT-LM models
and an app sample using the Tensor TPU dispatch library. Ballistic still uses CPU llama.cpp;
this requires a new model backend and compatible compiled weights. Physical GrapheneOS
compatibility, actual accelerator use, RAM and speed remain untested. Google's documented
custom fine-tuned SLM compiler path currently targets G6, so G5 support for our own tuned model
must be established before choosing it as the deployment target. Details and sources are in
the [model/runtime diagnosis](log/2026-11-08-model-harness-diagnosis.md#tensor-g5-accelerator-research--8-november-2026).

## Prompt flow clarification — 8 November 2026

Read-only ADB check now sees Pixel 10 Pro serial 56181FDCH0070R authorized on the ThinkPad,
alongside emulator-5554. This establishes connectivity, not installation or NPU validation.
Source-derived Mermaid diagrams in `docs/diagrams/` separate command admission before core.run,
observation/read routing restrictions, deterministic filtering, planner/reader calls, execution
authorization and completion verification. These describe current root source; pending app
branches and installed native-library provenance are separate. The core loop is tree-only;
the current phone adapter supplies permitted notifications, not general screen vision or UI access.
