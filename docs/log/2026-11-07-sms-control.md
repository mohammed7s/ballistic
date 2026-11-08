# Simple SMS Messenger recipient control — 7 November 2026

Bounded offline candidate against Ballistic parent
`93c7624095a9072bc55983d5f56e434bde9dda68`. Only observation recognition, focused
development tests, complete normalized fixtures and this report change. No action, grant,
confirmation, verification, transport, planner instruction, budget or host schema change.

## Recorded evidence

Read-only source trace:
`/home/wakeel/Desktop/ballistic/ballistic_runs/core5_7a9238e/SimpleSmsSend/20261107-053339_SimpleSmsSend.json`.
SHA-256: `073e20928dbd1c886502a304052fb68b74508e9e6d8a2de75e06bcadd3208c62`.
The run used Ballistic `7a9238eba3880036dc507238ff2ff33b45c27e16`.
After address entry, attempted identification of e4 failed. The planner queried the title
and empty-contact placeholder, then repeated its unsuccessful reader query. Neither the
message composer nor a send was reached. This identifies an observation gap before composing;
it does not establish that supplying a label will make a planner finish the workflow.

The complete, unchanged normalized `ScreenState` values at `/lines/23/data` (64 nodes,
keyboard visible) and `/lines/34/data` (18 nodes, keyboard dismissed) are retained in
`core/tests/screens/known_controls/sms_new_conversation_keyboard.json` and
`sms_new_conversation.json`, respectively. Their provenance wrappers identify the original
trace and JSON pointers. All recorded nodes, strings, flags, bounds, installed-app entries,
capabilities and foreground fields are preserved. These are host-normalized observations,
not reconstructed original Android accessibility forests.

Both observations have foreground package `com.simplemobiletools.smsmessenger` and activity
`com.simplemobiletools.smsmessenger.activities.NewConversationActivity`. The relevant raw
normalized ancestry, before the core filter prunes containers, is:

- Node 1: root `container`, `android.widget.FrameLayout`, no resource, label or text.
- Node 2: child of 1, `container`, `android.view.ViewGroup`, resource
  `com.simplemobiletools.smsmessenger:id/new_conversation_coordinator`, no strings.
- Node 5: child of 2, `edit`, `android.widget.EditText`, resource
  `com.simplemobiletools.smsmessenger:id/new_conversation_address`, editable/clickable.
- Node 7: child of 2, `image`, `android.widget.ImageView`, resource
  `com.simplemobiletools.smsmessenger:id/new_conversation_confirm`, clickable/enabled/visible,
  null label/text, bounds `(954,296)-(1080,422)`. Other flags are false. No children.

All four nodes explicitly belong to the SMS package. The original planner line is
`e4 image id:new_conversation_confirm [clickable]`. Node numbers, bounds, current address,
title and placeholder strings are evidence about the recording, never runtime match constants.
The pinned harness normalizer (`harness/core_bridge.py`, SHA-256
`d75ea171924485132bb1bd41e7fba6df980a4377844e6b800c23c4c69f175882`) keeps parent links and
derives roles from class; its label combines content-description/hint/tooltip fallbacks.
The original pre-normalization forest is unavailable in these artifacts.

## Pinned primary evidence and installation limit

