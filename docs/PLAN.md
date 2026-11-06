# Plan of attack and repo layout

No dates. Order matters; each step ends with something you can see. Components are named for what
they are: assistant app, model host, policy service, gates, harness.

## Steps

### 1. Harness: the agent loop on the laptop
Emulator (Android 17 image) + ADB. Python loop: screenshot + UI tree → model → tap/swipe/type.
llama.cpp server with a 4B text + 3B vision model (or Gemma 3n E4B for both); optional cloud
endpoint for faster iteration. AndroidWorld installed, 10–20 task subset.
**You see:** the emulator being driven by the model; a baseline score ("9/20 passed, 38 s/step").
Resources: ~9.5 GB RAM with the emulator, ~30–35 GB disk.

### 2. Core library: the agent logic, platform-independent
Planner/reader split, tool schema, skill record/replay, backend routing (OpenAI-compatible client),
retries. Injection test suite. Used by the harness now and by the app later.
**You see:** injection tests passing on the emulator (hostile message → summary, no action); a
task replayed from a saved skill with zero model calls; backend switched by one config line;
AndroidWorld score moving up.

### 3. Assistant as an ordinary app (no privileges)
Android app (Gradle): voice in/out, command gate (biometric/passkey before any command), chat
overlay, settings, action log. **Models in the app** behind the core library's backend interface
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

### 6. Model host (v2, optional)
Fill Android's empty `OnDeviceIntelligenceService` / sandboxed-inference socket: isolated process
in the `isolated_compute_app` domain (needed for GPU), LiteRT / llama.cpp, STT (whisper), TTS
(Piper), model download + verify. Do this only when one of its reasons applies: sharing one
resident model across apps, OS-enforced "STT never leaves the device", or a home for GrapheneOS's
planned open STT/TTS. Until then the app keeps its models.
**You see:** the same assistant with no model in the app; model switch in Settings.

### 7. Policy service in the OS (v2, when needed)
Move `core/policy` behind an AIDL in `system_server` so the app cannot bypass grants, prompt
assembly or the broker; wrap its Computer Control and AppFunctions calls. Backend indicator chip in
the status bar. Do this when v1 shows a concrete bypass the OS must close or upstream asks for it.
**You see:** a bank app marked "notifications only, device-only" being refused below the app; the
injection, data-injection and command-gate suites green on the device.

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
Steps 6 and 7 are v2 and conditional.

## Code classes

- **A. OS patches meant for upstream** (small, generic, written as hardening deltas on AOSP, never
  replacements): Computer Control build flag + user allowlist + Settings page; AppFunctions caller
  gate; user-selectable `OnDeviceIntelligenceService` provider; generic SELinux domains.
- **B. OS-side code that stays in our build** (v2): policy service (`services/core/.../assistant/`),
  AIDL, SystemUI indicator, device/vendor config (bundled models, Updater URL), Computer Control
  limit patches, InputDispatcher rule only if window-side filtering proves insufficient.
- **C. Apps** (bulk of the work): assistant app with policy library and models (v1), model host
  (v2), system keyboard for typing, core library, confirmation window.
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
