# Ballistic assistant app (`apps/assistant`)

The Android shell around the Rust core (SPEC.md §4.1, PLAN step 3, docs/ANDROID_APP.md). Kotlin and
Jetpack Compose. It supplies core's four ports and shows what core returns; it holds no agent
logic (core/README.md §15, rule 1). This first pass is a skeleton. The UI and the platform
adapters can now be built alongside core. **Core is not bound yet**: every run uses a scripted
stand-in, and the UI says so.

Package and application id: `app.ballistic.assistant`. It runs as an ordinary app with no
privileged permission.

## Layout

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml   one version catalogue
gradlew, gradle/wrapper/                                           Gradle 9.9.0 (checksum pinned)
core-api/            pure Kotlin/JVM; builds and tests without an Android SDK
  core/Types.kt          data shapes of core/src/types.rs, same JSON (serde) form
  core/Ports.kt          Screen, Hands, Models, Confirm (core/src/ports.rs), PortException
  core/CoreJson.kt       the JSON configuration matching serde (strict) and one for records
  core/CoreEngine.kt     CoreEngine interface, RustCore facade (run/stop), StepEvent, RunResult
  core/FakeCoreEngine.kt scripted replay through the real ports; no planning, no model calls
  core/UniffiCoreEngine.kt  placeholder for libballistic_core
  core/Policy.kt         policy file shape, defaults, ConfirmFloor (tighten-only rule)
  core/PolicyStore.kt    policy.json load (fail to defaults) / atomic save
  core/ActionLog.kt      append-only JSON-lines log of core's step events
  models/ModelsHttp.kt   Models port over an OpenAI-compatible endpoint, per-role config
  session/PromptSession.kt  prompt-box state machine (gate, run, approval, Stop, log)
  session/Labels.kt      result/evidence/action wording ("acknowledged" is never "sent")
app/                 the Android application
  BallisticApp.kt        AppGraph: which adapter fills which port; app-scoped session
  MainActivity.kt        tabs: Ask (prompt box), Activity (log), Settings
  gate/CommandGate.kt    BiometricPrompt before the first request of a session (SPEC §7.8)
  confirm/ConfirmActivity.kt   confirmation window (SPEC §7.1)
  confirm/InjectedInputFilter.kt  verifyInputEvent + device/source/flag checks
  confirm/OperationKey.kt      per-operation biometric-bound Keystore key
  confirm/ConfirmBroker.kt     core thread <-> window hand-off, one request at a time
  adapters/NotificationScreen.kt  Screen from active notifications (listener service)
  adapters/IntentHands.kt        Hands from intents; plus host-only reply/dial/alarm
  adapters/BiometricConfirm.kt   Confirm port: ConfirmFloor, then the window
  adapters/InstalledApps.kt      launchable apps (installed_apps, policy table)
  storage/SecretStore.kt         API key encrypted with a Keystore AES-GCM key
  storage/ModelsConfigStore.kt   models.json
  ui/                            Compose screens
