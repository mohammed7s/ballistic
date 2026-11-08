# 7 November 2026 — targeted caption results and creation controls (verified)

The three-case run at `96d9c94` finished **2 PASS, 1 FAIL, 0 ERROR**, state **2/3**:
MarkorCreateFolder seed 0 FAIL (68.0 s, 11 calls), seed 137 PASS (64.0 s, 12 calls),
ContactsAddContact seed 137 PASS (59.1 s, 10 calls). All 33 calls succeeded with no retry/429;
82,330 prompt and 1,278 output tokens. Agent time includes 25.511 s request pacing. Parent
verified all 18 downloaded artifact hashes. This is targeted development evidence, not a new
full-suite result; last full score stays **4/20 combined, 9/20 state**. Same-seed folder state
regressed; the varied-seed pass used a reader pick, not dated-caption projection. See
`docs/BENCHMARK_RUNS.md` for timing definitions and limitations.

The failed folder trace repeatedly treated FOLDER as a mode selector and never entered a name.
Pinned app source establishes that it creates the folder immediately. Exact dialog structure
now supplies `Create folder` / `Create file` control labels without using task values or trusting
generic button IDs. Offline replay emits both labels on all four recorded dialog snapshots;
116 Rust/UniFFI and 79 Python tests pass without skips. No live uplift claimed.

**7 November 2026 — input semantics and planning guidance corrected:** the timer trace exposed
misleading core feedback suggesting a tap on a non-editable display. Feedback now requires a
real editable input. Planner rules clarify action-specific fields, numeric-keypad entry,
exact names/extensions and date/time constraints, and inspection before repeating acknowledged
creations. These are model instructions, not stronger code-enforced completion or retry policy.
Exact Markor new-file identifiers now provide `File name` and `File extension` labels rather
than relying on `my_note`/`.md` hints; displayed values remain content. The two new development
regressions improve 1/2 to 2/2. **114 Rust and 79 Python tests pass, no skips**. Planner text
increases 828 characters; live token/quality impact remains unmeasured. Details and pinned
layout source are in `docs/log/2026-11-07-reliability.md`. The ongoing caption validation is pinned to
`96d9c94` and excludes this change. Last full-suite result remains 4/20 combined, 9/20 state.

**7 November 2026 — explicit foreground completion integrated:** the launch-only change from
`c17432c` checks the complete owner request against a conservative grammar and unique exact app
labels/packages. At `done`, it requires advertised `foreground_component_v1`, app grants,
consistent component fields and a newly sampled increasing host observation sequence. The result
is `code_validated_host_foreground`, never a model claim or launch acknowledgment. Unknown or
ambiguous launch targets and absent/stale/contradictory evidence stop pending. Recognition does
not dispatch any action. The harness supplies evidence only after its bracketed foreground reads
and settled accessibility acquisition. Native NotificationScreen leaves this contract absent;
its foreground-completion gap remains. Rust/Kotlin JSON mirrors changed together, with no
UniFFI ABI regeneration or APK rebuild. Details and trust limits: `core/README.md` §10.

Parent integration checks pass: **112 Rust tests with UniFFI, 79 Python tests and 44 JVM tests,
no skips**; the JVM checks use the freshly built native library and include positive foreground
and negative notification-only boundary cases. These are development regressions. The full
`OpenAppTaskEval` prompt also asks for permission-popup handling and is deliberately outside
this launch-only grammar; this change alone does not fix that measured benchmark regression.
Compounds retain ordinary verification, the planner must still reach `done`, and a trusted-host
assertion proves foreground presence at that observation, not saved/sent state or absence of
prior side effects. Sequence detects replay, not dishonest host fabrication or stale a11y capture.
No live uplift or 13/20 claim is made. A separate live caption round is pinned to `96d9c94`, so
its results will not include this foreground change.

