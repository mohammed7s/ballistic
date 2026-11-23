# Computer Control emulator: first boot and setup

Measured on 23 November 2026 on the ThinkPad; these are emulator results, not Pixel results.

- Custom image extraction completed with exit 0. `system.img` is 8,601,468,928 bytes.
- Visible AVD `BallisticControlApi37` started on desktop `:1`, serial `emulator-5554`, using
  emulator 37.2.13 and KVM. The original `AndroidWorldAvd` was retained and not run concurrently.
- `sys.boot_completed=1`, SDK 37, hardware `ranchu`; fingerprint:
  `Android/sdk_phone64_x86_64/emu64x:17/CP2A.260605.016/2026112200:userdebug/test-keys`.
- Package-manager feature and shared-library lists both contain
  `com.android.extensions.computercontrol`. Clock and the virtual-device consent application exist.
- App and matching test APK installed successfully from `~/ballistic_cc_20261122/apps/assistant/`.
  Initial launch worked. The read-only host preflight initially failed only the missing assistant role.
- Through ordinary Android UI, selected Ballistic as digital assistant and accepted the role warning.
  `cmd role get-role-holders --user 0 android.app.role.ASSISTANT` then returned
  `app.ballistic.assistant`; package permission state showed
  `ACCESS_COMPUTER_CONTROL: granted=true, flags=[ GRANTED_BY_ROLE]`.
- Screen automation Settings opened. Fresh assistant and target switches were off. Test setup
  tapped Allow screen automation and Clock; persisted checked state must still be re-read.
- A disposable test PIN was entered and confirmed through Settings. Setup reached the lock-screen
  notification-content page. Fingerprint enrollment and app onboarding remain unfinished.

This setup uses host ADB taps as a simulated test operator in an isolated emulator. No shell role
or permission grant, privileged app installation, production approval bypass or phone change was
used. It does not establish physical biometric security. Host setup input must not become the
agent's action route during benchmarks.

SSH became unavailable again around 07:33 UTC. Two Tailscale SSH attempts timed out; the recorded
LAN address returned no route to host. The last verified UI was the lock-screen notification page;
the attempted next taps were not delivered. Do not assume the emulator stopped or repeat setup
blindly when connectivity returns.

Connectivity recovered at 07:36 UTC, permitting further setup:

- Completed PIN setup and fingerprint enrollment through Android UI with emulator `finger touch 1`
  events. This fingerprint is simulated test input, not a physical-device security result.
- Finished app onboarding, selected Connected server, and allowed task notifications through the
  Android permission prompt.
- The existing Gemini key successfully listed available Pro models. Configured the explicit
  `gemini-3.1-pro-preview` model ID, 90-second request timeout and Gemini OpenAI-compatible endpoint.
  Preview IDs are not immutable weight revisions; record this exact ID in run results.
- Used the existing debug private-file import on the ThinkPad/emulator only for the key and model
  config. No secret was printed or copied to the Latitude. Restarted the idle app to import them.
- Settings showed `Engine: Ballistic core` and the connected-route disclosure. No inference call
  has occurred; model listing alone does not establish usable generation quota.
- Opened Clock's app-rule editor. Last verified UI was the Read dropdown with the tree option;
  no policy was saved or fingerprint-approved. Subsequent SSH timed out again at 08:17 UTC.

Remaining: finish Clock app policy and normal target consent; run the harmless Clock/Stopwatch pilot through the actual
APK driver, then Stop/revocation checks. No Computer Control session, model call or benchmark result
is established here. AndroidWorld API-37 compatibility and the frozen 30-task suite are pending.

Observed UX issues: onboarding still says this phone setup cannot tap/type/scroll, even though the
custom OS advertises Computer Control. The phone-lock step also retained its old no-lock display
when returning from setting a PIN; advancing the step worked. Neither issue was changed here.

ThinkPad launcher/logs: `~/ballistic_cc_20261122/start-emulator.sh` and
`~/ballistic_runs/cc_emulator_20261122/emulator.log`. The launcher keeps a visible window and checks
for another emulator before starting. Resume by inspecting the existing process and UI first.
