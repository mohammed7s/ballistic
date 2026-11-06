# Ballistic: context and conclusions (hand-off document)

Last updated: 6 November 2026. Maintainer: @mohammed7s.
Purpose: let a person or an agent resume the design work without re-reading the sessions.
The design began as a two-day conversation on 4–5 Nov 2026; its conclusions are below.

## 1. What Ballistic is, in one paragraph

A GrapheneOS-based build for Pixel phones with an AI assistant that has OEM-level powers (see any
screen, act in any app, wake word) but is controlled by the owner: per-app grants, local models or
the user's own GPU, and a confirmation step the agent cannot press. OS-side changes are kept small
and generic so they could be merged into GrapheneOS; the assistant itself is an ordinary app. The
design rule everything follows from: **the agent can touch anything on the phone except the
confirmation dialog.**

## 2. Decisions taken (and why)

| Decision | Why |
|---|---|
| **Fork GrapheneOS, not AOSP, LineageOS or /e/OS; not a new OS brand** | Keeps hardening, verified boot, sandboxed Play, Pixel-only single target. A new OS or a LineageOS base means no hardening, worthless attestation, and a maintenance burden we can't carry. |
| **Pixel hardware only** | GrapheneOS supports Pixel 6–10a. Tensor TPU, Titan M2, relockable bootloader with our own AVB key. |
| **Full OS control is required; an app is not enough** | Every capability we need (screen capture incl. FLAG_SECURE, input injection, hotword, persistence, policy enforced below the agent) is privileged. Every shipped agent (Gemini, Doubao) is privileged OEM software. |
| **Build on Android 17's Computer Control + AppFunctions, don't rebuild them** | Verified present in AOSP `android17-release` (see §4). GrapheneOS already ships Android 17. |
| **Confirmation = biometric-bound per-operation Keystore key in a window that drops injected events** (replaces Protected Confirmation, 6 Nov 2026) | Protected Confirmation's HAL was removed from Pixel 6/7/8 trees in Android 15 QPR1 and never shipped on Pixel 9 (§4b). Fingerprint matching happens in the TEE and injected touches cannot produce the auth token, so the property "agent can raise the dialog, never press it" is kept. |
| **v1 = everything in the app; v2 = policy and models move below the app** (6 Nov 2026) | v1 needs only the gate patches, is what would run on stock Android if the DMA opens the same gates, and is demoable without a build box. OS-side policy/model host are built only when v1 shows a bypass the OS must close or upstream asks. |
| **Gates are hardening deltas, never replacements** (6 Nov 2026) | If Android 18 ships DMA assistant plumbing in AOSP, a replacement gate conflicts on every rebase and nobody merges it; a delta (no remote config, default empty, user toggle) survives and is the shape GrapheneOS already accepts. |
| **No stealth; parity-with-Gemini argument instead** (6 Nov 2026) | Computer Control's input devices are identifiable by any app in one call (§4b), so timing mimicry buys nothing and renaming devices is spoofing. Ballistic is as visible as Gemini on a Pixel; an app that blocks it blocks Gemini. "Human-like timing" removed from the spec. |
| **Command gate: biometric/passkey before any command** (6 Nov 2026) | Closes "stranger or played audio commands the phone". Distinct from content injection, which the planner/reader split and data-injection rules cover. |
| **Positioning: not faster than a hand** (6 Nov 2026) | Measured prefill on Tensor gives 4–25 s per agent step. Wins are hands-free, screen-off, long/repetitive tasks, skill replay, queues. |
| **Model choice open; must have a LiteRT build or acceptable CPU prefill** (6 Nov 2026) | LiteRT-LM GPU is the only fast prefill path on Tensor; llama.cpp Vulkan is broken on both Tensor GPUs; LFM2.5-8B-A1B has no LiteRT build and failed a real-phone agent test. |
| **Home GPU parked** (6 Nov 2026) | Local-first is evaluated on its own first. |
| **Model routing: device first; cloud opt-in off; home GPU parked** | Wake word and STT never leave the device. Indicator shows whenever a payload leaves the phone. |
| **Planner/reader split** | Untrusted content only reaches a reader model with no tool access; the planner sees labels and structured observations. Enforced by the OS service assembling prompts, not by the app. |
| ~~Resident on-device model: LFM2.5-8B-A1B class~~ **superseded 6 Nov 2026**, see model-choice row | Memory fits; speed and runtime support do not yet. |
| **Two tracks: fork now (track A), upstream the gates (track C)** | Fork = dev vehicle + fallback. Upstream candidates = user-controlled gates for Computer Control, AppFunctions, the OnDeviceIntelligence socket, hotword via assistant role. The agent itself will never be upstreamed. |
| **Name: Ballistic, one brand, no sub-brands** | Electrons move through graphene ballistically: fast, straight, no scattering. Chosen 6 Nov 2026 (earlier working names Hex, Monolayer rejected). Never "Graphene" in the name (trademark, and would imply endorsement). |
| **Test suite: AndroidWorld (116 tasks / 20 apps) + our injection tests** | Standard benchmark; runs on the emulator over ADB. |
| **Dev phone: used Pixel 8 Pro (12 GB); later Pixel 10 Pro (16 GB)** | Avoid Pixel 6/7 (end of support 2026/27). 8a is the cheapest acceptable. |
| **Repo: one GitHub org, one monorepo for our code + forks of the 4–5 AOSP repos we patch, glued by a repo manifest overlay** | AOSP's build forces the fork structure; the monorepo is what people read. One commit per feature in the forks so rebasing is mechanical and the upstream proposal is `git log upstream..ballistic-17`. |