**7 November 2026 — exact file captions integrated, request pacing added:** `6d2bebb`
recognizes the exact English Markor browser-row envelope verified against versionCode 146 source.
It separates file/folder kind from the full untrusted title; only whole owner-matched titles are
shown, while unmatched raw captions retain their reader identity. Kind alone grants neither
an action nor completion evidence. Unknown structures and ambiguous descriptions remain hidden.
Parent validation: **97 Rust tests with UniFFI pass**. The frozen development cases improved
3/10 to 10/10, plus one folding regression; they are not held-out evaluation. Offline recorded
CreateFolder screens expose the same requested folder in ten observations (previously zero);
related DeleteNote and CreateNote names remain hidden. No live score change is established.
See `docs/log/2026-11-07-observation-captions.md` for the grammar and limits.

The core harness now optionally spaces HTTP starts across roles and retry attempts using
`--core_model_min_interval` (suite: `CORE_MODEL_MIN_INTERVAL`); default zero preserves existing
behavior. Interval and added wait are recorded. A five-second interval is planned as a cautious
next-run setting after observed rate quota, not a measured provider limit; separate processes
are not coordinated by this limiter. It changes elapsed time, not model/token/attempt budgets,
and 429 remains terminal. **79 Python tests pass without skips**, including fake-clock pacing,
retry and quota tests. Full-suite comparison must report the pacing setting explicitly.

**7 November 2026 — targeted reliability validation stopped on rate quota:** at `215733e`,
the first of six preselected cases (`OpenAppTaskEval`, seed 0) opened the requested app and passed
the independent checker, but core rejected seven done claims citing unreadable elements. The
ninth planner call hit HTTP 429, classified as rate quota. Result: **0 PASS, 0 FAIL, 1 ERROR;
state 1/1 attempted**. The other five cases were not run. Agent time was 37.1 s, checker 0.031 s,
16 model attempts, 23,426 known prompt and 768 completion tokens; failed-call usage is unknown.
There were no retries and no empty successful responses, so live empty-response recovery remains
untested. Only one action dispatch occurred. This exposes a completion-evidence regression;
it does not establish recovery toward 13/20. The last full suite remains 4/20 combined, 9/20 state.
The helper released its emulator lock at 04:13:23 UTC; independent checks confirmed no remaining
owner/driver. Evidence is in ignored `ballistic_runs/reliability_215733e/`. The remote main
checkout's uncommitted app work was preserved by using a separate clean validation worktree.

The suite runner now stops after a recorded HTTP 429 (exit 75), preserves independent state
results, and reports attempted versus requested tasks. Numeric provider retry guidance and
allowlisted quota limits are retained without raw error messages or project identifiers; there
is no automatic 429 retry or key rotation. **75 Python tests pass without skips**, including the
Rust bridge protocol tests; shell syntax validation passes. These are offline checks, not a new
live score. Foreground completion readback and exact file/folder observations are being addressed
separately; neither is included in the results above.

**7 November 2026 — reliability changes integrated, offline validation only:** `ed4bbdd`
adds one retry for eligible missing/null/blank model replies within the existing attempt ceiling;
429, refusal, filtering, length-limit and tool-call responses are excluded. Retry usage is kept
in nested telemetry and must be included in cost analysis. Completion checks reject opaque,
duplicate and noncanonical citations, return fixed feedback, reuse the fresh verifier screen,
and check cancellation after verification. These checks preserve model-assessment status;
they do not prove semantic coverage, delivery or persistence. The native HTTP client is unchanged.
After integration with the wallet removal, **86 Rust tests with UniFFI and 71 Python tests pass**.
The eight completion regression cases improved from 4/8 to 8/8; they guided implementation and
are not independent held-out evidence. Scripted protocol tests retain exactly one guarded action
dispatch during empty-verifier-response recovery or exhaustion. No live improvement is measured;
the last suite remains 4/20 combined, 9/20 independent state. See `docs/log/2026-11-07-reliability.md`.

**7 November 2026 — native APK Clock and Stop smoke verified:** the existing pre-Core4 APK
opened Clock from the exact prompt `open the Clock app`, using in-process Rust and Gemini
flash-lite. An independent activity-manager read confirmed DeskClock in the foreground.
Core did not verify completion: 15 planner calls and one verifier call produced 14 `opened_ui`
outcomes and one rejected done claim, then exhausted the step budget. The notification-only
Screen adapter cannot observe the foreground app; repeated launches demonstrate an observation
and completion gap, not a successful end-to-end completion report.

