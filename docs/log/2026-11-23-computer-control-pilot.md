# First APK Computer Control pilot, 23 November 2026

Follow-up: the owner authorized Flash Lite. Its pilot exposed an OS initialization race; see
[the separate results and correction status](2026-11-23-flash-lite-computer-control.md).
The original Pro evidence below remains unchanged.

Environment: ThinkPad visible `BallisticControlApi37`, `emulator-5554`, custom API-37 build
`2026112200`. See [boot evidence](2026-11-23-computer-control-emulator.md). No phone changes.

## Setup and session

Completed Clock's app rule through UI and simulated fingerprint approval: observe `tree`, act
`full`, route `default`, confirm `default_list`, screen control `on`. No other app rule was added.
The app reported extension version 5 available. Start screen control returned the normal system
target-consent dialog; the test operator selected Always allow for Clock in this disposable
emulator. Ballistic then reported an active session on the hidden display. End screen control
was selected before starting instrumentation. All six host preflight checks passed.

## Model-driven run: infrastructure error before actions

Request: “Open Clock and show the Stopwatch tab. Do not start it.”

- Actual APK in-process Rust core, HTTP model `gemini-3.1-pro-preview` for every role.
- Instrumentation confirmed an active Computer Control session at submit, targeting Clock only.
- Agent elapsed 1.34 seconds; total instrument elapsed 5.182 seconds.
- First chat-model call returned HTTP 429; zero agent actions/steps and no completion claim.
- Driver `status: finished` means the run terminated; `exchange_kind: Error` and the HTTP error
  establish that it did not succeed. Independent state was not checked.
- A separate minimal diagnostic request returned `RESOURCE_EXHAUSTED`, explicitly listing zero
  free-tier input-token and request limits for `gemini-3.1-pro`, per minute and per day. This is
  model-service quota/access failure, not evidence of model planning or harness failure.
- No repeated suite calls or automatic model fallback. Owner was asked whether to use the existing
  Flash Lite model for a separate functional evaluation; no answer had arrived at this record.

Artifact, on both ThinkPad and Latitude under `ballistic_runs/cc_pilot_20261123/`:
`cc_3fa07f5ebd2d4669b695144c945081c8.json`.

## Model-free adapter fixture: passed

Added a fixed `adapter-smoke` mode to the separate test APK and host driver. The target APK's
production code was unchanged. The fixture uses production ComputerControlPlatform, Screen and
Hands classes, with the saved Clock policy and its own bounded test lifecycle. It does not run
the core, the model or the full app dispatch/approval pipeline. No host ADB tap is used to launch
Clock or select Stopwatch in this test; those operations use the actual session APIs.

All six checks passed in **9.57 seconds**, **zero model calls**:

1. Created an active Clock-only session.
2. Launched Clock and obtained its tree: 77 nodes, package `com.android.deskclock`.
3. Tapped the unique visible/clickable Stopwatch tab through session input; readback marked it
   selected. The final tree contains Start. No stopwatch start/reset operation was requested.
4. Rejected a non-target launch (`com.android.settings`) before dispatch. This tests the app
   adapter's target restriction, not a direct test of the OS protected-target guard.
5. After closing the session, Hands returned NotExecuted for another launch.
6. A retained old session input reference also rejected launch after closure.

Readback is through the same adapter, not an independent benchmark checker. Closing tests do not
establish the full user Stop button, in-flight model cancellation or live OS permission revocation.
No production authentication bypass was introduced. Simulated enrollment/approval during setup
is not physical biometric validation. This fixture's passes are not a 6-task AI benchmark score.

Artifact: `cc_2e3d2165c4e743fea444ad78d86d2404.json` in the same directory. Before/after UI trees
are retained there. The test APK compiled and installed successfully; seven host tests passed.

## Remaining

Obtain usable Pro quota or owner-approved alternative model, then run model-driven pilot tasks.
Finish independent state-check integration and AndroidWorld API-37 compatibility before the
frozen 30 tasks. Classify protected Settings tasks as policy-blocked; do not change the gates or
silently substitute tasks. Full app Stop, OS revocation and sensitive operation approvals remain
separate runtime checks. Computer Control capability now has live evidence; broad task quality
and speed remain unmeasured.
