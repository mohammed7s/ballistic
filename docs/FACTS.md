# Research and evidence

Dated research preserved from the former CONTEXT.md. These are historical findings, not current compatibility promises. Original section references refer to the [handoff snapshot](log/2026-11-07-before-reorganization-context.md). Later entries can supersede earlier ones.

Current support: [PLATFORM.md](PLATFORM.md). Current decisions: [CONTEXT.md](CONTEXT.md). Detailed OS gate research now belongs to [ballistic-os](https://github.com/mohammed7s/ballistic-os/blob/main/docs/FACTS.md).

## Historical verified facts (checked against AOSP `android17-release` source on 5 Nov 2026)

- `android.permission.ACCESS_COMPUTER_CONTROL`: `protectionLevel="internal|privileged"`, behind
  feature flag `android.companion.virtualdevice.flags.computer_control_access`.
  `MANAGE_COMPUTER_CONTROL_CONSENT`: `signature`, flag `computer_control_per_app_consent`.
- Computer Control API lives in `core/java/android/companion/virtual/computercontrol/`:
  `ComputerControlSession` (trusted virtual display; `launchApplication`, `requestScreenshot`,
  input by relative coordinates, `handOverApplications`), `ComputerControlAccessibilityProxy`
  (node tree), `ComputerControlConsentManager`, `InteractiveMirror`, `AutomatedPackageListener`.
  Service side in `services/companion/.../virtual/computercontrol/` (`ComputerControlSessionImpl`,
  `ComputerControlAllowlistController`, audio capture/injector, data store, stats).
- **Allowlists come from Google's DeviceConfig** (`allowed_session_owner_apps`,
  `allowed_automatable_apps`, files `session_owner_allowlist.txt`, `automatable_app_allowlist.txt`).
  GrapheneOS never receives DeviceConfig, so the feature is dead code there today. Other flags:
  `computer_control_role_assistant_requirement`, `computer_control_managed_profiles`,
  `computer_control_cross_device`, `computer_control_support_v5`.
- `EXECUTE_APP_FUNCTIONS`: `normal` protection when flag `app_function_access_api_enabled` is on,
  **plus a device allowlist plus user approval**; `internal|privileged` otherwise.
  `EXECUTE_APP_FUNCTIONS_SYSTEM`: `internal|recents|role`.
- `CAPTURE_AUDIO_HOTWORD`: `signature|privileged|role`. `MANAGE_HOTWORD_DETECTION`:
  `internal|preinstalled`. `BIND_VOICE_INTERACTION`: `signature`.
- AOSP has an **empty model-host socket**: `config_defaultOnDeviceIntelligenceService` and
  `config_defaultOnDeviceSandboxedInferenceService` are empty strings in AOSP `config.xml`;
  `USE_ON_DEVICE_INTELLIGENCE` is `signature|privileged`; the sandboxed inference service runs in
  an isolated process. Google fills it with AICore; nobody has filled it with an open host.
- AOSP `InputDispatcher` sets `FLAG_IS_ACCESSIBILITY_EVENT` (0x800) and a separate
  "injected from accessibility tool" MotionEvent flag, so apps can detect accessibility-driven
  automation. What flags Computer Control's injected events carry is **not yet checked**.
- Dev laptop A (i5-1145G7, 8 threads, 14 GB RAM, Iris Xe, ~128 GB free) cannot build AOSP;
  it can run the emulator, AndroidWorld, llama.cpp with a 4B text + 3B vision model (~9.5 GB
  total with the emulator). Headless Chromium for HTML→PDF is at
  `~/.cache/ms-playwright/chromium_headless_shell-1248/`.
- **Step 1 state (5 Nov 2026 evening):** emulator + AndroidWorld installed on dev laptop B
  (i5-7200U 2c/4t, 32 GB) via `harness/setup_emulator.sh`; AVD
  `AndroidWorldAvd` (API 33) boots headless in ~2.5 min; AndroidWorld apps installed
  (`--perform_emulator_setup`; four app first-run dialogs failed to auto-dismiss: chrome, contacts,
  open tracks, vlc). **Laptop B cannot run models next to the emulator**: measured
  qwen2.5:1.5b prompt-eval at 11.6 tok/s and gemma3:4b took >10 min for one T3A prompt (the
  client timed out). Decision: models run on laptop A (Ollama 0.40.14,
  `OLLAMA_CONTEXT_LENGTH=16384`, listening on 0.0.0.0), emulator on laptop B; RTT between
  them over Tailscale is ~175 ms (relayed, not LAN), negligible next to model time.
  `harness/run_task.py` takes `--base_url` for this. Smoke-test task: `ContactsAddContact`, T3A.