A separate native Stop test requested Stop while the planner was running, before its response
returned. The UI ended `Stopped; nothing was done`, Ballistic remained foreground, and the
action log was byte-identical before/after. This proves no subsequent action dispatch in this
case; it does not cancel the in-flight HTTP request. The initial TLS failure occurred with the
emulator dated October 2023; temporary host UTC allowed model calls without bypassing TLS checks.
The helpers restored benchmark time (last `2023-10-15T16:29:19.614003+00:00`) and released the
emulator lock; a separate check confirmed its absence. No physical phone was used. Evidence:
local ignored `ballistic_runs/app_pass2/`; details in `apps/assistant/README.md`.
The installed APK remains pre-Core4; these results do not change the 4/20 benchmark score.

**7 November 2026 — Kohaku wallet feature removed (owner decision):** removed the standalone
adapter, vendored transport, dependencies, planner action, balance result rendering, host adapter
and dedicated integration docs/tests. Ballistic has no wallet capability or planned wallet work.
The optional generic Tools/grants contracts remain inert, with no supported request variants;
phone control, capability checks, cancellation and injection checks remain. Wallet device testing
is cancelled. Historical Git commits and ignored build/run artifacts are retained, not active
product features. No device, emulator or external submission was changed for this removal.
Local validation: **78 Rust tests with UniFFI, 63 Python harness tests and 41 JVM tests pass**
(including the native core boundary); generic regression coverage rejects stale grants and
unsupported tool actions before dispatch. Android/device validation was not rerun.

**7 November 2026 — Astra recovery and Core 4 results:** the suite at `8098265`,
`suite_core_20261107-023245.tsv`, finished all 20 tasks: **4 PASS, 9 FAIL, 7 ERROR**;
AndroidWorld's independent state checker passed **9/20**. Agent time totals **1039.5 seconds**,
median **53.9 seconds**, **197 model calls**. T3A's original baseline remains 13/20 and 1495.7
seconds; differing seeds and failed tasks ending early prevent interpreting elapsed time as a
quality-adjusted speed improvement. False completion was observed for Wi-Fi off, Calendar add
and Markor create. Several errors were successful HTTP responses without message content; the
SMS send 429's truncated body does not establish whether its quota was daily or per-minute.
The new host fix records response shape/finish reason and classifies quota from the full response
without retaining raw error bodies. Core now bounds repeated element-reader queries and rejects
anonymous switch evidence; these follow-up fixes are **not included in the measured suite**.

The recovered UniFFI entry point is committed as `a0b3e15`; `b4734de` integrates Core 4 readback,
reader and control labels with cancellation plus the follow-up guards. Parent verification:
**76 Rust tests with UniFFI and 34 host tests pass**. Full provenance, reader summary/facts,
field-by-field completion evidence and skills remain unfinished. The native app APK was built
for arm64 and x86_64 (34,618,793 bytes), with 41 JVM tests reported passing by the app agent;
the real app prompt smoke is in progress and uses the earlier pre-Core-4 binary. Its host has
notification observations and intent launching, not general control inside other apps.
Injection runner/checker and fixtures are being completed; no live injection pass is claimed.

**7 November 2026 — core benchmark rerun aborted (quota), no new score:** two attempts at commit
`deeb3b0` with `gemini-flash-lite-latest`. Attempt 1 (00:54 UTC) lost all internet on the ThinkPad
mid-suite (Tailscale offline, DNS failures): 20/20 ERROR in setup. Attempt 2 (01:05 UTC): 18 tasks
got HTTP 429 on the first planner call, OpenAppTaskEval reached the goal state before its second
call was refused, ContactsAddContact died on an adb timeout under host load 9.6. The 429 body is the
**daily free-tier quota**: `GenerateRequestsPerDayPerProjectPerModel-FreeTier`, limit 500,
`gemini-flash-lite-latest` resolves to `gemini-3.5-flash-lite`; reset 08:00 UTC. A core suite costs
about 130 calls, a T3A suite about 280, so one key allows at most two suites a day. Harness gap: run
JSONs record only the 429 status, not the body. Logs in local `ballistic_runs/suite_core_20261107-*/`
(ignored). The comparison with the 13/20 baseline and the 3/20 run at `a13f59e` is still owed.

