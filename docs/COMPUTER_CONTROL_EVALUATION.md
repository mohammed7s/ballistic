# Computer Control emulator evaluation

Status: 28 November 2026. Same repaired image (`2026112300`), now running headless after a host
out-of-memory event; userdata and owner setup survived.

- Implemented and unit-tested: `open_app` waits a bounded time for the launched app's tree
  (unknown, not blank, if it never shows); the session Screen port settles its tree and re-reads
  an unchanged tree after input; step records carry the observed package, node count and grant;
  the fixture handles an already selected Stopwatch tab; the API-37 suite driver.
- Tested, adapter only: the fixture passed 9 of 10 runs, including all three warm runs from a
  selected Stopwatch tab (the other run was a `NotVisible` start right after install).
- Tested, one task, launch-wait build: Flash Lite "Run the stopwatch." passed 1 of 5 (three
  `target_resolution` stops after a screensaver detour, one HTTP 429). No grant denial recurred.
- Tested, eligible subset, settling build: **3 of 11 combined, 4 of 11 state, 0 false done.**
  Four runs ended on the model rate limit. 19 of the 30 are not eligible (table below).
- Planned: an approval protocol, display-aware checkers, a second target group, model-call
  pacing, and the post-transition blank observation.

See the [28 Nov record](log/2026-11-28-launch-wait-and-api37-suite.md).

Status, 27 November 2026. The repaired image (`2026112300`) is booted and its OS regression test
passed. Setup was repeated through Android UI. The fixture passed 3/3 cold; the Flash Lite pilot
passed 2 of 3, and the failed run's scroll was rejected before dispatch (root cause found on
28 November: the observation after `open_app` was blank). See the
[27 Nov record](log/2026-11-27-repaired-emulator-validation.md).

Earlier status, 23 November 2026. Pilot test driver and compatible image built; image delivered with a
matching checksum. The visible AVD booted, APKs installed and assistant-role permission grant
verified. Clock policy/consent and six live adapter checks passed. The first model-driven APK run
stopped before any action with Gemini Pro HTTP 429; its quota is zero. The owner then authorized
Flash Lite. A warm tab request completed, but a fresh stopwatch task and a model-free adapter
check failed with empty observations. An OS accessibility-proxy initialization race was reproduced;
the focused emulator fix built successfully, but transfer stopped on ThinkPad disconnection and
runtime validation remains pending. No 30-task result is available. See the
[pilot record](log/2026-11-23-computer-control-pilot.md) and
[Flash Lite findings](log/2026-11-23-flash-lite-computer-control.md).

The Pixel has the compatible APK installed, but owner setup and live session validation remain
pending. Its adapter reads accessibility/UI trees; screenshot-based model observation is not
implemented. The existing downloaded OS artifacts are Pixel `blazer` images, not emulator
images. A separate compatible emulator image completed on Contabo using the existing source.

## API-37 port of the frozen 30, 28 November 2026

Plan, in order: classify every task on this image without changing its identity or seed;
set up the eligible apps through owner-style UI; check that AndroidWorld's own setup and checker
run over adb; run the eligible subset once per task with Flash Lite; then widen eligibility (an
approval protocol, display-aware UI checkers, a second six-target group). Protected Settings
tasks stay classified. Nothing here touches the held-out 25 in
[ANDROIDWORLD_TASKS.md](ANDROIDWORLD_TASKS.md) §5; the 30 are the practice 20 and rotation 10.

The frozen table is `harness/suites/computer_control_30_api37.json`. Seed-0 goals come from
AndroidWorld's own `generate_random_params` after `random.seed(0)` and `numpy.random.seed(0)`.