- **Step 1 state (6 Nov 2026 afternoon):** first **PASS**: `ContactsAddContact`, T3A,
  `gemini-flash-lite-latest` via Google's OpenAI-compatible endpoint, 11 steps, 96 s, 2–3 s per
  model call (vs 2–3 min per call for a local 4B on laptop A). A free-prompt mode (`--goal`) set a
  7:30 alarm in 10 steps / 80 s. The cloud path is a `--base_url/--model/--api_key` change only;
  no code change beyond tolerating Gemini's list-shaped error replies. Gemini free tier:
  `gemini-3.8-flash` is 20 requests/day (one task is ~24 calls, so unusable); `gemini-2.5-flash`
  is closed to new keys; `gemini-flash-lite-latest` worked for ~50 calls without a limit error.
  Key lives in `~/.ballistic_env` on laptop B, never in the repo. Emulator now runs with a window on
  laptop B's own desktop (`WINDOW=1`, GDM Xauthority), boots in 34 s with host GPU.
- **Step 1 baseline (6 Nov 2026, 18:14–18:47, measured):** `harness/run_suite.sh`, 20 AndroidWorld
  tasks, T3A (UI tree, no screenshots), `gemini-flash-lite-latest`, one emulator on laptop B.
  **13 / 20 PASS.** Agent time 1,496 s total (median 66.4 s/task); 281 model calls (T3A makes
  ~2 per step: action + summary), 3.0 M prompt tokens, mean 10.7 k / call, max 43 k / call. No
  quota (429) errors. Laptop A ↔ B Tailscale dropped twice during the run; 4 `Error calling LLM`
  steps (EventsOnDate 1, MarkorDeleteNote 3) are network, not model. Table:
  `~/ballistic_runs/suite_20261106-181401.tsv`, per-task JSON beside it.

  | task | result | steps | s | prompt tok/call | cause of FAIL (from the JSON) |
  |---|---|---|---|---|---|
  | ContactsAddContact | PASS | 8 | 57 | 12.0 k | |
  | ClockTimerEntry | FAIL | 10 | 64 | 8.6 k | model: lost track of the timer keypad's shift-left entry; hit 10-step limit |
  | ClockStopWatchRunning | PASS | 4 | 21 | 5.8 k | |
  | OpenAppTaskEval | PASS | 2 | 10 | 4.9 k | |
  | SystemWifiTurnOff | PASS | 9 | 74 | 8.3 k | |
  | SystemBluetoothTurnOn | FAIL | 10 | 102 | **1.4 k** | UI tree: Settings returned an (almost) empty tree on every open; model waited/reopened until out of steps |
  | SystemBrightnessMax | FAIL | 7 | 70 | 5.9 k | model + action space: tapped the slider centre twice (T3A has no drag), then declared complete |
  | TurnOnWifiAndOpenApp | PASS | 6 | 69 | 6.9 k | |
  | SimpleSmsSend | PASS | 7 | 75 | 12.9 k | |
  | SimpleSmsReplyMostRecent | PASS | 7 | 47 | 13.7 k | |
  | SimpleCalendarAddOneEvent | FAIL | 14 | 90 | 12.7 k | model: date/time/title/description right, never set the 30-min end time, declared complete |
  | SimpleCalendarEventsOnDate | FAIL | 10 | 172 | 18.2 k | model (browsed Oct/Nov 2025; device date is Oct 2023) + network (92 s first step, LLM error on step 10); re-run alone |
  | MarkorCreateNote | FAIL | 7 | 48 | 15.2 k | model (inferred): typed the name without `.txt`, Markor defaults to `.md`; file not checked before next task's setup |
  | MarkorDeleteNote | PASS | 8 | 191 | 9.0 k | (slow: 3 network errors, retried) |
  | MarkorCreateFolder | PASS | 5 | 29 | 12.1 k | |
  | ExpenseAddSingle | FAIL | 12 | 82 | 13.8 k | harness: `open_app("Expense")` errored twice (app is "pro expense"), 4 steps lost; budget ran out on the SAVE step |
  | ExpenseDeleteSingle | PASS | 5 | 36 | 6.1 k | |
  | RecipeAddSingleRecipe | PASS | 11 | 171 | 16.8 k | |
  | FilesDeleteFile | PASS | 8 | 64 | 8.3 k | |
  | CameraTakePhoto | PASS | 3 | 25 | 4.2 k | |

  Failure split: 5 model (Timer, Brightness, CalendarAdd, CalendarOnDate, MarkorCreateNote),
  2 harness (Bluetooth empty tree, Expense app name). Three of the model failures are false
  "complete" claims, which argues for a verify step before `status: complete`.