**7 November 2026 — Android app skeleton started in parallel (`apps/assistant/`):** Kotlin + Compose
shell around the Rust core, unprivileged (PLAN step 3). `core-api` is a pure-Kotlin module with the
four ports and the types mirrored from `core/src/types.rs` (same serde JSON shapes; every fixture
in `core/tests/screens/` parses and re-encodes identically); 36 unit tests pass with JDK 21 and
Gradle 9.9. `app` (prompt box, confirmation window with BiometricPrompt + per-operation Keystore
key + injected-input filter, command gate, settings, action log, notification-listener Screen,
intent Hands, HTTP Models) is **uncompiled**: no Android SDK on the Latitude; build it on the
ThinkPad. Runs use a scripted `FakeCoreEngine`; `UniffiCoreEngine` is a placeholder. Decisions
taken by the agent, to review: UniFFI boundary proposed as the same JSON strings the bridge carries;
`IntentHands` returns `not_executed` for ops that need Computer Control; policy file
`{"version":1,"apps":{pkg:{observe,act,route,confirm}}}`; tighten-only confirmation floor lives in
Kotlin for now (should move to core/policy, §15 rule 1); confirmation window opened from a
notification when the app is in the background, pending approvals denied after 10 minutes;
compileSdk/targetSdk 37, minSdk 34; API key encrypted with a Keystore AES-GCM key. Conflicts with
`docs/ANDROID_APP.md`: it says `apps/android/` (SPEC §4.1 says `apps/assistant`, used); its
paired-host model adapter, interrupted-run record and foreground service are not built; core must
expose the port traits and run events before UniFFI is useful. Review found one likely runtime bug
(ACTION_MOVE events fail `verifyInputEvent` because of sample batching) and it was fixed: full
verification on DOWN and UP, device/source/tool checks on MOVE.

**7 November 2026 — training research completed:** `docs/PIXEL_MODEL_TRAINING.md` evaluates
teacher-generated supervision, LoRA, export/runtime fidelity and held-out evaluation. Proposed
sequence: correct core → untuned local baseline → prove custom export → small checked SFT trial
→ held-out comparison → actual Pixel measurements. E2B remains the first hardware candidate;
Qwen3-4B is the training control, E4B the quality challenger. Stock Tensor NPU bundles do not
establish custom tuned-weight deployment: the report links current compiler limitations and
reported export regressions. No numerical uplift forecast is justified; improvements would
first mean fewer errors/calls, not inherently faster same-size inference. Teacher service terms
need checking for the selected training use. No training, downloads or provisioning performed.
The owner's phone-control/app-workflow distinction supports testing a small typed-operation
interpreter before investing in general UI-policy training; structured adapters remain pending.

**7 November 2026 — observation consistency patch:** harness now brackets
acquisition with foreground-activity reads, requires two matching ready foreground samples in
active/focused application/system windows, and rejects an unchanged app tree when the observed
activity changed. Six attempts and a 15-second soft deadline end in a sanitized ObservationError
instead of returning an unready tree. No AndroidWorld changes. Trace inspection established
identical trees paired with different activities in Wi-Fi and Brightness cases; the exact
producer-side cause is unproven. Raw forests expose no capture generation tied to actions, so
same-activity stale trees remain undetectable; legitimate identical trees across activities can
be conservatively rejected. Blocking transport calls can exceed the soft deadline. Parent reran
all **25 Python harness tests**, passing locally and on the ThinkPad for the initial patch.
Live navigation-only testing exposed a launcher timeout caused by nesting AndroidWorld's
whole-screen settling loop inside this check. Follow-up `b33c441` uses raw acquisitions and
foreground-only comparison, with a 0.3 s interval between candidate samples; all 25 local tests
pass again. The repeat live smoke passed six observations: home 1.676 s, unchanged home 1.221 s,
Settings 2.229 s, Display 1.903 s, Brightness dialog 1.966 s, return home 2.694 s. No model calls,
task scoring or settings-value changes; emulator returned home. Evidence is in local ignored
`ballistic_runs/observation_smoke_20261107/` and remote `~/ballistic_runs/observation_fixed_20261107.log`.
This validates window metadata and these transitions only, not all stale-tree cases or a new
benchmark score. GitHub SSH was temporarily unreachable from ThinkPad; the already-pushed
follow-up commit was fast-forwarded there from a locally transferred Git bundle.

