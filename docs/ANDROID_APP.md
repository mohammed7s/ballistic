# Android app: first APK

Implementation plan, 7 November 2026. This refines PLAN step 3; it does not claim an app exists.

## Current state

The Rust agent and Python emulator host run today. There is no Android Gradle project, Kotlin UI,
UniFFI binding or APK. The agent loop currently accepts the concrete JSON-lines Bridge, so merely
generating bindings would not make it usable inside an Android process. Reader/provenance, the
deny-only judge and recorded workflow execution are still incomplete (see core/README.md).

The owner wants the actual app UI, not a browser development UI. Keep the terminal for harness
work. Build a text-first native Kotlin/Compose app under `apps/android/`; voice and an overlay
remain later step-3 deliverables. Keep the existing Gemini backend for the first emulator test.
On-device inference comes after this integration, with model selection based on measurements.

## Screens and behaviour

- **Conversation:** request input and Run; chronological requests/results; current activity and
  Stop while running. Allow one active run. Clearly indicate the configured model and whenever
  requests leave the device. Disable a second submission until the current run ends.
- **Approval:** app, exact proposed operation and arguments, their sources when available, and
  explicit approve/deny. Production approval uses SPEC §7.1, including biometric binding and
  injected-input rejection; a normal dialog is not an implementation of that security boundary.
  Editing an operation invalidates approval. Returning from background never approves it.
- **Result:** distinguish completed, opened interface, unresolved, failed and unsupported. Show
  whether completion rests on a receipt/readback, UI assertion or model interpretation. An input
  acknowledgement must never display “sent.” Stopped runs retain any known or uncertain effects.
- **Activity:** observation/action/verification summaries, failures and timing, with sensitive
  content redacted by default. Diagnostic export is explicit, not automatic.
- **Settings:** backend/model, app capabilities and grants, model/cloud status. Do not display
  benchmark-only capabilities as permissions available on a normal phone.

UI state flows from idle → command authentication → running ↔ awaiting approval → result.
Stop prevents new model calls and actions, cancels pending calls where supported, invalidates
outstanding approvals and drains in-flight work. It cannot undo an operation already submitted.
Record an ambiguous operation as unresolved; inspect before retrying. Rotation reattaches to
the same run. Process death records an interrupted run and never resumes mutation automatically.
Command authentication and operation approval remain separate checks.

## Integration sequence and acceptance checks

1. **Extract the in-process core API.** Retain the four ports and JSON-lines harness adapter,
   but remove the loop's dependency on the concrete stdio Bridge. Add run events, lifecycle and
   cancellation, with cancellation checked immediately before dispatch. Keep policy and routing
   in Rust. Acceptance: current Rust/Python checks pass; scripted cancellation and denied approval
   dispatch no subsequent operation; both adapters exercise the same engine.
2. **Build and install a native shell.** Create the Gradle app, Kotlin screens and UniFFI surface;
   package the Rust library for emulator x86_64 and phone arm64-v8a. Run the engine off the UI
   thread. Acceptance: a reproducible debug APK installs, opens, accepts input and renders actual
   core events. A mocked port test is labelled as such and is not an agent demo.
3. **Drive the visible emulator from that APK.** The existing API-33 AndroidWorld emulator does
   not supply our planned Android-17 Computer Control gates. Proposed development adapter:
   core runs in the APK, while an explicitly paired host provides Screen/Hands and Gemini Models
   through an ADB-local connection. The Gemini key stays on the host. The connection has exclusive
   run ownership; terminal, suite and app cannot control the emulator simultaneously. Confirmation
   is mediated separately from agent-controlled target apps. Emulator approval is a functional
   test, not evidence that the production biometric/input boundary holds. This adapter is debug
   only and absent from release builds. Check that navigating away from the app preserves the
   run and that the owner can return to approve or stop it. Acceptance: a real request entered in
   the native app completes on a fixture; deny, Stop, disconnect and ambiguous submission cases
   stop safely; AndroidWorld scores the result independently.
4. **Replace development ports with device ports.** Ordinary Android operations advertise only
   what the installed app can execute. Computer Control requires the separate OS track's gates;
   benchmark ADB privileges cannot substitute for them. Implement the production command and
   approval gates, then validate on the physical phone. Add the selected local inference runtime
   behind Models. Acceptance: supported tasks run without a laptop, permissions are enforced at
   dispatch, and device-side security and side-effect checks pass.

The first installable APK is milestone 2; the first native-UI agent demonstration is milestone 3.
A standalone, security-complete assistant requires milestone 4 and the outstanding core safety
work. No claim of production readiness follows from an APK building or a benchmark passing.

## Work alongside the current suite

Finish collecting the current Gemini suite before changing the tested binary. Classify completion
recognition, target resolution and environment failures separately. Fix these with targeted task
re-runs before another full comparison. Native shell and in-process API work can start without
waiting for a perfect benchmark score, but expanded actions remain gated on the unfinished
reader/provenance policy and side-effect tests. No user preference is blocking the text-first
milestone; exact visual styling can be refined after the first installable screen exists.
