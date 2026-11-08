# App roadmap

This repository delivers the assistant APK, shared Rust core and development harness.
The [platform contract](PLATFORM.md) defines dependencies. OS builds, gates, updates and upstream
work have their own [roadmap](https://github.com/mohammed7s/ballistic-os/blob/main/docs/PLAN.md).

## 1. Harness and baseline

The AndroidWorld API-33 emulator environment and terminal runner work. Keep task setup, independent
state checks and replay here; keep agent decisions in Rust. The original T3A baseline was 13/20.
Later core runs and limitations are in [BENCHMARK_RUNS.md](BENCHMARK_RUNS.md).
Deliverable: reproducible per-task outcomes, timing, token use and side-effect evidence.

## 2. Shared core

Design agreed 7 Nov 2026 (`core/README.md`; survey in `docs/AGENT_SECURITY_SURVEY.md`). Filtered
UI tree, one planner call per step, reader that returns typed fields, source tags on every value
with a taint rule and Rule of Two in code, deny-only judge, verifier before "done", policy and
confirmation list, skill record/replay, backend routing (OpenAI-compatible client). Injection
test suite. Used by the harness now, by the Android app in step 3 and by an iOS app later; one
implementation shared by every host, written in Rust (decided 7 Nov 2026).
**Refinement agreed 7 Nov 2026:** host-advertised executable contracts (typed arguments, app,
grants, confirmation and completion evidence), relevant/on-demand shipped descriptions separate
from recorded skills, requested-app-preserving routing, exact-operation approval across every
route, independent execution/verification statuses, and readback before ambiguous-effect retries.
Without reliable readback or idempotency, stop. Workflow design includes final postconditions,
app/version compatibility and recovery checkpoints. Test exact recipients and attachments, no
unrelated access, denial on all routes, changed approvals/targets and no duplicate sends. Report
independent benchmark outcome separately from agent done; benchmark inspection never becomes a
production capability. Measure route, latency and tokens and categorize planning, target
resolution, permission, environment, execution, verification and policy-denial failures.

**Current boundary:** initial Rust tree loop, harness, element reader and terminal owner source
excerpts exist; general reader facts/provenance, deny-only judge and recorded skills remain
pending. Summary validation is offline only; see [owner summary record](log/2026-11-07-owner-summary.md). The terminal prompt is sufficient for development;
there is no localhost/browser UI work. Screenshots and a separate GUI worker remain deferred;
the next product interface is the native Android app in step 3. Consult [CONTEXT.md](CONTEXT.md) for the
implemented contract subset and measured results; the following deliverables remain acceptance
criteria, not a claim that step 2 is complete.

**You see:** injection tests passing on the emulator (hostile message → summary, no action; a
forged number stopped at a confirmation that names its source); a task replayed from a saved
skill with zero model calls; backend switched by one config line; the 20-task score against the
13/20 baseline, on Gemini and on a local model, with prompt tokens per call.


## 3. Native Android app

The Kotlin/Compose shell and in-process Rust engine exist. The tested debug APK launched Clock
and passed Stop on the emulator; it predates current core and lacks other-app observation/input.
Follow [ANDROID_APP.md](ANDROID_APP.md) for milestones and [the app README](../apps/assistant/README.md)
for build commands and evidence.

Next: rebuild against current core, complete the bounded development adapter, validate native
approval and lifecycle behavior, then integrate real platform ports. Expose supported ordinary
app operations without promising general screen control. Local runtimes and voice follow measured
resource and safety validation.
Deliverable: a native task completed and independently verified, with real approval and Stop.

## 4. Platform integration and capability suite

Implement Computer Control and structured-action adapters where supported. The OS repo supplies
the platform; this repo owns client integration, capability discovery and unavailable-access UX.
Test representative apps, including messaging, files, settings and apps with weak UI trees.
Test denial/revocation, exact targets/attachments, private data handling, uncertain effects and
workflow compatibility. Report blocked apps honestly; never spoof identity or input devices.
Deliverable: measured device coverage, per-app limits and replay behavior, not an "any app" claim.

## 5. Device performance and model selection

Measure local models on the development Pixel: prefill, end-to-end latency, memory, thermal
behavior, battery and schema/numeric integrity. Choose by measured tasks. Home GPU and DSP wake
word work are deferred experiments, not prerequisites for the app architecture.
Deliverable: a supported model/runtime configuration with reproducible measurements.

## 6. Distribution

Decide the audience, supported Android versions and capability levels, release signing and update
process for the APK. Future stock Android adapters depend on actual APIs, not a forecast deadline.
OS image distribution and signing are separate decisions in ballistic-os.
Deliverable: an honest install/support guide for the chosen audience.

## Repository ownership

- `apps/`: native shells and platform adapters.
- `core/`: agent logic, contracts and enforcement.
- `harness/`: emulator, model transport, benchmarks and offline replay.
- `tests/`: app/core integration and injection checks.
- `docs/`: app design, current decisions, evidence and dated logs.

Old OS steps 4/5/9/10 moved to the OS roadmap. Old steps 6/7 (OS model host and policy service)
remain dropped. The original numbering and proposals are preserved in the
[historical plan](log/2026-11-07-before-reorganization-plan.md).