**7 November 2026 — benchmark safety patch reviewed:** core now rejects unidentified tap,
long-press, edit and slider targets, including the recorded anonymous Quick Settings guesses.
Filtering retains package ownership, prevents cross-package folding and supplies code-owned
labels for a small set of exact notification-footer/SMS control identifiers; network/device
strings remain content. After an acknowledged guarded input (Send, keyboard action, type+submit
and other confirmation-requiring activations), the loop stops with task `pending`, execution
`executed`, verification `unknown`. Only guaranteed `not_executed` permits replanning. This blocks
the observed blind repeats but also stops recipient/search submissions early; it does not prove
delivery, prevent every possible duplicate, or improve completion scoring. Anonymous tiles,
stale observations, semantic recipient/body binding and postcondition readback remain unresolved.
Parent reran **42 Rust + 9 Python tests**, all passing, including 12 new regressions from three
recorded screen fixtures and hostile variants. No live model/emulator task was run for this patch;
there is no new benchmark score. The earlier 3/20 score below belongs to code `a13f59e`.

**7 November 2026 — completed core suite:** all 20 outcomes collected from
`suite_core_20261106-234941.tsv`, code `a13f59e`, Gemini `gemini-flash-lite-latest`, seed 0.
Suite exited; emulator alive and no task/chat/core process at 00:17:11 UTC. **3 PASS, 9 FAIL,
8 ERROR**, versus original T3A **13/20 PASS**. Independent state: **6 passed, 13 failed,
1 unknown**; Bluetooth and both SMS tasks passed state without agent done. Zero final false-done.
Errors are separate from model failures: 5 HTTP 429 responses (daily quota versus rate limit
unknown), 2 Gemini read timeouts, 1 setup DNS failure before any JSON/checker. There is no
established emulator crash. All 20 logs and 19 JSONs downloaded; MarkorCreateNote has no JSON.

Recorded time across 19 JSONs: **950.8 s**, median **37.9 s**, plus 15.449 s checker time;
missing setup-failure time is unknown. Baseline: **1495.7 s**, median **66.4 s**, includes checker.
Neither includes a comparable full wall-clock measurement. Baseline unseeded versus current
seed 0, different goals and early infrastructure stops prevent a controlled speed comparison.
132 model attempts: 125 succeeded, 7 errors; recorded usage **290,616 prompt + 4,357 completion
tokens**, mean **2,325 prompt tokens per usage-bearing call**, versus baseline **10,707**.
Failed-call usage is unknown, not zero. Seventeen approvals were automatic harness decisions,
not evidence of a working biometric gate.

Trace-backed priority: Quick Settings labels were hidden, causing unrelated Flashlight and DND
changes. Stale trees affected Settings navigation. SMS tasks kept attempting Send after success;
duplicate delivery is not established. A benchmark state pass does not prove absence of side
effects. Fix these before expanding action coverage. Full ignored local evidence is in
`ballistic_runs/core_suite_20261107/` (`report.txt`, `comparison.json`, source logs/JSONs and
`release_status.json`). The table below preserves the comparison in tracked documentation.