```

App-private files: `files/policy.json`, `files/logs/actions.jsonl`, `files/models.json`, and
SharedPreferences `secrets`, which holds only ciphertext. Backup and device transfer exclude all of them.

## What is real and what is a stub

| Part | State |
|---|---|
| Kotlin data shapes and JSON | Real. Tested against serde output of core/src/types.rs and every recorded screen in core/tests/screens. |
| Ports | Real interfaces. Synchronous, because core calls them from its own thread and blocks on each call. |
| Engine | **Stub.** `FakeCoreEngine.demo()` replays open Clock → wait → one soft-confirmed tap, then stops as core does after a guarded input ("effect unverified"). The UI shows "Fake core (scripted replay, no planning)". `UniffiCoreEngine` throws. |
| Prompt box | Real UI over `PromptSession`: gate check, one run at a time, steps as they arrive (action, why, outcome), Stop, result wording per ANDROID_APP.md. |
| Command gate | Real BiometricPrompt (BIOMETRIC_STRONG). The session locks when no Ballistic activity is visible or the screen turns off. Passkey and voice are not built. |
| Confirmation window | Real: FLAG_SECURE, overlays hidden, input filter, Back denies, a hard request needs a biometric bound to a per-operation key that signs the exact request. **Untested on a device.** |
| Policy settings | Real table. Edits need the gate unlocked. A file that does not parse falls back to the defaults. |
| Action log | Real. Typed text is hidden until the owner asks to see it. |
| NotificationScreen | Real adapter. Needs notification access. Bounds are a synthetic layout (see the file). |
| IntentHands | `open_app` and `wait` are real. Every coordinate op, back and home returns `not_executed: needs Computer Control`. Reply, dial and alarm exist but core cannot call them yet. |
| ModelsHttp | Real client (tested against a local HTTP server). The planner endpoint is set in Settings. |
| Voice, overlay, foreground service, in-process models | Not started. |

## Build

```
./gradlew :core-api:test          # pure Kotlin, any JDK 17+; no Android SDK needed
./gradlew :app:assembleDebug      # needs an Android SDK with platform 37 (ANDROID_HOME or local.properties)
```

Or open `apps/assistant` in Android Studio. The tests read the recorded screens in place from
`core/tests/screens`, so run them inside this repository.

### What was verified where this was written (7 Nov 2026)

There was no Android SDK on the machine; per the brief none was downloaded. JDK 21 (an AOSP
prebuilt) and Gradle 9.9.0 were available.

- `./gradlew :core-api:test`: **passes**, 36 tests (JSON compatibility, fixtures, policy rule,
  policy store, fake engine stopping rules, prompt session with the fake engine, labels, HTTP
  client).
- `:app` configures under AGP 9.5.1, and every dependency in its runtime classpath resolves.
- `./gradlew :app:assembleDebug`: **not verified**. It stops at "SDK location not found". No
  file under `app/` has been compiled, the manifest has not been merged, resources have not been
  processed, and nothing has run on an emulator or a phone.

## Decisions taken here that the design docs leave open

- **Strict JSON.** The Kotlin side rejects unknown keys for every core type. Rust does this only for
  `HandsOp` and `HostCapabilities`. Core and shell ship in one APK, so an unknown key means a
  version mismatch. Run records (`StepEvent`, `RunResult`, log lines) are parsed leniently.
- **UniFFI boundary (proposed).** The callback interface passes the same JSON strings as the
  JSON-lines bridge, so serde's shapes stay the single contract and the classes in `Types.kt`
  stay the Kotlin side. The alternative is UniFFI records generated per type. This needs
  agreeing in core.
- **`RustCore` exposes `run()` and `stop()`, not `step()`.** Core owns sequencing. A shell that
  stepped the loop would be deciding when to act.
- **Unsupported ops return `not_executed`**, not `error`. Nothing was dispatched, and `error` makes core
  treat the effect as unknown and stop.
- **Policy file shape**: `{"version":1,"apps":{"<pkg>":{"observe","act","route","confirm"}}}`.
  The values are snake_case forms of core/README.md §9 (`tree_screenshot`, `device_only`,
  `default_list`, `never_automate`). Signal defaults to device-only. There is no banking list yet.
- **ConfirmFloor** is a second, tighten-only check in the Confirm adapter. It can raise soft to
  hard, using the SPEC §7.1 list plus core's CONFIRM_WORDS, a non-owner value source, or
  `confirm: always`. It can refuse, for `never_automate` or `act: none`. It never lowers. The
  authority belongs in core (`core/policy`, not written yet).
- **Hard approval signs the canonical request** with a per-operation key. Core does not verify
  the signature yet, because the port returns only approved/denied.
- **Normal-level permissions added beyond the brief's list.** `HIDE_OVERLAY_WINDOWS` serves SPEC §7.1 "secure against overlay".
  `SET_ALARM` is required by `ACTION_SET_ALARM`. Neither is privileged.
- **No AndroidX ViewModel.** `PromptSession` is the prompt box's view model. It is
  application-scoped, so a run survives leaving the activity and rotation.
- **API key at rest** uses a plain Keystore AES-GCM key. androidx.security-crypto is deprecated.
- **Versions**: the latest stable releases on 7 Nov 2026. compileSdk/targetSdk 37 (Android 17,
  what the gates target and the test phone runs). minSdk 34.

## Next passes

1. **Core API extraction (in core, first).** `agent::run` must take the port traits instead
   of the stdio `Bridge`, emit run events (step and activity), and check cancellation before
   every model call and dispatch (ANDROID_APP.md, integration step 1).
2. **UniFFI binding.** Export `run`/`cancel` and a host callback interface from core, build
   `libballistic_core.so` for x86_64 (emulator) and arm64-v8a (phone) with cargo-ndk, generate the
   Kotlin bindings, and implement `UniffiCoreEngine`. Keep `FakeCoreEngine` for UI tests.
3. **Device checks of the confirmation window.** Measure the device id and flags of adb
   `input tap` and of accessibility clicks on Android 17. Run SPEC §9's 1,000 injected taps.
   Decide whether window-side filtering suffices (CONTEXT open question 7).
4. **Core contracts for notification reply, dial and alarm**, so core can ask for them (with
   exact-operation approval) rather than only shell code.
5. **Run lifecycle.** A foreground service while a run is active, and an "interrupted" record
   after process death (never resume mutation).
6. **Computer Control adapters once the gate lands (PLAN step 5).** These are
   `ComputerControlScreen` (accessibility proxy tree) and `ComputerControlHands` (taps, swipes,
   `commitText`, back/home). The app then builds as a priv-app with the assistant role.
7. **Voice and overlay.** Wake word, push-to-talk, STT/TTS (SPEC §5.3), then in-process models
   (LiteRT-LM, llama.cpp) behind `Models`.