| Status | Count | Tasks | Why |
|---|---:|---|---|
| eligible | 11 | ContactsAddContact, ClockStopWatchRunning, ClockStopWatchPausedVerify, SimpleCalendarAddOneEvent, SimpleCalendarEventsOnDate, MarkorCreateNote, MarkorCreateFolder, MarkorEditNote, MarkorMoveNote, ExpenseAddSingle, RecipeAddSingleRecipe | App installed or present; AndroidWorld setup and checker run over adb, except both stopwatch tasks (adapted Clock-preference checkers). The calendar tasks run with the real 2026 device date (see below). |
| policy_blocked | 5 | OpenAppTaskEval, SystemWifiTurnOff, SystemBluetoothTurnOn, SystemBrightnessMax, TurnOnWifiAndOpenApp | Settings/SystemUI are protected Computer Control targets. Seed 0 makes OpenAppTaskEval "Open the settings app". |
| approval_gated | 8 | SimpleSmsSend, SimpleSmsReplyMostRecent, SimpleSmsReply, MarkorDeleteNote, ExpenseDeleteSingle, ExpenseDeleteMultiple2, FilesDeleteFile, SimpleCalendarDeleteOneEvent | Send or delete needs the owner's fingerprint on the real prompt; no approval protocol exists yet. SMS also needs the protected SMS role and a default-display foreground check; FilesDeleteFile's goal names AndroidWorld's storage label. |
| checker_needs_adaptation | 1 | ClockTimerEntry | AndroidWorld reads the entered digits from the default display's UI tree; the task runs on the session display and the entry is not persisted. |
| setup_unavailable | 1 | MarkorCreateNoteFromClipboard | `clipper.apk` targets SDK < 4; API 37 refuses it (`INSTALL_FAILED_DEPRECATED_SDK_VERSION`). |
| app_unavailable | 1 | CameraTakePhoto | AndroidWorld's `com.android.camera2` is absent; no substitute camera is used. |
| time_dependent | 1 | SimpleCalendarNextMeetingWithPerson | "Next meeting" is relative to AndroidWorld's fixed 15 Oct 2023 date. |
| deferred_session_limit | 2 | NotesIsTodo, RetroPlayingQueue | Joplin and Retro Music install (target SDK 33) but would be a seventh and eighth target. |

**Device date.** AndroidWorld sets the device to 15 October 2023. Here the model calls leave the
device over TLS, so the driver keeps the real date and sets only UTC and 24-hour format, as
AndroidWorld's `setup_datetime` also does. Calendar tasks whose dates are absolute stay eligible,
with the deviation recorded per task.