The run manifest pins AndroidWorld to `55ddaf5edc6f6c25673aed343907d5b9dca66f84`.
Its [app setup source](https://github.com/google-research/android_world/blob/55ddaf5edc6f6c25673aed343907d5b9dca66f84/android_world/env/setup_device/apps.py#L406)
specifies `com.simplemobiletools.smsmessenger_85.apk`. Retrieved source SHA-256
`c635ff38557211c4f64ba1d1f0234b1cfaa6dbe467be008c2501c78e65411b79` exactly matches the
recorded `source_hashes_before.json` entry. This identifies the configured source version;
it is not an installed-package measurement.

Upstream tag **5.19.3**, exact source commit
**`8b27d9f379e8bde62be5e77695366402f042af9a`**, supplies the following evidence:

- [Version catalog](https://github.com/SimpleMobileTools/Simple-SMS-Messenger/blob/8b27d9f379e8bde62be5e77695366402f042af9a/gradle/libs.versions.toml):
  package `com.simplemobiletools.smsmessenger`, versionCode **85**, versionName **5.19.3**.
  File SHA-256 `23d1fe3c821954a14256d2a258123274f336ba7822471a1c09e6ed3ae7ca39b2`.
- [Layout](https://github.com/SimpleMobileTools/Simple-SMS-Messenger/blob/8b27d9f379e8bde62be5e77695366402f042af9a/app/src/main/res/layout/activity_new_conversation.xml#L22):
  the dedicated address field and confirmation ImageView share the form; the latter draws
  a check icon and lacks a content description. Accessibility flattening differs from XML
  ancestry, so the runtime envelope uses the recorded normalized links above.
  File SHA-256 `f0301112e20a5a54b1e1ae242a2d858a562cf552edd79843a3decd474e4277bd`.
- [NewConversationActivity](https://github.com/SimpleMobileTools/Simple-SMS-Messenger/blob/8b27d9f379e8bde62be5e77695366402f042af9a/app/src/main/kotlin/com/simplemobiletools/smsmessenger/activities/NewConversationActivity.kt#L84):
  visibility follows address-search length. The exact click listener reads the address,
  rejects invalid short codes with letters, and calls `launchThreadActivity`. That method
  constructs and starts a `ThreadActivity` intent with recipient/title/text extras. This
  supports the code-owned label **Open conversation**, without claiming recipient validity,
  a send, or successful completion. File SHA-256
  `e73d1c884bb1a34007646b72bcce0c8f12a033df45dfa02bda35eb8de117f1d6`.

The transitive startup path was also inspected at that same commit:
[ThreadActivity](https://github.com/SimpleMobileTools/Simple-SMS-Messenger/blob/8b27d9f379e8bde62be5e77695366402f042af9a/app/src/main/kotlin/com/simplemobiletools/smsmessenger/activities/ThreadActivity.kt#L609)
loads the conversation and calls `setupButtons`. Forwarded `THREAD_TEXT` populates the editor;
its text-change listener updates availability/counters. Sending is wired to a separate Send
click or optional Enter event; the scheduled-message send-now path is a separate dialog
choice. None of those sends is invoked by the inspected activity startup path. Source SHA-256:
`8bef6a40d8c72f31d006b23affc672dd00c09a68c264069f97510d2018bb29dc`.
The [thread ID helper](https://github.com/SimpleMobileTools/Simple-SMS-Messenger/blob/8b27d9f379e8bde62be5e77695366402f042af9a/app/src/main/kotlin/com/simplemobiletools/smsmessenger/extensions/Context.kt#L844)
uses `Threads.getOrCreateThreadId`; it is not a send. File SHA-256:
`54caccd5a5daf2d50f3fcdc04695aa84d8b84c0611ab9d26c5703c31819caa93`.
Opening can still allocate thread metadata, restore drafts and cause normal read/notification
side effects. **Open conversation** names that navigation operation; **Confirm recipient**
would misleadingly suggest verified recipient identity or validity. This is not a claim of
side-effect-free navigation or of absence of independent background activity.

**Installed APK version/hash remain unverified.** The available installed-app entries contain
only label, package and activity. As with the existing compatibility profiles, the pinned
setup-to-source link plus exact recorded structure supports a bounded mapping; it does not
attest the installed binary, publisher, signature or runtime origin. No device call was used
to close that gap. Another version or application able to present the same metadata can match.

## Implementation and compatibility envelope

`core/src/screen_filter/sms.rs` recognizes one node before pruning or folding. It requires
the exact screen package/activity, positive screen size, unique node IDs, globally unique
confirmation/address/coordinator resources (including hidden or foreign copies), and one
explicitly SMS-owned root. Root and coordinator must have the recorded classes/roles,
no strings or interaction flags, and direct parent-before-child ancestry. The root has
only its coordinator child. Control and editable address are direct, childless siblings
under that coordinator, with exact ownership/classes/roles, visible enabled nonempty
on-screen bounds and compatible flags. A collection or wrapper inserted on that ancestry
fails recognition, including one that the ordinary filter would later prune.

Other form siblings may contain contacts or placeholders; their strings do not select this
identity and retain the existing filter policy. This is an anchored control profile, not a
claim to recognize every node on the page. Keyboard and SystemUI roots remain independent.
Different sibling order, node IDs, coordinates, address values and translated captions are
not reasons to reject an otherwise matching structure. Changed hierarchy/classes, multiple
app roots, hidden/disabled anchors, duplicate IDs/resources or unknown ownership are rejected.
This intentionally leaves unsupported versions/layouts without this additional static label.

The filter adds **Open conversation** to that exact source node, preserving its role, bounds,
flags and resource. Any label/text carried on the recognized image is retained byte-for-byte
as `Source::App` content, including whitespace, and cannot become typed/matched planner text.
Original screen values are never rewritten. This uses the existing content-preservation path;
it does not parse recipients, read goals, add a workflow, tap automatically or claim completion.
Existing action validation and per-app/confirmation requirements apply unchanged.

## Frozen development regressions and validation

Before production edits, `core/tests/sms_new_conversation.rs` was frozen at SHA-256
`3ad653bd7235ce352a6fd215c2e68a46276c74020a7daa53bfa5b7bf6f7ff4dd` and run against the
parent. It remains unchanged after implementation. The fixture hashes are
`0e48d8f4ec8a9f3e8a527c9ea5b871ee5d2dc0222c926903bc540b7205ac2cd1` (keyboard) and
`f6c64d7c3630554ee92c14284762e2153cae0b2cdbc522a4753970be4971511b` (dismissed).

The **55 cases improve from 48 passing / 7 failing to 55 passing / 0 failing**. The seven
positive/preservation cases cover both recorded screens, changed strings/recipient values,
renumbered/reordered siblings, resized geometry and empty input, a populated contact collection
sibling, and hostile multiline image strings even when matched by the request or typed history.
The 48 rejection cases cover wrong package/activity/ownership/resource/role/class/flags,
hidden/disabled/off-screen anchors, broken/forward/cyclic ancestry, collection spoofs,
missing or malformed address fields, duplicate IDs/resources including hidden copies,
multiple app roots and an attempted child-caption fold into the ImageView.
These tests guided development; they are **not held out** and provide no generalization score.

Commands run locally in `core/`:

```sh
cargo test --features uniffi --test sms_new_conversation
cargo test --features uniffi
cargo build --features uniffi --bin ballistic-core
cargo test --features uniffi --test sms_conversation_boundary
```

The parent baseline was reproduced: **214 Rust tests pass**. Full suite after implementation:
**269 Rust tests pass, zero failures/ignored tests**, including UniFFI and existing action,
reader, confirmation and completion regressions. One additional boundary test requested during
parent review also passes, for **270 passing Rust tests covered in total**. That single
`sms_conversation_boundary.rs` probe runs the real core agent/JSON-lines Bridge with scripted
host replies: observe, plan Open conversation, one ordinary tap dispatch, observe composer,
plan Send, hard confirmation requested, owner denial. Exactly one Hands operation occurs,
at the opening control; the send operation is bound in the confirmation request and is never
dispatched. The result is `policy_denial` with `done=false`. No live model or host is involved.
This post-implementation probe is additional development coverage, outside the frozen 55-case
before/after matrix. Final fresh debug binary SHA-256:
`c5fc30df42ee72a501dbaf39354408ebc2f090f4e74594e94c0155020e41ccdc`.
Offline filtering of all five recorded observations adds the label on exactly the two
confirmation screens, changing observed identification from **0/2 to 2/2**; both keep e4.
The other three observations receive no new label. Complete fixture/source JSON equality
and `git diff --check` also pass.

No Python host integration or JVM rerun was needed: neither host nor interface changed.
The parent's 94 Python / 51 JVM results are inherited, not newly measured here. No new APK,
device/emulator/SSH/ADB access, model/provider probe, credential access, push or merge occurred.
SMS reply completion, Calendar and Timer are outside this change. No live score uplift,
delivery guarantee, stronger semantic verification or origin-attestation claim is made.
