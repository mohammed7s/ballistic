# Platform support and integration contract

Ballistic is an Android app with a shared Rust core. The OS supplies capabilities; the app
supplies planning, models, policy, approvals and workflow storage. This page describes that
boundary. It does not promise that every Android phone supports every action.

## Current support

| Environment | What is available | What remains unproven or unavailable |
|---|---|---|
| AndroidWorld emulator + laptop harness | UI-tree observations, ADB actions, configured model endpoint, independent benchmark checks | A development environment, not a standalone phone deployment; scripted approvals are not biometrics |
| Ordinary Android app, including stock GrapheneOS | APK minimum API 33; native UI and Rust engine; emulator smoke demonstrated Clock launch and Stop | Other-app screen observation and GUI input are absent in the tested APK; latest core needs rebuilding; no broad device compatibility claim |
| GrapheneOS with ballistic-os | Pixel build `2026112100` verified booted; ordinary selected assistants can request role-based permission and owner-controlled Computer Control access | Ballistic version-5 Screen/Hands client implemented; runtime consent, tree/input, Stop and revocation still require owner-authorized device validation |
| Future stock Android assistant APIs | Intended integration through new host adapters | API availability, device coverage, permissions and distribution requirements must be verified when usable; no promised date or drop-in compatibility |
| iOS | Rust core is intended to be reusable | No iOS app or general screen-control route; any future shell is limited to capabilities that iOS permits |

The supported OS grants the requested `ACCESS_COMPUTER_CONTROL` permission to a qualifying
ordinary selected assistant. Selection alone does not enable the assistant/target switches or
answer system target consent. Ordinary intents and notification access retain their own grants.
See the [OS integration contract](https://github.com/mohammed7s/ballistic-os/blob/master/docs/assistant-integration.md).

## App and platform boundary

```text
Ballistic app
  Native UI, command gate, approval window, model runtimes
  Rust core: planning, reading, policy, evidence, workflows
                         |
                 Host adapters
                         |
Platform capabilities (where available)
  Computer Control | AppFunctions | intents / notifications
  Assistant role   | BiometricPrompt + Keystore
```

Core uses four ports: Screen, Hands, Models and Confirm. The host advertises only operations it
can execute. Missing access yields an unsupported or denied result; the planner cannot invent a
grant. [SPEC §5.5](../SPEC.md#55-executable-contracts-and-evidence-agreed-7-nov-2026) defines the
operation contract, evidence and ambiguous-effect recovery requirements.

### Observation and action

- Computer Control is the intended route for other-app trees and GUI actions on the patched OS.
  Availability requires platform permission, assistant role, authorization and target consent.
- AppFunctions can expose structured operations when both platform and target app support them.
  The Computer Control patches do not establish an AppFunctions caller gate.
- Intents, deep links and notification replies offer narrower actions under ordinary Android
  permissions. Opening a composer is not proof that a message was sent.
- The requested app remains part of the task contract; routing cannot silently substitute another
  service. Every route passes the same app-side approval and provenance policy.

### Models and approval

Models belong in the app. Local inference backends are planned; the tested APK uses an HTTP
backend. An OS model-host service is not a requirement.

The app implements its approval UI using BiometricPrompt and a per-operation Keystore key.
Approval binds the resolved operation, including recipient, values and attachments. Input
filtering and real-device authentication still need security validation; emulator debug access
and scripted harness approval do not establish that boundary.

### Evidence and lifecycle

Execution, verification and benchmark outcome are separate. A successful dispatch or OS receipt
may leave an operation pending. The host must report uncertainty, revoked access and unavailable
observations rather than guessing. Stop and access revocation must prevent subsequent actions.
Privileged benchmark inspection must never become a production agent capability.

## Who owns changes

This repository owns adapters, API requirements and behavior when capabilities are unavailable.
[ballistic-os](https://github.com/mohammed7s/ballistic-os) owns GrapheneOS gate implementation,
shared-library packaging, OS signing, flashing, updates and platform validation. Its
[patch design](https://github.com/mohammed7s/ballistic-os/blob/master/docs/patches.md) explains how
that platform can provide access. Neither repository's successful build proves the other's
end-to-end integration.

## Adding another platform

Implement and test the supported ports, advertise the real capability set, and verify approval,
revocation, evidence and recovery on that platform. Reuse the Rust logic. Do not make platform
availability depend on a forecast regulatory deadline or assume identical APIs across vendors.

## Computer Control app integration — 22 November 2026

The exported ASSIST/DEFAULT activity qualifies Ballistic for owner selection through
`Settings.ACTION_VOICE_INPUT_SETTINGS`. The APK requests Computer Control and notifications,
loads `com.android.extensions.computercontrol` as an optional shared library, checks API 37,
library presence, extension version >= 5, role, permissions and versioned availability, then
uses `getInstance(activity, 5)` and Activity-context Params from a visible owner action.
A missing or incompatible library leaves ordinary intent/notification use available.

Version-5 Params name up to six explicit app-policy targets and include user-query attribution
and an ongoing, promotable notification with Stop. The platform's pending consent sender is
shown only through Review app consent. Ballistic never chooses roles, grants permissions,
changes AppOps or approves system consent itself. Owner destruction (including rotation), Stop,
creation failure, platform closure and blocking end the request/session. Cancellation invalidates
older callbacks; a late created session is closed. There is no automatic restart after blocking.

Screen reads and input use the session's virtual display. App policy remains separate from the
OS gates: Screen control defaults off for every old policy; screen reading requires Tree, and
device-only content cannot reach connected inference. Navigate permits opening, scrolling and
Back; arbitrary taps, long presses and typing need Full. Every-action approval is rechecked with
the app, policy and task state at dispatch. Individual platform input calls recheck session,
target and policy, including after the focus delay before typing. These restrictions do not
establish the broader planned safety design as validated.

The run's display route is pinned until it finishes, including on revocation. Typed intents are
neither advertised nor dispatched during screen control: their existing implementation launches
on the owner's display. They remain supported outside sessions. Home, screenshots and
AppFunctions are not supplied by this adapter. No screenshots are requested or retained.
The approval window is restricted to the default display and never a session target.

### Compile-only API provenance

No Soong-generated public stub jar was present in the delivered local/ThinkPad artifacts on
22 November; build servers were not accessed. The fallback Java headers were generated from
[GrapheneOS tag 2026110200's public signature](https://github.com/GrapheneOS/platform_frameworks_base/blob/2026110200/libs/computercontrol/api_extension/current.txt),
SHA-256 `0ecc8825d00906cbeb37b361bda0ec748ae9a9570c65e58bb0d3fee5825d4bf8`.
The current OS patches install the wrapper and publish stubs without changing that public API.
This is a verified signature fallback, **not** the actual Soong/metalava output.
`:computercontrol-stubs` is compile-only; the runtime implementation must come from the OS.

When the generated jar is available, build with
`-PcomputerControlStubs=/absolute/path/computercontrol.extension.stubs.public.jar`.
That replaces the source headers at compile time; never use an implementation jar as an APK
dependency. Check the final dex class definitions for absence of the extension package and verify
both Rust ABIs were rebuilt from the delivered source. See the
[delivery record](log/2026-11-22-computer-control-app.md) for actual results and pending checks.
