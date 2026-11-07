# Ballistic

**An owner-controlled, local-first AI assistant for GrapheneOS.**

Ballistic gives a Pixel running GrapheneOS an assistant with the powers Google reserves for Gemini
(see any app's screen, act in any app, wake word, structured app functions) while keeping every one
of those powers in the hands of the phone's owner: per-app grants the user sets, models that run on
the device, and a confirmation step the agent can raise but never press. The name is from graphene
itself: electrons move through it *ballistically*, fast and straight, without scattering.

Status: **design and harness stage** (November 2026). The agent loop runs on an emulator; nothing
ships yet. The Rust core has an initial tree agent, confirmation hooks, verifier, element reader
and terminal owner-only source excerpts; general reader facts, provenance rules, deny-only judge
and recorded skills are still planned. The terminal prompt drives the harness. A native prototype
APK has demonstrated Clock launch and Stop; other-app observation and the latest-core rebuild
remain unfinished. No localhost/browser UI is planned.
Apache 2.0. Not affiliated with or endorsed by GrapheneOS.

## Why

Android 17 already contains the machinery for a system-level assistant: **Computer Control**
(a privileged assistant operates apps in a secure virtual display), **AppFunctions** (apps expose
callable actions), an empty **OnDeviceIntelligence** socket for a system model host, and hotword
detection. Google uses them for Gemini. On every other phone they are gated by Google's remote
allowlists and privileged-app status, so nobody else can use them. GrapheneOS, which ships no AI at
all, carries the same code with the gates closed.

The EU's DMA decision of July 2026 will force Google to open these features to third-party
assistants on stock Android by August 2027, on Google's terms and with Google's certification. We
don't want to wait, and we don't want Google's terms. Ballistic opens the same gates on GrapheneOS
with the user, not a vendor, deciding who may use them.

Two products have shipped a system-level agent so far: Gemini screen automation (Pixel 10 / Galaxy
S26, cloud model, selected apps, US/Korea) and ByteDance's Doubao assistant on ZTE's Nubia phone
(OS-level input injection, cloud model; WeChat, Alipay and banks locked it out within days). Both
are vendor-controlled and cloud-first. Nobody has built the owner-controlled, local one.

## Principles

1. **The agent can touch anything on the phone except the confirmation dialog.** Sends, payments,
   deletes and installs require a real touch on a window that drops injected input; the agent can
   bring it up, never approve it.
2. **Hostile content can never act.** Text read from a message, page or screen only ever reaches a
   model that cannot call tools; the planning model sees labels and structure, not raw text.
3. **Local by default.** Models run on the phone. Any payload leaving the device is opt-in, per
   app and per role, with a visible indicator. Wake word and speech-to-text never leave.
4. **No privileged third party.** Not Google, not us. The user grants the assistant role and can
   revoke it. GrapheneOS's hardening is untouched.
5. **Parity, not stealth.** Ballistic uses the same Computer Control path Gemini uses on a Pixel
   and is exactly as visible to apps. No device spoofing, no attestation games, no automating
   logins or payments.
6. **Built to be merged.** OS-side changes are small, generic, user-facing gates written as deltas
   on AOSP, in the style GrapheneOS already accepts (Network and Sensors permissions, Storage and
   Contact Scopes). The assistant itself is an ordinary installable app.

## How it works

```
┌──────────────────────────────────────────────────────────────────────────┐
│ APPS       Signal · WhatsApp · Mail · Maps · Bank · Browser · …           │
│            Ballistic app: voice, planner, reader, skills, policy, models   │
├──────────────────────────────────────────────────────────────────────────┤
│ GATES      Computer Control · AppFunctions · assistant role · hotword     │
│ (AOSP,     today: Google DeviceConfig allowlists, privileged-app only     │
│  user-     Ballistic: user-managed allowlist + Settings toggle, default   │
│  gated)    empty, assistant-role requirement kept                          │
├──────────────────────────────────────────────────────────────────────────┤
│ OS         GrapheneOS, unchanged hardening · our signing keys · our OTA    │
├──────────────────────────────────────────────────────────────────────────┤
│ HARDWARE   Pixel: Tensor GPU/TPU for on-device models                      │
└──────────────────────────────────────────────────────────────────────────┘
```

- **v1** is the app alone, on a GrapheneOS build whose only changes are the gate patches: voice in
  and out, a biometric command gate, planner/reader split, per-app policy, confirmation window,
  skill record-and-replay, and on-device models (Gemma 4 E2B/E4B class via LiteRT-LM, picked by
  hardware). This is also exactly what would run on stock Android if Google opens the same gates.
- There is no v2 in the OS: Ballistic is always an app (decision 7 Nov 2026). The agent's brain
  (`core/`) is written once and shared by the Android app, the laptop test bench and a future iOS
  app.

The 7 November 2026 design refinement makes each host expose only operations it can execute,
with typed inputs, affected app, required grants, confirmation and completion evidence. Ballistic
preserves the app you request and can use that app's structured API without opening its screen.
Approval covers the exact recipient, value and attachments on every route. Opening a screen or
receiving an acceptance receipt does not prove completion: execution status, verification and
evidence are recorded separately. An uncertain send is inspected before retrying; without reliable
readback or idempotency, it stops. These are implementation requirements; current coverage is in
[`core/README.md`](core/README.md) and [`docs/CONTEXT.md`](docs/CONTEXT.md).

## What we would ask of GrapheneOS

Three small, independent changes, each defensible on privacy grounds alone and each off by default:

1. **Computer Control gate**: enable the feature flags, and supplement the Google-DeviceConfig
   allowlists (`allowed_session_owner_apps`, `allowed_automatable_apps`, dead code on GrapheneOS
   today) with a user-managed store and a Settings page. Assistant-role and per-target consent
   requirements stay on. Computer Control requires the assistant to ship as a privileged app
   with `ACCESS_COMPUTER_CONTROL` allowlisted in the image.
2. **AppFunctions gate**: the same user-managed allowlist for callers.
3. **OnDeviceIntelligence provider**: let the user choose the on-device model service, the way they
   choose a keyboard, instead of a hard-coded OEM component.

We will bring a working assistant as the demonstration and keep our fork until (and unless) these
land. The agent itself is not proposed for upstream.

## Status and plan

| Step | State |
|---|---|
| 1. Harness: emulator + [AndroidWorld](https://github.com/google-research/android_world) + local models, agent loop driving a phone | **Running.** First full task completed end to end (failed on quality, not plumbing). |
| 2. Core library: filtered UI tree, planner/reader split with source tags, deny-only judge, skills, routing, injection tests | **Initial Rust implementation.** Element reader and terminal owner excerpts implemented offline; general facts/provenance/judge/skills pending (`core/README.md`). |
| 3. Assistant as an ordinary app (no privileges) on any Android phone | |
| 4. Own GrapheneOS build, own keys, own OTA | |
| 5. Computer Control gate; assistant runs through `ComputerControlSession` on a Pixel | |
| 8–9. Capability suite, CI rebase bot, upstream proposal (6–7, OS-side model host and policy service, dropped: Ballistic is always an app) | |

Full plan: [`docs/PLAN.md`](docs/PLAN.md). Specification: [`SPEC.md`](SPEC.md). Decisions, verified
facts (checked against `android17-release` source) and open questions: [`docs/CONTEXT.md`](docs/CONTEXT.md).

## Repositories

Two repositories, no forks of AOSP until a pull request upstream needs one:

| Repo | What |
|---|---|
| **`ballistic`** (this) | the assistant app, the agent logic, the emulator harness, the design docs |
| [**`ballistic-os`**](https://github.com/mohammed7s/ballistic-os) | the GrapheneOS build recipe: a pinned GrapheneOS tag, the gate patches as plain `git format-patch` files, sepolicy and device config, build/sign/OTA scripts, the CI rebase bot |

```
ballistic/
  harness/     emulator + AndroidWorld + model setup and the task runner (works today)
  core/        platform-independent agent logic (planner, reader, judge, skills, routing)   [design agreed: core/README.md]
  apps/        assistant app (Android shell; iOS later), system keyboard                    [planned]
  tests/       capability suite, injection tests                                            [planned]
  docs/        plan, context, agent-security survey, archive of earlier design documents
  docs/guide/  plain-language HTML pages: how it works, the agent, trying it on the emulator
```

The OS side is kept as patches against GrapheneOS's own tree rather than as forked repositories,
so that what we change is three readable text files and the build is "GrapheneOS at tag X plus
these".

## Contributing and contact

Issues and pull requests are welcome. The most useful things right now: running the harness on
your own hardware and reporting step times per model, corrections to the facts in
`docs/CONTEXT.md`, and experience with Computer Control or AppFunctions on Android 17.

Maintainer: [@mohammed7s](https://github.com/mohammed7s).