**Suite driver (implemented, unit-tested).** `harness/apk_suite.py` loads the frozen table and
refuses tasks that are not eligible. For each task it builds AndroidWorld's task class (seed 0),
roots adbd, runs AndroidWorld's `initialize_task` through an adb-only environment, force-stops the
task's apps and unroots adbd. Then it sends only the goal string to the APK (`apk_control.run_goal`;
15-step APK budget). Afterwards it roots adbd again and runs the checker: AndroidWorld's
`is_successful`, or the adapted Clock check. For the answer task, the raw sourced answer text goes
into the checker environment only. The record keeps agent done, agent-complete (done or delivered
answer), independent state, combined pass and false done (agent-complete while the check failed).
UI-tree reads by a checker raise an error rather than read the wrong display. Setup errors never
start the agent; there are no retries. Before the agent starts, the driver refuses a device clock
moved by setup (AndroidWorld's inner tasks reset it) and runs any listed operator step
(ContactsAddContact: Settings > Apps > Contacts storage > Device only, through the UI, because
clearing the contacts provider resets it and the editor otherwise opens that protected picker).
AndroidWorld's Google Clock and Contacts names map to the AOSP apps on this image; no other app
is substituted. Fifteen host tests cover ordering (root only around setup and checking), false
done, answer delivery, the adapted checks, the date guard and refusal of ineligible tasks.

```bash
~/android_world/.venv/bin/python -m harness.apk_suite --serial emulator-5554 --out-dir DIR
python3 -m harness.apk_suite --list
```

**Result (tested, one counted run per task).** Combined pass: ContactsAddContact,
ClockStopWatchRunning, ClockStopWatchPausedVerify. State also passed for MarkorEditNote without an
accepted done. Rate limit (HTTP 429): MarkorCreateNote, MarkorCreateFolder, MarkorEditNote,
MarkorMoveNote. Platform closed the session: ExpenseAddSingle. Agent-side: both calendar tasks
(a blank observation after a tap; a judge false block) and RecipeAddSingleRecipe (repeat-loop
stop). A first pass was invalidated by three driver/environment faults, fixed before the nine
affected tasks were run again; details and per-task numbers are in the
[28 Nov record](log/2026-11-28-launch-wait-and-api37-suite.md).

## Platform deliverable

The OS recipe supplies a separate virtual-device build from the pinned Android 17 source
with the installed-assistant Computer Control patches. The ARM64 Pixel factory/OTA image is
not an emulator image. Use a supported virtual-device target from that source tree; Cuttlefish
x86_64 is a candidate, not yet validated against this GrapheneOS pin. Record target, release
configuration, source/patch identities, image hashes and matching host tools. Keep the existing
API-33 baseline intact and run only one virtual device at a time, with a visible display.

Require the extension v5 shared library, permission role grant, centralized owner switches,
consent UI, secondary-display support and virtual input. Verify the ordinary assistant and
target fixtures first, including refusal and revocation. A userdebug build may support external
benchmark setup/checking, but the agent must use ordinary app authorization and session APIs.

The ThinkPad currently has about 66 GiB free and 31 GiB RAM. Hosting an image and compiling a
whole OS are separate resource requirements; no local full OS build is configured here.

## App and benchmark deliverable

1. Prove one harmless session on the already-upgraded Pixel: tree observation, navigation,
   Stop, and target withdrawal, after owner selection and consent.
2. Install the same app source on the virtual device and prove the same basic contract.
3. Adapt AndroidWorld setup and independent checks to API 37. Upstream documents API 33;
   app/data formats, permissions, sensors, and task assumptions need compatibility checks.
4. Add a test driver that submits tasks to the APK and collects results. The existing
   `harness/core_bridge.py` observes through AndroidWorld and acts through ADB; running that
   unchanged would not evaluate the APK's Computer Control adapters. Keep setup and independent
   checking outside the production agent. Do not feed checker state to the agent.
5. Freeze the previous 20 practice plus 10 rotation task identities, seeds, model, policy and
   limits. Audit eligibility first: Settings, PermissionController and consent UI are protected
   targets. Label affected tasks policy-blocked rather than weakening gates or silently replacing
   them. Hardware-dependent tasks and UI-only verifiers may also need separate classifications.
6. Run a small compatible pilot, then attempt the frozen suite, reporting task completion,
   independent state, policy blocks, environment errors, calls and elapsed time separately.

Approval tests need their own protocol. Simulated approvals in an isolated benchmark cannot
establish physical biometric security; never add a production approval bypass to run a suite.
Use the same connected model first to isolate adapter behavior. Emulator timings cannot establish
Pixel Tensor G5 NPU performance. Screenshots/vision remain separate future implementation work.

References: [OS integration contract](../../ballistic-os/docs/assistant-integration.md),
[AndroidWorld](https://github.com/google-research/android_world),
[AOSP virtual-device build](https://source.android.com/docs/setup/start).

## Pilot driver implemented, 22 November 2026

`harness/apk_control.py` provides read-only `preflight` and a single-goal `run`. The separate
`androidTest` APK contains `ComputerControlBenchmark`; no test endpoint or approval bypass
is added to the production application. Both host and instrumented code refuse physical devices.
The driver requires API 37, the extension library, installed APK/test runner and assistant role,
then checks app policy and real session availability. It uses the ordinary AppGraph/PromptSession
and real Rust engine, with the existing model and approval ports. No ADB input is used for the
agent. Starting instrumentation restarts the app: complete setup beforehand, including owner
consent. A consent-needed result stops without accepting the prompt. A session is closed at exit.

The pilot keeps the app's current **15-step** limit and conversation routing. Each run clears
the test emulator's conversation history, but retains policy and models. Results distinguish
finished/error/timeout and include the core report and steps; `finished` is not a success verdict.
Independent state remains `not_checked`. This is not yet the 30-task AndroidWorld suite driver,
and its fixed budget is not directly comparable with the prior complexity-based task budgets.
The test instrument does not simulate fingerprints; requested action approvals still need the
owner. A host timeout force-stops only the already-verified emulator app, records uncertainty,
and never automatically repeats an action.

Build the target app and its separate instrumentation APK with the same signing identity:

```bash
cd apps/assistant
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

After installing both APKs on a compatible virtual device and completing normal owner setup:

```bash
python3 harness/apk_control.py preflight --serial emulator-5554
python3 harness/apk_control.py run --serial emulator-5554 \
  --goal 'Open Clock and show the Stopwatch tab. Do not start it.'
```

Five host driver tests passed (physical-device/API-33 refusal, shell quoting, preflight semantics,
and bounded timeout without retry). The instrumentation APK compiled on the ThinkPad using the
existing freshly rebuilt core/native artifacts. A read-only preflight against the Pixel confirmed
that run eligibility is refused; no test was installed or executed on the phone. No live session,
model call, AndroidWorld checker, or OS image build is implied by these checks.

Built test APK: `ballistic_runs/cc_emulator_20261122/app-debug-androidTest.apk`, SHA-256
`f5d41851c448c69461ca09134927ea08d84d104b64bdd89fab4486037773d64f`.
Its inspected manifest targets `app.ballistic.assistant` and registers the expected custom
instrumentation runner. Final test-APK build passed in seven seconds after the metadata update.
The existing signed Pixel APK is unchanged. The original 30 task names were recovered from the
ThinkPad's `suite_core_20261115-204902.tsv` and recorded in
`harness/suites/computer_control_30.json`; generated requests and API-37 eligibility remain to check.

## Image target identified

The pinned manifest includes Goldfish at `160f9d95c50827b12e5ab1c49faa5d6502511e0b`.
Its `AndroidProducts.mk` declares `sdk_phone64_x86_64`, device `emu64x`. This is the first
candidate because it runs in the SDK emulator used by AndroidWorld (including its gRPC server),
whereas Cuttlefish would also need an environment-launcher adaptation. On 22 November the owner
authorized this session to use Contabo. `lunch sdk_phone64_x86_64-cur-userdebug` passed and
`RELEASE_PACKAGE_COMPUTER_CONTROL` returned `true`. A persistent systemd build now targets
`emu_img_zip` and the public compile stub, using a separate output directory and retaining Pixel
outputs. That build completed successfully on 23 November at 05:46:51 UTC after two earlier
build-path failures. Transfer, SHA-256 checking, extraction and boot passed. The runtime exposes
the extension feature/library and role permission grant; session availability and consent are now
verified through an active Clock session. Broader runtime behavior still requires testing. Live log paths,
source checks and recipe: [OS build record](../../ballistic-os/docs/log/2026-11-22-emulator-build.md).

Sources: [pinned manifest](https://raw.githubusercontent.com/GrapheneOS/platform_manifest/2026110200/default.xml),
[product declarations](https://raw.githubusercontent.com/GrapheneOS/device_generic_goldfish/160f9d95c50827b12e5ab1c49faa5d6502511e0b/AndroidProducts.mk),
[x86_64 phone product](https://raw.githubusercontent.com/GrapheneOS/device_generic_goldfish/160f9d95c50827b12e5ab1c49faa5d6502511e0b/64bitonly/product/sdk_phone64_x86_64.mk).

## Adapter fixture without a model, 23 November 2026

Build/install the matching test APK before selecting `--mode adapter-smoke`. The fixed fixture
runs in the emulator app process using production ComputerControlPlatform/Screen/Hands adapters.
It launches only Clock and taps Stopwatch, retaining existing app read/action rules and normal OS
grants/consent. It never starts or resets the stopwatch, calls a model, grants access or answers
approval prompts. Test setup requires Clock tree/full-input access and the default sensitive-action
approval mode already saved. The fixture uses its own bounded test lifecycle, so it does not test
core decisions, UiPolicyHands, hard confirmations, full AppGraph Stop or OS permission revocation.

```bash
python3 harness/apk_control.py run --serial emulator-5554 --mode adapter-smoke \
  --goal 'Fixed Clock adapter fixture; no model calls' --timeout 90
```

The goal is only a record label in this mode; it cannot change the fixed operations. The result
identifies `scripted_adapter_fixture`, `model_calls: 0` and individual checks. Tree readback uses
the same Screen adapter, not an independent AndroidWorld state checker. Keep these results
separate from agent runs. Host tests: seven passed, including physical-device refusal for both
modes, unknown-mode refusal and bounded timeout cleanup.

## Independent stopwatch pilot check, 23 November 2026

`python3 -m harness.apk_clock_pilot --serial emulator-5554 --out-dir PATH` resets only the
disposable emulator's Clock data, submits the frozen “Run the stopwatch.” goal to the real APK,
and checks Clock's device-encrypted preferences from the host afterward. It requires the existing
virtual-device preflight plus a userdebug build and checker access before resetting data.
It cannot run on the Pixel. It never supplies checker state to the app, retries an action,
changes a grant or approves a prompt.

The pinned AOSP Clock source defines missing `sw_state` as reset and ordinal 1 as running.
The supplemental record keeps agent done, independent state and combined pass separate;
malformed/unknown state is a checker error. This is an adapted state check, not the original
AndroidWorld UI checker or a side-effect audit. Twelve host tests pass across the APK driver and
this checker. Live execution of this new combined driver is pending the repaired emulator image
and restored ThinkPad SSH connectivity; earlier preference checks were manual.

Live execution, 27 November 2026, repaired image: three runs. Two passed on agent done,
independent state and combined; one failed when core rejected a scroll before dispatch.
Details are in the [27 Nov record](log/2026-11-27-repaired-emulator-validation.md).
