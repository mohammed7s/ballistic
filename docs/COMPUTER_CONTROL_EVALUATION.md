# Computer Control emulator evaluation

Status: 22 November 2026. Preparation requirements, not a completed image or test run.

The Pixel has the compatible APK installed, but owner setup and live session validation remain
pending. Its adapter reads accessibility/UI trees; screenshot-based model observation is not
implemented. The ThinkPad has only the API-33 SDK system image. No compatible emulator image
was found or built during this audit.

## Platform deliverable

The OS track must supply a separate virtual-device build from the pinned Android 17 source
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