- **AndroidWorld `open_app` (verified 6 Nov 2026 in the installed source on laptop B):** app names
  resolve through a fixed regex table, `android_world/env/adb_utils.py` `_PATTERN_TO_ACTIVITY`, not
  through the launcher; any name outside it fails ("Expense" vs the app's label "pro expense").
  The task registry holds 116 tasks (`task_metadata.json`).
- **Build box (6 Nov 2026):** Hetzner Cloud refused (new-account limits: 8 dedicated vCPUs, no
  increase button); Contabo needs ID verification (order pending, may be cancelled). Using
  **vast.ai** hourly: instance 59466158, Belgium, EPYC 7662, 61-core quota, 550 GB disk, ~$0.94/h
  incl. disk, download $2.7/TB. It is an unprivileged Docker container: user namespaces are
  blocked (`unshare -Ur` fails), no VM. vast.ai VM templates failed (host QEMU missing). The web UI
  did not apply the disk size twice (got 32 GB); the `vastai` CLI with `--disk 550` works
  (`~/.local/bin/vastai` on laptop A, account key stored by the owner). Offers with
  `inet_down_cost` near $40/TB are a trap for a ~200 GB source sync.
- **OS build track, plan step 4 (6 Nov 2026, evening; verified on the box unless noted):** recipe
  and scripts in `ballistic-os` (`MANIFEST` = `2026110200`, `scripts/{sync,build,sign}.sh`,
  `docs/keys.md`). 2026110200 is still the current blazer release on stable and beta (checked
  `releases.grapheneos.org/blazer-{stable,beta}`). Manifest tag signature verified ("Good git
  signature for contact@grapheneos.org", key `SHA256:AhgHif0mei+9aNyKLfMZBh2yptHdw/aN7Tlh/j2eFwM`).
  **Sync:** 43 min wall, 154 GB with `--depth=1`. `-j32` hit googlesource quota (RESOURCE_EXHAUSTED /
  HTTP 429, 63 projects); `-j8` worked, but repo retried `prebuilts/tools` and
  `tools/tradefederation/prebuilts` with full history (49 GB+ and still going); fetching their pinned
  commits with `git fetch --depth=1` took 13 s. **No user namespaces is not a blocker:** Soong logs
  "Build sandboxing disabled due to nsjail error." and builds. It does break adevtool's dependency
  build, which treats that stderr line as fatal although the build exits 0; re-run with
  `ADEVTOOL_SKIP_DEP_BUILD=1`. adevtool refuses to run as root, so the tree lives under an
  unprivileged `build` user on the box. adevtool pulls the stock `blazer CP3A.261005.005` factory
  image (11.4 GB) and checks the generated vendor module against `vendor-specs/` hashes (throws on
  mismatch); it passed. GrapheneOS 2026110200 builds as `BUILD_ID=CP3A.261005.005`.
  `OFFICIAL_BUILD` only adds the Updater app in this tree (`media_system.mk`); our build leaves it
  unset, so no Updater until we have our own update server. Updates go by `adb sideload` of our
  signed OTA. Our `BUILD_NUMBER` is `2026110600`, so our fingerprint differs from the official one.
  The phone's yellow boot notice shows `sha256(avb_pkmd.bin)` (checked: the official blazer
  `avb_pkmd.bin` hashes to the `4e8ee8f7…` value above).
- **OS build result and test signing decision (7 Nov 2026):** stock tag `2026110200`,
  build number `2026110600`, completed at 2026-11-06 23:57:51 UTC. First attempt failed
  after 7164 s (`Cannot fork`; cgroup PID limit hit, 257 jobs); incremental resume with
  `m -j32` succeeded in 834 s. Unsigned artifacts on the box:
  `/home/build/gos/releases/2026110600/` (`blazer-target_files.zip` 4.3 GiB,
  `blazer-otatools.zip` 512 MiB). Owner explicitly authorized **disposable cloud test keys**
  and direct signed-image download to the ThinkPad, and clarified the Pixel is a test phone.
  Existing Latitude keys stay separate; cloud test keys must not become production keys.
  Cloud signing log: `/home/build/logs/sign-cloud-test.log`; key procedure and exception
  recorded in `ballistic-os/docs/keys.md`. Phone flashing still needs the final backup/go
  confirmation. This build omits Updater; signed OTA sideloading is the update path.
- **First patched OS build launched (7 Nov 2026, verified):** at 00:33:48 UTC (03:33:48
  Amman), after baseline signing finished, the OS patch thread started build `2026110700`
  on existing instance `59466158`. All eleven patches from `ballistic-os` commit `6b936cf`
  (patch contents unchanged from `d02b997`) applied to the three clean repositories at tag
  `2026110200`; resulting trees match the reviewed local implementations. Incremental
  `blazer-cur-user`, `m -j32 target-files-package otatools-package`, preserves `out/`, vendor
  files and baseline `releases/2026110600/`; no signing or phone operations by this thread.
  Supervisor job `ballistic-patched-2026110700` runs as `build`, without automatic restart.
  `/home/build/ballistic-patch-build-2026110700/` holds `build.log`, `status.txt`,
  `build-identity.txt`, patch snapshot, hashes and applied revisions. Successful completion
  finalizes unsigned packages into `/home/build/gos/releases/2026110700/` and writes checksums.
  Soong regeneration observed running; compilation success and Android tests remain unverified.
  Owner requested a prompt to hand monitoring and subsequent build work back to the build agent.
  **Build-track takeover (7 Nov, 00:41 UTC):** existing Supervisor process verified RUNNING,
  41% through incremental tasks. Combined five-minute monitor covers patched build and baseline
  download; it must not stop the instance while compilation/tests/signing/transfers are active.
  No second build launched. Baseline download preserved 369,885,184 bytes after a network drop.
  ThinkPad selected a disconnected Ethernet default route for the cloud IPv4 address; a temporary
  host route (`154.42.3.225/32` via Wi-Fi gateway `10.32.141.239`, interface `wlp5s0`)
  restored access and the existing resumable job continued. This route may need removal or update
  if the Wi-Fi network changes. No global network or emulator changes were made.
- **Patched build completed (7 Nov, verified 01:10 UTC):** `2026110700` finished
  `rc=0 stage=complete` at 01:09:56 UTC after 36 min 8 s. Unsigned `blazer-target_files.zip`
  is 4,543,948,875 bytes; `blazer-otatools.zip` is 536,798,276 bytes. Both pass the recorded
  SHA-256 checks (`ballistic-os/docs/regression-tests.md` contains full hashes and paths).
  Signing with the same disposable cloud test keys started at 01:11:04 UTC; a separate
  `-j32` test job compiles VirtualDeviceServicesTests and focused Settings host-test dependencies,
  then runs two Settings Robolectric classes. Logs/status are in the existing patched job
  directory. Test results, signing completion and on-device Computer Control validation remain
  pending. Baseline install download continues independently; no phone has been flashed.
- **Hardware (6 Nov 2026, corrected same day):** the owner's phone is a **Pixel 10 Pro**
  (`blazer`, SKU GEHN3, serial 56181FDCH0070R, stock Android 17 `CP2A.260805.005`), not a Pixel 10.
  **It cannot be bootloader-unlocked, so it cannot run GrapheneOS or Ballistic as it stands.**
  Verified on the device over adb: `fastboot flashing get_unlock_ability` = 0; Settings shows
  "Connect to the internet or contact your operator"; single owner user, no device/profile owner,
  no `no_factory_reset`, Wi-Fi validated, GMS enabled. Per AOSP `OemUnlockPreferenceController`
  that leaves only the carrier-allow flag in the IOemLock HAL (Titan M2) as the cause. User 0 also
  carries a base `no_oem_unlock` restriction not set by device policy. The device is **not
  SIM-locked**: it has `FEATURE_TELEPHONY_CARRIERLOCK` and the allowed-carriers list is empty. The
  phone is in India; it is most likely an imported carrier unit (carrier not yet identified).
  **Update, later on 6 Nov 2026:** the owner got OEM unlocking enabled (how is not recorded); the
  `no_oem_unlock` restriction was gone and `get_unlock_ability` = 1. Stock GrapheneOS
  `2026110200` (`blazer`) was then installed with the CLI method from laptop B (ThinkPad, over
  SSH): signature verified against `allowed_signers`, `flash-all.sh` clean, bootloader relocked.
  Expected boot key hash: `4e8ee8f717754052198ca6d2d3aaa232e2461b4293c0d6f297e519cc778de093`.
  This is stock GrapheneOS, not a Ballistic build; flashing our own signed build later means
  unlocking and wiping again.

## 4b. Findings from source research on 6 Nov 2026 (public AOSP/GrapheneOS source; nothing run on a device)

**Computer Control on GrapheneOS**
- Flags live in `core/java/android/companion/virtual/flags/flags.aconfig`; none is fixed-read-only, so
  the release config decides. In `cp2a` (android17-release, and GrapheneOS's `cur` which inherits it)
  `computer_control_access`, `computer_control_per_app_consent` and `computer_control_support_v5` are
  **unlisted → DISABLED, READ_ONLY**. `role_assistant_requirement`, `consent`, `user_restriction`,
  `managed_profiles`, `activity_policy_strict`, `typing` are ENABLED. Only `trunk_staging` enables the
  master flag. `VirtualDeviceManagerService.requestComputerControlSession` throws when the flag is off,
  and `ACCESS_COMPUTER_CONTROL` is not even defined (`android:featureFlag`). **Removing the allowlists
  is not enough; the flag must be enabled at build time** (add a `*_flag_values.textproto` under
  `aconfig/cp2a/android.companion.virtualdevice.flags/`).
- Session owner must be a priv-app with the permission allowlisted (AOSP grants it only to shell,
  "test only") **and** hold `ROLE_ASSISTANT`. No Google Play services, GMS component or Google
  certificate dependency found.
- Allowlists: DeviceConfig namespace `computer_control`, keys `allowed_session_owner_apps`,
  `allowed_automatable_apps`, `blocked_automatable_apps` (`pkg:sha256` or `*`; `PREINSTALLED` matches
  system apps); fallback files under `/data/system/computercontrol/`. A newer `AllowlistManager`
  (`services/allowlist`) also defines IDs 3–5 for Computer Control; which path is live in cp2a must be
  re-checked (answered 7 Nov: DeviceConfig/files, §4c). Consent screen is the AOSP `packages/VirtualDeviceManager` app (platform-signed), only
  built when `RELEASE_PACKAGE_COMPUTER_CONTROL` is true (trunk_staging); a GrapheneOS forum user
  reports it appeared in build 2026092500. App op `COMPUTER_CONTROL` set to MODE_ALLOWED skips it.
- Other preconditions: `POST_NOTIFICATIONS`, visible window, device unlocked, params version ≥ 17,
  `MAXIMUM_CONCURRENT_SESSIONS = 1` (hardcoded, TODO b/419548594), no managed profiles.
- Client library `com.android.extensions.computercontrol` (`libs/computercontrol`) is not in any
  AOSP product makefile; the framework API is `@hide`.
- Display/input: ordinary VirtualDevice with a `VirtualDisplay` (TRUSTED | ALWAYS_UNLOCKED), virtual
  touchscreen + dpad via `/dev/uinput`. No HAL dependency. Whether GrapheneOS sepolicy allows
  system_server to use `/dev/uinput` is unchecked (answered 7 Nov: it does, §4c).
- **Open question 4 answered:** injected events do **not** carry `FLAG_IS_ACCESSIBILITY_EVENT`, but the
  virtual devices are trivially fingerprintable: name `"<agentPkg>:<session>-tscr"` / `-dpad`,
  vendor 0x0000, product 0xCC03 / 0xCC01, `BUS_VIRTUAL`, non-zero display id. Any app can read this via
  `InputDevice.getDevice(event.getDeviceId())`. Typed text goes through `commitText`, not key events.
- **Open question 5 answered:** `MAX_TARGET_PACKAGES = 6` is a hardcoded constant in
  `ComputerControlSessionParams.java` (enforced for params version ≥ 17). Which apps are allowed is the
  allowlist.
- No public demo of Computer Control outside Gemini found. GrapheneOS on the forum (1 Oct 2026,
  discuss.grapheneos.org/d/42470) called the VirtualDeviceManager infrastructure "harmless" and said
  they will not strip it; no statement on agent automation.

**Android Protected Confirmation**
- Mechanism: Keystore key with `setUserConfirmationRequired`; TEE ConfirmationUI draws the prompt;
  `onConfirmed` returns a CBOR blob (prompt text + extraData) that is the only thing the key may sign.
  Verifier ("relying party") is normally a server; local verification is just a signature check.
- Limits: `MAX_MESSAGE_SIZE = 0x1800` (6144 bytes incl. CBOR); allowed character set undocumented
  (newlines, emoji rejected); one prompt at a time; UI not customisable; **`isSupported()` returns
  false whenever any accessibility service is enabled**.
- **Device support: HAL removed from gs101/gs201/zuma (Pixel 6/7/8) in android15-qpr1 (gs101 commit
  966663c7, "APC/TUI: remove support for APC"); Pixel 9 (zumapro) never had it (commit bcd94dd);
  GrapheneOS trees have no confirmationui.** Google deprecated the API in Dec 2023 then reverted the
  deprecation; HAL optional in all compatibility matrices; no production app known to use it.
- Alternatives: (1) BiometricPrompt + CryptoObject with a per-operation key
  (`setUserAuthenticationParameters(0, BIOMETRIC_STRONG)`), fingerprint matched in TEE, injected
  touches cannot produce the auth token; (2) SystemUI window that rejects events with
  `POLICY_FLAG_INJECTED` / deviceId `VIRTUAL_KEYBOARD_ID` (-1) / `FLAG_IS_ACCESSIBILITY_EVENT` / not
  from the internal touchscreen, via `InputManager.verifyInputEvent()`; (3) KeyguardManager credential
  confirm (PIN is injectable; fallback only). Recommended: (1) presented in a window built per (2).

**AppFunctions**
- Adoption (Nov 2026): Google Calendar/Keep/Tasks and Samsung apps (reported); no WhatsApp, Uber,
  Spotify, bank or Signal. On F-Droid: Tasks.org (verified, `appfunctions:1.0.0-alpha11`), MyBrain.
  AOSP Settings and PermissionController expose device-state functions. Jetpack
  `androidx.appfunctions` 1.0.0-rc01 (4 Nov 2026), API 36+, KSP; no public schema catalogue. Gemini
  integration still private preview. Estimate: AppFunctions alone covers 2–5 % of everyday requests.
- Access: `EXECUTE_APP_FUNCTIONS` normal when `app_function_access_api_enabled`; allowlist via
  `AllowlistManager` ID 2 → `AllowlistProviderService` named by `config_allowlistProviderServicePackage`
  (empty in AOSP). **Fails open**: ERROR_PROVIDER/INVALID_REQUEST/NETWORK are treated as allowlisted.
  Shell override `cmd allowlist add-package-multimap 2 <pkg>:<certsha> '*'`. User-approval flags in
  `AppFunctionAccessService.kt`; whether they are checked on the execute path was not confirmed.

**On-device inference on Tensor**
- No published numbers for any 8B-A1B MoE or any 7–9B dense model on a Pixel. Measured: Pixel 8
  Gemma 4 E2B LiteRT-LM CPU 53 pp / 10.6 tg tok/s, GPU (OpenCL) 427 pp / 11.9 tg; Pixel 8a
  Qwen3-0.6B LiteRT GPU 723 pp / 43 tg; Pixel 10a Qwen3-1.7B llama.cpp CPU 53 pp / 8 tg; Pixel 10a
  llama.cpp Vulkan on Mali 9–17 pp (broken/slow, fence hangs); Pixel 10 PowerVR Vulkan garbage output
  unless COOPMAT and int-dot disabled; Pixel 10 Pro Fold Gemma 4 E2B CPU 10.4 tg (conflicts with the
  ~16 tok/s in §5). Reference S26 Ultra Gemma 4 E2B LiteRT GPU 3808 pp / 52 tg. Estimate for
  LFM2.5-8B-A1B Q4 on Pixel 8 Pro CPU: 80–150 pp / 12–20 tg → a 2k-token UI tree costs 13–25 s per
  step. No LiteRT build of LFM2.5-8B-A1B exists. LiteRT GPU on Mali corrupts numbers in tool-call JSON
  (6–9 % bad JSON); CPU clean.
- TPU: **correction to §5** — GrapheneOS's toggle governs *direct* TPU/GXP access by Google apps; any
  app can use the TPU via NNAPI (deprecated, JIT per token, "slow and not worth it"). Tensor SDK
  (LiteRT NPU) is G5/G6 only, experimental access; NPU models: Gemma3-1B (G5), Gemma4-E2B (G6).
- Memory: no hard native cap for isolated processes; `OnDeviceSandboxedInferenceService` is bound
  with `BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES` (+ `BIND_SCHEDULE_LIKE_TOP_APP` while a
  foreground caller has a job), so other apps are killed first. mmap'd GGUF weights are reclaimable
  file pages; KV cache is not. GPU from the sandbox requires the `isolated_compute_app` domain
  (`isolated_compute_allowed_device` includes `gpu_device`); plain `isolated_app` is denied.
- Hardening: MTE tags only allocations ≤ 128 KiB; no llama.cpp/TFLite/ONNX MTE crash reports found;
  base-OS components cannot opt out of hardening; dynamic code loading blocked for the base OS, so all
  runtime plugins must ship in the image. LiteRT-LM GPU confirmed working on Pixel 10 Pro Fold under
  GrapheneOS (LiteRT-LM#2566); AICore preview does not work on GrapheneOS.
- Battery/thermal: no Pixel measurements found; S24 Ultra GPU clamped to 231 MHz after ~6 iterations;
  decode dominates energy.
- AndroidWorld, phone-sized models (self-reported, screenshot-based): UI-Voyager 4B 81.0 %,
  Holo3.1-4B/9B 71.0 %, MAI-UI-8B 70.7 %, MAI-UI-2B 49.1 %; frontier Qwen3.8-Omni-Flash 87.1 %.
  A text-only UI-tree agent with Qwen3-4B passed 2/5 tasks on a real phone; LFM2-8B-A1B failed
  (multiple actions per turn).


## Later core/harness evidence
 (7 November 2026)

Verified on the Latitude and ThinkPad, unless explicitly described as external research:

- Previous Opus work stopped at its API limit. Harness edits were recovered; Rust had only
  partial filter/action files, with `run` still unimplemented. Opus retry hit the same limit;
  owner approved Codex subagents for the continuation.
- Old interactive chat is stopped. One visible API 33 emulator was connected as emulator-5554;
  no concurrent task runners were active. The earlier 20-task screen recorder had exited with
  **zero screens**, not a benchmark result. Suite discovery now raises an error on no tasks.
- Gemini key/endpoint verified working with `gemini-flash-lite-latest`: a fresh T3A
  **ContactsAddContact PASS, 8 steps, 58 s** (rounded recorder elapsed time), goal Muhammad
  Mohammed / +10891448553. Eight raw-screen fixtures are committed under
  `core/tests/screens/ContactsAddContact/`. Remote log: `~/ballistic_runs/record_contact.log`.
- Rust `run --request` now performs filtered observation, one planner call per valid step,
  a bounded invalid-action retry, validated/resolved actions, confirmation hooks and a fresh
  verifier call before accepting done. `read`/`answer` fail closed as unsupported. **17 tests
  pass**, including no Hands call after denial, invalid actions, false/malformed verification,
  hidden hostile content and scrolling-form field labels. This is an initial loop, **not the
  completed three-layer security design**: typed reader, source propagation/taint enforcement,
  Rule of Two, full policy, deny-only judge, config file and skill execution remain pending.
- `chat.sh --agent core` accepts terminal prompt input and logs each run; `/agent` and `/confirm`
  switch modes. Confirmation remains a benchmark approve/deny policy, not the phone biometric
  gate. `replay_core.py` replays recorded port responses offline; this is debugging replay,
  **not skill replay on a live device**. Scripts keep the API key in the environment.
- Same-screen filter measurement (`python3 harness/measure_filter.py
  core/tests/screens/ContactsAddContact`): T3A screen text **168,365 characters** total versus
  core **9,290**, **94.5% smaller** across eight observations. These are characters, not model
  tokens; rules, app list and history are excluded. Actual full-prompt tokens and core task
  success must be measured on the live run. Form hints stay visible; arbitrary matching content
  is revealed only if its entire string appears at word boundaries in the owner's request.
- Deployment/live core Contacts test was attempted after pushes, but SSH timed out before
  connecting and Tailscale showed the ThinkPad offline. **At that point no live core benchmark existed; the reconnect results below supersede
  that blocker. No new 20-task score exists yet.** Last verified deployed commit is `edfbf55`; local core commits `ebe14e9`
  and `585ae30` are pushed. Reconnect, preserve the remote untracked recorded fixtures before
  pulling their committed counterparts, pull/build/test, then run Contacts, prompt input,
  and the suite sequentially. Do not run chat alongside the suite.
- Reconnection follow-up (7 November 2026): ThinkPad returned online; code pulled and all
  17 Rust tests passed there, release binary rebuilt. First live core Contacts run reached
  the editor then gave up: **FAIL, 4 steps, 16.6 s, 5 model calls**, no HTTP errors. Log
  `~/ballistic_runs/20261106-233502_ContactsAddContact.json`. Root cause: host readiness counted
  the six status-bar nodes while the app window was loading. `7250912` changes readiness to
  require visible foreground-app content; two host regression tests pass. Offline replay of
  the failed run matched all **17 bridge asks**, zero model/emulator calls.
- Retry after the readiness fix: **ContactsAddContact PASS, 8 steps, 65.5 s, 9 model calls**
  (8 planner + 1 verifier), 0 confirmations. AndroidWorld verified Elias Garcia / +19752947256.
  Total prompt tokens **19,423**, mean **2,158/call** (planner-only mean 2,371); the baseline
  Contacts row was ~12,000/call, but different randomized contacts/state and run conditions mean
  this is not a controlled same-input token comparison. No HTTP errors. Remote record:
  `~/ballistic_runs/20261106-233759_ContactsAddContact.json` (filename UTC, local date 7 November).
  Offline replay matches all **34 bridge asks**, with zero live model/emulator calls.
- Opened a visible ThinkPad terminal titled `Ballistic — emulator prompt` running
  `bash harness/chat.sh --agent core --confirm deny`. Guarded actions are denied until the owner
  changes `/confirm approve`; this is harness behavior, not phone biometrics. No suite is running
  concurrently. Next: broader core task comparison and remaining security/reader/skill work.
- External research (not measured on our phone): `docs/PIXEL10PRO_MODELS.md` records the local
  model shortlist and sources. It supersedes broad older claims that the Tensor SDK is only
  EAP, G5 NPU only supports Gemma3-1B, or Vulkan is universally unusable. Tensor SDK Beta was
  announced 19 May 2026; an E2B G5 NPU bundle exists; specific Vulkan workarounds were reported.
  The reported LFM2 phone failure must not be treated as an LFM2.5 measurement. First phone
  comparison: Gemma 4 E2B NPU/GPU/CPU, E4B GPU, Qwen3-4B control. No reliable pp2048 measurement
  on Pixel 10 Pro was found. Verify NPU from the actual GrapheneOS app UID and measure sustained
  correct output, full step latency and memory before choosing a default.

## 4e. Adopted refinements and implementation (7 November 2026)

Owner accepted the PhoneHarness-inspired refinement review, kept the Rust architecture, and
confirmed the terminal prompt is sufficient until the native Android app UI exists. No localhost
prompt UI is planned. Subagents use GPT-6-Astra at the owner's request.

- Implemented: independent AndroidWorld state scoring even when the agent does not report done;
  combined score retained for baseline compatibility; checker faults remain errors/unknown.
  Suite TSV includes both outcomes, failure category, calls/tokens and JSON paths, and preserves
  full per-task logs. Seed 0 is now explicit; original baseline had no fixed seed.
- Implemented in core: shipped contracts for existing input capabilities; host-advertised
  operations/app grants checked before approval or Hands (missing advertisement denies);
  compact available-operation instructions; exact one-use approval bound to resolved HandsOp
  and app; ambiguous execution errors halt instead of retrying; explicitly known non-execution
  permits replanning. Task status, execution status and verification/evidence basis are separate.
  Model screen assessment is labelled as such, never promoted to authoritative readback.
- Validation before deployment: **30 Rust tests + 9 Python tests pass**. New regressions cover
  missing grants, changed operations after approval, no duplicate input after ambiguous timeout,
  independent state success despite agent failure, false completion and checker faults.
  These are host/unit tests, not yet the full emulator side-effect or injection suite.
- SPEC/core design now require semantic capability arguments, same-app routing, receipt/readback
  interpretation, exact recipient/amount/attachment approvals, workflow compatibility/final
  postconditions/recovery and side-effect checks across routes. Current implementation supports
  input operations only; semantic adapters, authoritative production readback, full provenance,
  typed reader, deny-only judge and saved skill execution are still pending. Binding a screen
  coordinate is not yet equivalent to binding the semantic recipient/body visible behind it.
- Full 20-task core comparison started on ThinkPad at 23:49 UTC on 6 November (02:49 local,
  7 November): code commit `a13f59e`, Gemini flash-lite, seed 0, confirmations approved.
  Table `~/ballistic_runs/suite_core_20261106-234941.tsv`; log
  `~/ballistic_runs/core_suite_refinements.log`. Prompt chat was idle and stopped for the suite;
  restore it only after the suite releases the emulator. Astra delegated evaluation is monitoring
  the existing run, not starting another. **Results pending; no full core score yet.**
  Original baseline TSV totals 1,495.7 s, median 66.4 s/task (corrects earlier ~64 estimate).


## Reported facts — not independently established here
 (from docs/press; re-check before relying on them)

- **Gemini screen automation** (Google): beta since Feb 2026 on Galaxy S26 / Pixel 10, Uber,
  DoorDash, Grubhub; US and Korea; runs on Computer Control; cloud model. AppFunctions Gemini
  integration in private preview (May 2026).
- **Doubao Phone Assistant** (ByteDance + ZTE Nubia M153, Dec 2025, China, ~$495, sold out):
  agent granted `INJECT_EVENTS` at OS level; within days WeChat, Alipay, Taobao and banks logged
  users out server-side ("abnormal login environment"); WeChat control withdrawn 3 Dec 2025.
  Second-gen Nubia NaviX Ultra (Sept 2026), China only. In June 2026 Tencent opened a WeChat
  agent-to-agent channel to Honor/Huawei/Xiaomi/Oppo/vivo "on its own terms".
- **EU DMA decision DMA.100220** (16 July 2026, Art. 6(7)): Google must open 11 Android features
  to third-party assistants (long-press, hotword, centralised app data, context-aware
  intelligence, ambient data, structured on-device integration = AppFunctions, screen automation,
  system integration, system-level on-device models, own on-device model, background execution).
  Five may need certification (draft terms 1 Feb 2027, final 1 May 2027). Deadline Android 18 /
  1 Aug 2027; concurrent hotword Android 19 / 1 Aug 2028. Google must publish "complete
  documentation". No mention of AOSP or forks. Measures PDF link was 404 on 5 Nov 2026.
- **GrapheneOS**: ships no AI, no telemetry; TPU access toggle (Google apps only); plans open
  STT/TTS; strongly discourages third-party accessibility services and plans safeguards; ported
  Android 17 on launch day; forum thread says no Google AI features expected. Motorola
  partnership announced MWC March 2026 (devices expected 2027).
- **Bank attestation**: our signing key is unknown to banks. Apps that check nothing work; apps
  that whitelist GrapheneOS's key specifically fail on our fork; apps needing Play Integrity
  (~10%) already fail on GrapheneOS. Revolut reported blocking GrapheneOS (Aug 2026).
- **Models/speeds**: Gemma 4 E2B ~16 tok/s on Pixel 10 Pro XL (measured); 8B Q4 on Tensor G5
  estimated single-digit to low-double-digit tok/s (no published measurement). Gemini Nano is
  ~2–3B. Best agents score ~80% on AndroidWorld; MobileUse ~63%.
- **Reference implementations**: AAOSP (Apache 2.0, archived Oct 2026, successor JibarOS on
  Android 16): `LlmManagerService` in system_server, llama.cpp JNI inside system_server (we do
  the opposite: isolated process), manifest-declared tools, consent gate, SQLite audit;
  `docs/AAOSP_ARCHITECTURE.md` has build pitfalls. gos-ai (mx37): app-level GrapheneOS assistant.
  OpenDroid / DroidRun / MobileUse: app- or ADB-level agents.
- **Agent injection defences** (survey 7 Nov 2026, `docs/AGENT_SECURITY_SURVEY.md`): CaMeL
  (DeepMind, Mar 2025) is the only published design with provable results (0 injections on
  AgentDojo, 77% vs 84% utility), through code-tracked provenance; OpenAI Operator's monitor model
  was exploited within weeks; 2026 red-teaming finds indirect injection succeeds across models;
  a Cloud Security Alliance note (Jul 2026) broke Mobile-Agent-v3 with 7 of 7 attacks and other
  open Android agents with 6 of 7; none of the open Android agents separates what the model reads
  from what it may do; no phone injection benchmark exists.
