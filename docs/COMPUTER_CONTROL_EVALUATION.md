# Computer Control emulator evaluation

Status: 23 November 2026. Pilot test driver and compatible image built; image delivered with a
matching checksum. AVD configured, extraction completion unverified after SSH interruption;
no boot or live test yet.

The Pixel has the compatible APK installed, but owner setup and live session validation remain
pending. Its adapter reads accessibility/UI trees; screenshot-based model observation is not
implemented. The existing downloaded OS artifacts are Pixel `blazer` images, not emulator
images. A separate compatible emulator image completed on Contabo using the existing source.

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
build-path failures. Transfer and SHA-256 checking passed. Effective runtime flags, boot
and consent remain unverified. Live log paths,
source checks and recipe: [OS build record](../../ballistic-os/docs/log/2026-11-22-emulator-build.md).

Sources: [pinned manifest](https://raw.githubusercontent.com/GrapheneOS/platform_manifest/2026110200/default.xml),
[product declarations](https://raw.githubusercontent.com/GrapheneOS/device_generic_goldfish/160f9d95c50827b12e5ab1c49faa5d6502511e0b/AndroidProducts.mk),
[x86_64 phone product](https://raw.githubusercontent.com/GrapheneOS/device_generic_goldfish/160f9d95c50827b12e5ab1c49faa5d6502511e0b/64bitonly/product/sdk_phone64_x86_64.mk).
