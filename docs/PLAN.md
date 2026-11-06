# Plan of attack and repo layout

No dates. Order matters; each step ends with something you can see. Components are named for what
they are: assistant app, core, gates, harness. Ballistic is always an app; the OS side is gates
only (decision 7 Nov 2026), which is why steps 6 and 7 below are dropped.

## Steps

### 1. Harness: the agent loop on the laptop
Emulator (Android 17 image) + ADB. Python loop: screenshot + UI tree → model → tap/swipe/type.
llama.cpp server with a 4B text + 3B vision model (or Gemma 3n E4B for both); optional cloud
endpoint for faster iteration. AndroidWorld installed, 10–20 task subset.
**You see:** the emulator being driven by the model; a baseline score ("9/20 passed, 38 s/step").
Resources: ~9.5 GB RAM with the emulator, ~30–35 GB disk.

### 2. Core library: the agent logic, platform-independent
Design agreed 7 Nov 2026 (`core/README.md`; survey in `docs/AGENT_SECURITY_SURVEY.md`). Filtered
UI tree, one planner call per step, reader that returns typed fields, source tags on every value
with a taint rule and Rule of Two in code, deny-only judge, verifier before "done", policy and
confirmation list, skill record/replay, backend routing (OpenAI-compatible client). Injection
test suite. Used by the harness now, by the Android app in step 3 and by an iOS app later; one
implementation shared by every host (Rust proposed).
**You see:** injection tests passing on the emulator (hostile message → summary, no action; a
forged number stopped at a confirmation that names its source); a task replayed from a saved
skill with zero model calls; backend switched by one config line; the 20-task score against the
13/20 baseline, on Gemini and on a local model, with prompt tokens per call.

### 3. Assistant as an ordinary app (no privileges)
Android app (Kotlin shell around the core library): voice in/out, command gate (biometric/passkey
before any command), chat overlay, settings, action log. **Models in the app** behind the core library's backend interface
(LiteRT-LM GPU and llama.cpp CPU), model picked per hardware. **Policy in the app** (`core/policy`:
grants, planner/reader prompt assembly, confirmation broker, log). Confirmation window built as in
SPEC §7.1 (biometric-bound per-operation key; window drops injected events). Actions limited to
what any app may do: notification inline replies, wa.me / tg:// links, calls, alarms, intents.
**You see:** a working messaging assistant on your current phone ("what did I miss on Signal?",
"tell Ali I'm late" → draft → fingerprint → sent). Everything later versions reuse, and the whole
of what would ship on stock Android if Google opens the gates. Measure step time per model here
and pick the v1 model.

### 4. Own the OS pipeline
Cloud build box (64 GB+ RAM). Build stock GrapheneOS for the dev Pixel, generate keys offline,
flash, relock the bootloader with our key, serve one OTA from our server. No custom code.
**You see:** a Pixel booting our signed build with a locked bootloader and updating from our server.

### 5. First gate: user-controlled Computer Control
Three small changes, each its own commit: (a) enable `computer_control_access` and
`computer_control_per_app_consent` in the `cp2a` release config (they are DISABLED/READ_ONLY in
AOSP and GrapheneOS; the service throws without them); (b) include the AOSP consent app
(`RELEASE_PACKAGE_COMPUTER_CONTROL`) or expose the app op in Settings; (c) a user-edited allowlist
store + Settings page as a delta on the existing DeviceConfig/file lookup; keep the assistant-role
requirement. Build the assistant app as a priv-app with the permission allowlisted and grant it the
role. Swap the harness's ADB transport for a real `ComputerControlSession`. Check GrapheneOS sepolicy
lets system_server use `/dev/uinput`.
**You see:** the assistant opening apps in the system's virtual display and completing a task on
the real phone, with the system consent dialog in front. First "it works on the phone" moment.

### 6. Model host in the OS: dropped (7 Nov 2026)
Ballistic is always an app; models stay in the app. The research notes on what an OS host would
have needed are kept in SPEC §4.2.

### 7. Policy service in the OS: dropped (7 Nov 2026)
Policy, prompt assembly, provenance, the judge and the confirmation broker live in core, inside
the app. The injection, data-injection and command-gate suites run against the app on the device
as part of step 8.

### 8. Any app: the capability suite
~20 apps across categories (AndroidWorld subset + ours: messaging, email, maps, shopping,
delivery, bank read-only, browser, a Flutter app, a React Native app, a game). Skills library on
device, per-app device-only rules. Record which apps detect or block Computer Control (its input
devices are identifiable in one call) and surface that to the user; no stealth. Patch Computer
Control's limits (one session, six target apps, both hardcoded) only where the suite shows they
bite.
**You see:** a pass rate across real apps, and skills replaying day to day.

### 9. CI: rebase bot
On each GrapheneOS tag: rebase forks, build, flash the CI Pixel, run capability + injection
suites, publish OTA.
**You see:** an upstream release reaching your phone within days without anyone touching it.

### 10. Upstream round one
Send the Computer Control gate (flag + allowlist delta + Settings page) to the GrapheneOS team
with the demo. Separately and later: the model-host socket selection. Never bundle the two asks.
The §7 questions in `docs/CONTEXT.md` go to their forum before step 4, not after step 9.

### 11. Hardware and model step-up
Pixel 10 Pro; larger resident model; TPU delegate tuning; home-GPU planner tuned; wake word on
the audio DSP.

### 12. Distribution decision
Personal only, public signed builds, or app-only on stock GrapheneOS if upstream lands.

Steps 1–3 need only the laptop and any Android phone and produce the complete v1 app. Step 4 is
the first that needs the Pixel and a build box; it is development plumbing, not a release.
Steps 6 and 7 are dropped (app-only decision, 7 Nov 2026).

## Code classes

- **A. OS patches meant for upstream** (small, generic, written as hardening deltas on AOSP, never
  replacements): Computer Control build flag + user allowlist + Settings page; AppFunctions caller
  gate; generic SELinux domains.
- **B. OS-side code that stays in our build** (small): device/vendor config (Updater URL),
  Computer Control limit patches, InputDispatcher rule only if window-side filtering proves
  insufficient. No policy service, no model host (dropped 7 Nov 2026).
- **C. Apps** (bulk of the work): core library (shared brain, one implementation for Android, iOS
  and the harness), assistant app with policy and models, system keyboard for typing, confirmation
  window.
- **D. Tooling**: harness, test suites, build/sign/OTA scripts, CI.

## Repo layout

Two repositories (decided 6 Nov 2026; replaces the earlier monorepo-plus-forks layout):

- **`ballistic`**: `apps/`, `core/`, `harness/`, `tests/`, `docs/`. The app builds with Gradle and
  installs on any Android.
- **`ballistic-os`**: `MANIFEST` (pinned GrapheneOS tag), `patches/<aosp-repo>/NNNN-*.patch`
  (one commit per gate, `git format-patch` output), sepolicy and device config, `scripts/`
  (sync, build, sign, ota, ci), `docs/` (keys, flashing).

No forks of AOSP repositories. A fork is created only when a patch is submitted upstream as a
pull request, and is generated from the patch files at that moment. Rebasing onto a new GrapheneOS
tag = `sync.sh` applying the patches; a patch that fails to apply is the conflict to resolve.