| Task | T3A result / s | Core result / s | Core state | Cause |
|---|---|---|---|---|
| ContactsAddContact | PASS / 57.2 | PASS / 44.3 | passed | Passed |
| ClockTimerEntry | FAIL / 64.3 | FAIL / 37.9 | failed | Wrong timer digits; repeated backspacing |
| ClockStopWatchRunning | PASS / 21.0 | PASS / 28.8 | passed | Passed |
| OpenAppTaskEval | PASS / 10.1 | PASS / 15.1 | passed | Passed |
| SystemWifiTurnOff | PASS / 73.6 | FAIL / 20.2 | failed | Stale tree, wrong navigation, invalid targets |
| SystemBluetoothTurnOn | FAIL / 101.9 | FAIL / 42.0 | passed | Bluetooth on; agent never reported done |
| SystemBrightnessMax | FAIL / 70.2 | FAIL / 44.7 | failed | Old Display tree while BrightnessDialog active |
| TurnOnWifiAndOpenApp | PASS / 68.5 | FAIL / 195.7 | failed | Hidden labels: toggled Flashlight and DND instead of Wi-Fi |
| SimpleSmsSend | PASS / 75.3 | FAIL / 44.9 | passed | Sent; repeated Send attempts, no done |
| SimpleSmsReplyMostRecent | PASS / 47.3 | FAIL / 39.6 | passed | Replied; repeated Send attempts, no done |
| SimpleCalendarAddOneEvent | FAIL / 90.2 | ERROR / 172.4 | failed | Gemini read timeout (not established as quota) |
| SimpleCalendarEventsOnDate | FAIL / 171.7 | ERROR / 153.8 | failed | Gemini read timeout (not established as quota) |
| MarkorCreateNote | FAIL / 48.0 | ERROR / unknown | unknown | Setup DNS failure fetching accessibility forwarder; no JSON |
| MarkorDeleteNote | PASS / 190.9 | FAIL / 10.8 | failed | Required reader path is unimplemented |
| MarkorCreateFolder | PASS / 28.6 | FAIL / 49.0 | failed | Created/opened a document instead of folder |
| ExpenseAddSingle | FAIL / 81.8 | ERROR / 27.7 | failed | HTTP 429 on call 7; exact limit unknown |
| ExpenseDeleteSingle | PASS / 35.5 | ERROR / 7.2 | failed | HTTP 429 on first call |
| RecipeAddSingleRecipe | PASS / 171.1 | ERROR / 7.2 | failed | HTTP 429 on first call |
| FilesDeleteFile | PASS / 63.9 | ERROR / 7.4 | failed | HTTP 429 on first call |
| CameraTakePhoto | PASS / 24.6 | ERROR / 2.1 | failed | HTTP 429 on first call |

**7 November 2026 — first APK plan:** `docs/ANDROID_APP.md` now details the native text-first
Kotlin UI, run/Stop/approval/results, lifecycle and four implementation milestones. Verified:
there is no Android project, Kotlin binding or APK yet; the Rust loop still takes the concrete
stdio Bridge. Proposed integration: extract the in-process engine, build the native shell, test
it using a debug-only host adapter on the API-33 emulator, then supply real device ports and
production authentication. The development adapter is not Computer Control and does not validate
the production approval boundary. The owner's terminal-only development preference remains;
no browser UI is planned. Gemini stays the first integration backend; local-model testing follows.