| **Fingerprint-gated actions confirmed by the owner (6 Nov 2026)** | Owner's decision: sends, payments, deletes and installs require a biometric (fingerprint) approval on a window the agent cannot press, regardless of whether Protected Confirmation turns out to be available. |
| **Build pipeline: first build is the pinned GrapheneOS tag unmodified; patches are added one at a time afterwards (6 Nov 2026)** | Proves the build/sign/flash/OTA chain before any of our code is in it; each later patch is then a single, bisectable change. Build machine: rented by the hour (Hetzner-class, 16 vCPU / 64 GB), source tree kept on a persistent volume so re-syncs are cheap. |

## 3. Things explicitly out of scope

Spoofing attestation / Play Integrity; automating logins, payments, biometric or identity checks;
hiding the agent from apps' fraud detection; modifying `keystore2`, Titan paths, biometrics or
DRM; a Linux-based phone; upstreaming the agent itself.

## 4. Verified facts (checked against AOSP `android17-release` source on 5 Nov 2026)

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
  **13 / 20 PASS.** Agent time 1,496 s total (median ~64 s/task); 281 model calls (T3A makes
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
- **Hardware (6 Nov 2026):** the owner has a **Pixel 10 (non-Pro, 12 GB)**, their daily phone, not
  carrier-locked. GrapheneOS supports it (reported). It serves as the dev device for steps 3–5;
  flashing waits until our signed build exists so the daily phone is wiped once, not twice. OEM
  unlocking was greyed out on first look (no SIM, Wi-Fi connected); see open questions.

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
  re-checked. Consent screen is the AOSP `packages/VirtualDeviceManager` app (platform-signed), only
  built when `RELEASE_PACKAGE_COMPUTER_CONTROL` is true (trunk_staging); a GrapheneOS forum user
  reports it appeared in build 2026092500. App op `COMPUTER_CONTROL` set to MODE_ALLOWED skips it.
- Other preconditions: `POST_NOTIFICATIONS`, visible window, device unlocked, params version ≥ 17,
  `MAXIMUM_CONCURRENT_SESSIONS = 1` (hardcoded, TODO b/419548594), no managed profiles.
- Client library `com.android.extensions.computercontrol` (`libs/computercontrol`) is not in any
  AOSP product makefile; the framework API is `@hide`.
- Display/input: ordinary VirtualDevice with a `VirtualDisplay` (TRUSTED | ALWAYS_UNLOCKED), virtual
  touchscreen + dpad via `/dev/uinput`. No HAL dependency. Whether GrapheneOS sepolicy allows
  system_server to use `/dev/uinput` is unchecked.
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

## 5. Reported facts (from docs/press; re-check before relying on them)

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

## 6. Open questions

1. Audience: personal device only, public builds, or app-only on stock GrapheneOS once upstream
   lands? (Affects licence, polish, and Doubao-style pushback risk.)
2. Which banking / fintech apps must keep working (check each against the attestation table).
   What the "FT app" is (Financial Times vs a fintech app) was never answered.
3. Home GPU: existing hardware and VRAM.
4. ~~Does Computer Control's injected input carry a flag apps can detect?~~ Answered 6 Nov: no
   accessibility flag, but the virtual input devices are trivially identifiable (§4b).
5. ~~Six-target-app cap: code or allowlist?~~ Answered 6 Nov: hardcoded constant (§4b).
6. Will the GrapheneOS team accept a user-controlled Computer Control gate at all, or do they
   consider CC itself too much attack surface? Ask early; it decides whether upstream is a 2026
   or 2027 conversation.
7. Does the confirmation window (SPEC §7.1) hold with window-side filtering via
   `InputManager.verifyInputEvent()` alone, or is an InputDispatcher rule needed? Test with 1,000
   injected taps (SPEC §9).
8. ~~Human-like timing~~ Decided 6 Nov: removed (see decisions).
9. ~~Policy service location~~ Decided 6 Nov: app in v1, OS in v2.
10. v1 model: measure Gemma 4 E2B/E4B, Qwen3 1.7B/4B and a 4B UI agent (UI-Voyager/Holo class) on
    the Pixel 8 Pro with LiteRT-LM GPU and llama.cpp CPU; pick by step time on a 2k-token tree.
11. Does GrapheneOS sepolicy let system_server create `/dev/uinput` devices for Computer Control?
12. Which allowlist path is live for Computer Control in `cp2a`: DeviceConfig/files or the newer
    `AllowlistManager` IDs 3–5?

## 7. Questions to put to the GrapheneOS team

- Timeline for open-source STT/TTS; would an `OnDeviceIntelligenceService` host be the right home?
- Planned accessibility-service safeguards, and whether a privileged assistant path (CC) is
  preferable to them.
- Would they accept replacing Computer Control's DeviceConfig allowlists with a user-controlled
  per-app toggle, default off?
- How they intend to handle the Android 18 DMA assistant APIs if they land in AOSP.
- Fork advice: hardening interactions with native inference libraries (MTE, exec restrictions),
  release cadence, signing.

## 8. Deliverables produced so far (all in `docs/archive/`)

- `graphene_agent_os.pdf/.html`: 12-slide deck (why, landscape, options, architecture, routing,
  safety, limits, plan, decisions).
- `graphene_agent_os_spec.md/.html/.pdf`: long-form spec v0.1 (pre-sanity-check; still assumes we
  write screen capture/injection ourselves).
- `graphene_agent_os_changes.pdf/.html`: short intro + exact file-level change list (also
  pre-sanity-check).
- `2026-11-04_original_chat_transcript.txt`: the first conversation.

The current `SPEC.md` (v0.3, 6 Nov 2026) supersedes the archived spec where they differ.
`docs/SPEC.html` and `docs/SPEC.pdf` were rendered from v0.2 and are stale.
