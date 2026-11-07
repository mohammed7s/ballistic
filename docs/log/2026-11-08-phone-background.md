# Phone command access and background work — 8 November 2026

The owner requested elapsed time, clarified that leaving the app should keep a task running,
and distinguished manual app locking from command admission and sensitive-action approval.
Harness reliability work remains paused; no OS/build-server changes were made.

## Implemented

- Command admission checks the phone's secure unlock and interactive state afresh. Switching
  apps does not lock Ballistic. Manual request locking persists independently. Ordinary phone
  unlock does not require a second app fingerprint prompt. Sensitive-operation approval is
  unchanged. Voice wake-up is not implemented; speech cannot authorize a locked-phone request.
- Ask shows elapsed time from a monotonic clock, excluding authentication wait and freezing
  at completion. Cancellation errors are presented as an owner stop, not an inference failure.
- Owner-started Ask and Local AI work have a foreground service, private progress notification,
  elapsed chronometer, Stop action and CPU wake lock. Home/Continue in background hides the
  Activity without cancelling work. The service is not sticky and does not restart a task.
  An interrupted-process marker is shown on next launch. Force-stop still interrupts work.
- Settings links to Android's task notification settings. The Ask introduction identifies
  unsupported alarm creation and general tap/type control.

Android's configured phone unlock methods are trusted: an unlocked-state check cannot prove
fingerprint rather than PIN use. See [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager).
The service uses the declared [specialUse type](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
for user-started work and follows [foreground-service launch requirements](https://developer.android.com/develop/background-work/services/fgs/launch).
It provides neither privileged screen control nor an exemption for background activity launches;
Google Play distribution approval is not established.

## Physical Pixel findings

Pixel 10 Pro, Android 17, ARM64; commands explicitly targeted the USB phone, not the emulator.
The earlier Wi-Fi download completed: managed Qwen3 0.6B Q8 GGUF is 639,446,688 bytes with its
metadata installed. Updating the APK preserved it; no model re-download was needed.

- **Local speed check passed:** first token 0.4 seconds, generation 21.8 tokens/second.
  This short inference ran with the launcher foreground and WorkService foregrounded; the
  service disappeared after completion. These are short-prompt runtime numbers, not agent-step
  latency, sustained thermal performance or task-quality scores.
- **Clock launch observed:** a local Ask trial opened the Clock UI, independently inspected.
  The agent repeated the launch because the notification-based screen adapter cannot verify
  the foreground app. This is not an end-to-end completion pass. The elapsed display was live
  and froze at 1:31 when the first trial stopped.
- **Alarm test remains unsupported:** the core-facing Android adapter exposes open_app and
  wait. A host alarm-intent helper is not a core operation. The owner's earlier alarm trace
  also repeatedly launched Settings, a model-planning error. No hardcoded alarm workaround
  was added.
- Notification permission remained disabled after the owner's reported settings change.
  The already-authorized POST_NOTIFICATIONS permission was enabled via ADB and verified.
- Physical testing caught a Stop cleanup bug: its new service start ID invalidated the
  collector's earlier stopSelfResult ID. The corrected service reattaches its completion
  observer for each start, including Stop. Final device verification is recorded below.

On the final installed APK, tapping the expanded notification's Stop ended an Ask run with
"Stopped. Any earlier actions remain." Elapsed time froze at **2:19**, unchanged on a later
inspection. WorkService was absent from the active-services dump; the power log recorded
release of `Ballistic:active-work`. The model remained Ready and requests enabled on return.
The temporary USB stay-awake override was restored to its original **0**, verified by readback.

## Build and evidence

PromptSession tests: 6 passed, zero failures/errors/skips. Added coverage checks fresh command
admission despite stale UI state, monotonic elapsed time excluding authentication and freezing
on failure, and cancellation errors recorded as owner stops. Debug APK assembly passed offline.

Installed artifact: `ballistic_runs/phone_background_20261108/Ballistic-background-debug.apk`,
54,097,006 bytes, SHA-256
`6f6d432b78c208d0d9e74ecfb4ec977c712e887294f3a876bd5ef303f2e4977c`.

Local evidence under `ballistic_runs/phone_background_20261108/`: speed-background.txt,
speed-result.xml, clock-foreground.xml, ask-elapsed.xml, ask-result.xml and
notification-stop-final.txt, notification-stop.png and final-state.txt. These device artifacts
are not committed.

The ThinkPad clone has pre-existing tracked/untracked changes and cannot fast-forward safely;
those files were preserved. The APK was transferred separately for installation. This work
does not establish full phone automation, physical sensitive-operation biometric approval,
screen-off continuation reliability, resumable downloads, or production readiness.

## Follow-up: compact progress and expandable history

The owner requested log-like live updates, with past details hidden until expanded. Ask now
shows the latest six single-line timestamped events during execution and time since the latest
update during a long call. Events come from actual observation/model/dispatch callbacks and
completed core steps; no simulated percentage or model reasoning is exposed. A completed Ask
card collapses its details automatically. Activity groups action records by run and starts with
each card collapsed. Its existing typed-content visibility switch remains available.

Progress is bounded to the latest 250 entries per run in memory. The existing persisted action
log remains unchanged; transient lifecycle events do not survive process restart. Six session
tests pass, including added checks for progress ordering and isolation between runs. APK assembly
passed. Updated APK installed successfully on the Pixel, preserving its managed model.

Artifact: `ballistic_runs/phone_progress_20261108/Ballistic-progress-debug.apk`, 54,114,422 bytes;
SHA-256 `c7d620d18c9da7a6abcf57d43d22164f59a5211cffbd37f2bdb13ed7a1ad9820`.

Physical inspection confirmed Activity displayed separate collapsed run cards with step counts
and Show details controls, including an in-progress run. Evidence:
`ballistic_runs/phone_progress_20261108/activity-collapsed.txt`. Subsequent SSH connections to the
ThinkPad timed out twice, so expansion and the live Ask feed were not physically verified in
this follow-up. No phone sleep-setting override was applied for this update.