| Decision | Why |
|---|---|
| **Fork GrapheneOS, not AOSP, LineageOS or /e/OS; not a new OS brand** | Keeps hardening, verified boot, sandboxed Play, Pixel-only single target. A new OS or a LineageOS base means no hardening, worthless attestation, and a maintenance burden we can't carry. |
| **Pixel hardware only** | GrapheneOS supports Pixel 6–10a. Tensor TPU, Titan M2, relockable bootloader with our own AVB key. |
| **Full OS control is required; an app is not enough** | Every capability we need (screen capture incl. FLAG_SECURE, input injection, hotword, persistence, policy enforced below the agent) is privileged. Every shipped agent (Gemini, Doubao) is privileged OEM software. |
| **Build on Android 17's Computer Control + AppFunctions, don't rebuild them** | Verified present in AOSP `android17-release` (see §4). GrapheneOS already ships Android 17. |
| **Confirmation = biometric-bound per-operation Keystore key in a window that drops injected events** (replaces Protected Confirmation, 6 Nov 2026) | Protected Confirmation's HAL was removed from Pixel 6/7/8 trees in Android 15 QPR1 and never shipped on Pixel 9 (§4b). Fingerprint matching happens in the TEE and injected touches cannot produce the auth token, so the property "agent can raise the dialog, never press it" is kept. |
| ~~v1 = everything in the app; v2 = policy and models move below the app~~ **superseded 7 Nov 2026: Ballistic is always an app** | Owner decision. The app is the product on GrapheneOS with our gates, on stock Android if the DMA opens the same gates, and one day on iOS with the smaller action set that platform allows. The OS side is switches only (Computer Control on, owner-edited allowlist, confirmation window input filtering). No model host, no policy service in `system_server`; SPEC §4.2–4.3 and PLAN steps 6–7 marked dropped. |
| **Gates are hardening deltas, never replacements** (6 Nov 2026) | If Android 18 ships DMA assistant plumbing in AOSP, a replacement gate conflicts on every rebase and nobody merges it; a delta (no remote config, default empty, user toggle) survives and is the shape GrapheneOS already accepts. |
| **Computer Control gate shape: two user lists + AOSP consent, Option A** (7 Nov 2026) | Owner confirmed. Per user, two lists edited only in Settings: agents ("Allow screen automation") and targets ("Allow screen automation of this app"), both empty by default, signer-bound, ORed with the DeviceConfig lists at the two existing checks; the assistant role and the AOSP per-target consent dialog stay. No master switch (AOSP has none); no GosPackageState (stay AOSP-shaped); own Settings strings file; per-action limits stay in the app (SPEC §4.3). Patches and design in `ballistic-os/patches/` and `ballistic-os/docs/patches.md` (§4c). |
| **No stealth; parity-with-Gemini argument instead** (6 Nov 2026) | Computer Control's input devices are identifiable by any app in one call (§4b), so timing mimicry buys nothing and renaming devices is spoofing. Ballistic is as visible as Gemini on a Pixel; an app that blocks it blocks Gemini. "Human-like timing" removed from the spec. |
| **Command gate: biometric/passkey before any command** (6 Nov 2026) | Closes "stranger or played audio commands the phone". Distinct from content injection, which the planner/reader split and data-injection rules cover. |
| **Positioning: not faster than a hand** (6 Nov 2026) | Measured prefill on Tensor gives 4–25 s per agent step. Wins are hands-free, screen-off, long/repetitive tasks, skill replay, queues. |
| **Model choice open; must have a LiteRT build or acceptable CPU prefill** (6 Nov 2026) | LiteRT-LM GPU is the only fast prefill path on Tensor; llama.cpp Vulkan is broken on both Tensor GPUs; LFM2.5-8B-A1B has no LiteRT build and failed a real-phone agent test. |
| **Home GPU parked** (6 Nov 2026) | Local-first is evaluated on its own first. |
| **Model routing: device first; cloud opt-in off; home GPU parked** | Wake word and STT never leave the device. Indicator shows whenever a payload leaves the phone. |
| **Planner/reader split** → **three layers against content injection** (7 Nov 2026) | Survey of CaMeL, Dual LLM, the six design patterns, Meta's Rule of Two, OpenAI Operator and the open Android agents (`docs/AGENT_SECURITY_SURVEY.md`): no model is un-injectable; guarantees come from code tracking data provenance plus a human. Layer 1, code: planner never reads content (list titles shown only when plain string matching finds them in the owner's request); reader returns typed fields in a shape core asked for, never prose into the planner; every value carries a source tag set by code; taint rule (tainted value → hard confirmation showing value and source); Rule of Two (read untrusted content and send/pay/delete never in one session without a confirmation). Layer 2, judge: deny-only model call before every state change; "allow" changes nothing, "block" halts, so a fooled judge only adds caution. Layer 3, owner: fingerprint window. Enforced in core inside the app. Owner agreed all four points (app-only, three layers, deny-only judge, typed reader). |
| ~~Resident on-device model: LFM2.5-8B-A1B class~~ **superseded 6 Nov 2026**, see model-choice row | Memory fits; speed and runtime support do not yet. |
| **Two tracks: fork now (track A), upstream the gates (track C)** | Fork = dev vehicle + fallback. Upstream candidates = user-controlled gates for Computer Control, AppFunctions, the OnDeviceIntelligence socket, hotword via assistant role. The agent itself will never be upstreamed. |
| **Name: Ballistic, one brand, no sub-brands** | Electrons move through graphene ballistically: fast, straight, no scattering. Chosen 6 Nov 2026 (earlier working names Hex, Monolayer rejected). Never "Graphene" in the name (trademark, and would imply endorsement). |
| **Test suite: AndroidWorld (116 tasks / 20 apps) + our injection tests** | Standard benchmark; runs on the emulator over ADB. |
| **Dev phone: used Pixel 8 Pro (12 GB); later Pixel 10 Pro (16 GB)** | Avoid Pixel 6/7 (end of support 2026/27). 8a is the cheapest acceptable. |
| **Repo: one GitHub org, one monorepo for our code + forks of the 4–5 AOSP repos we patch, glued by a repo manifest overlay** | AOSP's build forces the fork structure; the monorepo is what people read. One commit per feature in the forks so rebasing is mechanical and the upstream proposal is `git log upstream..ballistic-17`. |

