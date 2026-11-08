# Platform support and integration contract

Ballistic is an Android app with a shared Rust core. The OS supplies capabilities; the app
supplies planning, models, policy, approvals and workflow storage. This page describes that
boundary. It does not promise that every Android phone supports every action.

## Current support

| Environment | What is available | What remains unproven or unavailable |
|---|---|---|
| AndroidWorld emulator + laptop harness | UI-tree observations, ADB actions, configured model endpoint, independent benchmark checks | A development environment, not a standalone phone deployment; scripted approvals are not biometrics |
| Ordinary Android app, including stock GrapheneOS | APK minimum API 33; native UI and Rust engine; emulator smoke demonstrated Clock launch and Stop | Other-app screen observation and GUI input are absent in the tested APK; latest core needs rebuilding; no broad device compatibility claim |
| GrapheneOS with ballistic-os | Computer Control patch set compiled (eleven-patch form, since re-cut to eight with the same source); factory image signed, flashed and relocked in the recorded test | Patched boot/build identity and runtime Computer Control checks remain unverified in the recorded evidence; privileged app integration and app-side adapter are not established |
| Future stock Android assistant APIs | Intended integration through new host adapters | API availability, device coverage, permissions and distribution requirements must be verified when usable; no promised date or drop-in compatibility |
| iOS | Rust core is intended to be reusable | No iOS app or general screen-control route; any future shell is limited to capabilities that iOS permits |

The ordinary APK is not the privileged assistant package needed by the current Computer Control
integration. Selecting the assistant role alone does not supply privileged permission. Ordinary
intents and notification access have their own grants and limitations.

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
privileged packaging, OS signing, flashing, updates and platform validation. Its
[patch design](https://github.com/mohammed7s/ballistic-os/blob/master/docs/patches.md) explains how
that platform can provide access. Neither repository's successful build proves the other's
end-to-end integration.

## Adding another platform

Implement and test the supported ports, advertise the real capability set, and verify approval,
revocation, evidence and recovery on that platform. Reuse the Rust logic. Do not make platform
availability depend on a forecast regulatory deadline or assume identical APIs across vendors.