| **Fingerprint-gated actions confirmed by the owner (6 Nov 2026)** | Owner's decision: sends, payments, deletes and installs require a biometric (fingerprint) approval on a window the agent cannot press, regardless of whether Protected Confirmation turns out to be available. |
| **Build pipeline: first build is the pinned GrapheneOS tag unmodified; patches are added one at a time afterwards (6 Nov 2026)** | Proves the build/sign/flash/OTA chain before any of our code is in it; each later patch is then a single, bisectable change. Build machine: rented by the hour (Hetzner-class, 16 vCPU / 64 GB), source tree kept on a persistent volume so re-syncs are cheap. |
| **Step 1 closed (6 Nov 2026)** | Its deliverable was a baseline, and it exists (13/20, §4). 20/20 on the stock agent is not a goal; the table is the yardstick for step 2. The two harness-side failures (empty Settings tree, app-name lookup) move into core's Screen/Hands port instead of being patched on T3A. |
| **AndroidWorld: keep environment + benchmark, replace the agent (6 Nov 2026)** | Its env layer (android_env + a11y forwarder over gRPC, ADB actuation) becomes the harness's Screen/Hands port; its 116 tasks and `is_successful` checks stay as the scoreboard; T3A/M3A are replaced by `core/`. No agent framework (LangChain/LangGraph etc.): the loop is small, must port into an Android app, and the safety rules must live in our own testable code. |
| **Core design agreed (7 Nov 2026)** | `core/README.md`: four ports (Screen, Hands, Models with roles planner/reader/judge/verifier, Confirm), filtered tree with match-only titles, one planner call per step by element id, typed reader, provenance + taint rule + Rule of Two, deny-only judge, verifier before `done`, policy with hard (fingerprint) and soft (one tap) confirmation, skills by resource id/label saved only after a pass or the owner's yes. Defaults in force: attacker = content inside trusted apps; OS text is label except shade/SSIDs/Bluetooth names; injection tests are gates, score second; measure Gemini and a local model; summaries show sources; no memory between requests in step 2; apps without a usable tree fail in step 2. Still open: language (below), skill templates, screenshots deferred. |
| **Core language: Rust from the start, native shells, Python harness unchanged (decided 7 Nov 2026)** | Owner requirement: the app must one day run on iOS, and the owner does not want to debug across three languages. Python withdrawn (cannot ship in a phone app); "Python now, port to Rust later" rejected because the port phase (two implementations that must agree) is the worst debugging phase of all; Kotlin-only withdrawn (locks out iOS); React Native rejected for core (every hard part is native on both platforms, JS engine in a privileged app, UI too small to be worth sharing). Rust library + UniFFI bindings, the `libsignal` pattern. Cleanliness rules: core is a sealed box with four ports; no agent logic in Python or Kotlin; harness ↔ core is one JSON line per message, every line logged; `--replay` reproduces a logged run with no emulator; unit tests on recorded screens. Data files (prompts, schema, policy, skills) describe behaviour, code enforces it, models never write files; shipped data is hashed in the signed APK; mutable files are app-private. Toolchains: Latitude stable 1.99.1 (rustup, minimal profile, 7 Nov 2026), ThinkPad nightly 1.97 in `~/.cargo/bin` (not on the non-login PATH). |
| **User-facing docs as HTML in `docs/guide/` (6 Nov 2026, owner request)** | Plain-language pages: how it works, the agent, trying it on the emulator. Technical documents stay the source of truth; update the guide when they change. |



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
