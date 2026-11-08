# Settings static observation — 7 November 2026

## Round two: Display compatibility and actual radio controls

This section supersedes the first-round missing-evidence/Display limitations below. Worktree
base `3050c7dc56303072140e0f2a80541e505ecd4009`; first-round patch cherry-picked cleanly as
`2cced83` (original `b6410b22c8b8e5e735ff76cb38a346487deee7ee`). Markor captions and creation
field labels, foreground completion, pacing, planner feedback, and unrelated `docs/CONTEXT.md`
were preserved. No agent, type/schema, verifier, model-client, prompt, budget, or task oracle
changes. Later parent Calendar work is not in this isolated checkout.

**No task/model validation or score uplift is claimed.** Last full suite remains **4/20 combined,
9/20 independent state**. The separate caption round's provisional 2 pass / 1 fail does not
change it. These are development regressions, not held-out evaluation or evidence of 13/20.

### Captures and restoration

Bounded observation sessions used only visible `emulator-5554`, explicit `adb -s emulator-5554`,
AndroidWorld console port 5554, and `CoreHost(models=None).observe()` from the clean worktree
`~/ballistic_validation/captions_96d9c94`, exact SHA
`96d9c94fe74f974c41d57b54d6006a30f9e0468e`. Its status was checked clean. No key was read, model
called, APK installed/built, emulator restarted, device clock changed, private settings/database
used, or physical phone accessed. Remote dirty main sources were untouched.

Each session checked active driver processes and exclusively created `~/ballistic_runs/emulator.lock`
with owner/PID/UTC timestamp before device operations; bounded alarms and `finally` removed only
its own matching lock. Genuine Display, Network, Internet, Connected devices, Connection
preferences and Bluetooth dashboard trees were captured with screenshots and UI hierarchy XML.
Routes used Settings intents, then GUI Connection preferences → Bluetooth. The AOSP manifest maps
`BLUETOOTH_SETTINGS` to Connected devices, not directly to the Bluetooth main-switch page.
An initial unsupported connected-device intent and several host settling failures were discarded;
no readiness checks were weakened or failed trees used as evidence.

Initial Wi-Fi and Bluetooth main switches both read `checked=true`. Only those two identified
GUI controls were temporarily toggled. Wi-Fi off was captured, and its original true state was
verified by fresh normalized observation. After Bluetooth's off toggle the current host session
reported `no_ready_foreground`, including cleanup acquisition. A new exclusively locked session
captured the genuine normalized off page, then restored Bluetooth through its freshly identified
GUI switch bar and verified `checked=true` twice through fresh UI hierarchies. The saved final
Bluetooth XML also reads true. No blind repeated toggle, Pair-page inference, connection-existence
inference, or CLI settings read/write was used. Locks were released on failed sessions too;
restoration was not claimed until readback succeeded. Independent SSH checking after the final
session confirmed no lock and no capture/driver process. The parent was notified and subsequently
assigned the device to another agent; all subsequent work here stayed offline.

Raw captures remain outside the repository on ThinkPad under
`~/ballistic_runs/settings_observation_round2{,b,c,d,e,f}/`, from 04:42–04:53 UTC, and downloaded
under `/tmp/settings_captures/` locally. Session provenance records owner/PID/time and capture SHA.
Fixtures under `core/tests/screens/settings_static/` are **sanitized synthetic copies**: Settings
nodes only, installed-app/capability metadata removed, summaries/carrier/SSID/device/footer and
injected-tile strings replaced with synthetic text. Structure, geometry, ownership, roles,
resource IDs, visibility, enabled and checked flags remain observed. Static resource titles
remain intact. Each fixture records its raw-wrapper hash (Display's historical extraction also
records original run SHA and reply 14). No raw run artifact or account data was copied into Git.

### Parent objections and implementation responses

- **Display lost seven previously named controls.** The complete captured Display envelope now
  restores all seven, including Navigate up. `PrimarySwitchPreference`/`DarkModePreference` has
  separate row navigation and switch click listeners: the filter keeps clickable Settings
  widgets separate instead of incorrectly folding them into navigation. Dark theme therefore
  adds one correctly identified switch at its original bounds, preserving its observed state
  and enabled flag. Four visible category headings and the toolbar also regain code-owned
  labels. Hidden Colors/Auto-rotate titles do not grant actionable identity. The old keyword
  fallback was not restored.
- **No actual Wi-Fi or Bluetooth switch evidence.** Both checked states are now captured for
  both controls. Internet requires the complete toolbar/list signature, static preference
  anchors, a real `RestrictedSwitchPreference`, and explicitly validated opaque mobile-provider
  and long-press Wi-Fi row forms (or the captured footer variant). Bluetooth requires its
  separate `switch_bar`/`switch_text`/actual switch hierarchy and captured preference/footer
  variant. The observed `checked` flag is copied unchanged; layout presence does not imply state.
  Synthetic inverted-flag tests independently verify that layout does not select the state.
- **Safe navigation without dynamic text leakage.** Dedicated manifest activities are allowed
  only for their corresponding full profiles; SubSettings still requires the whole profile.
  Direct activity names and any collapsing Settings toolbar tighten raw-text suppression,
  including with an arbitrary wrong activity. Network/Connected retain their earlier identities;
  the captured Connected variant lacking Pair/See-all summaries is supported without deriving
  Bluetooth state. Carrier/SSID rows and Bluetooth's device summary/footer remain content even
  when their strings collide with static labels. Malformed trees, ownership/role/activity
  changes, missing anchors, extra unknown descendants and title collisions fail closed.
- **Injected tile count is installation-dependent.** Agreed: three is **not** an AOSP control
  identity. `DashboardFragment.createPreference()` builds injected tiles as ordinary preferences;
  their normalized title-and-summary rows are indistinguishable from Cast/Printing. Arbitrary
  opaque extras would let a renamed injected row replace a missing generic static anchor.
  This round therefore retains the entire observed Connection preferences envelope: Bluetooth,
  Cast, Printing and exactly three structurally checked **opaque** injected rows. Tile names are
  never compared to recorded vendor/app names and never emitted as labels. Added/removed irrelevant
  tiles reject the whole profile; collisions and replacing a removed static anchor with an
  existing injected tile also reject. This is a narrow compatibility restriction, not proof of
  row origin. Host metadata lacks preference keys/fragment/provider provenance; a fully fabricated
  replacement with identical metadata and restored total cardinality is indistinguishable.
  Supporting variable installations safely needs stronger producer identity, not a guessed count
  or allowlisting installed-app names. No generalized Connection preferences support is claimed.

Additional source at the same pinned Settings commit `e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6`:
[manifest activities](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/AndroidManifest.xml),
[Display controls](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/res/xml/display_settings.xml),
[DarkModePreference](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/display/darkmode/DarkModePreference.java),
[Internet controller and dynamic entries](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/network/NetworkProviderSettings.java),
[LongPressWifiEntryPreference](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/wifi/LongPressWifiEntryPreference.java),
[Bluetooth main-switch wiring](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/connecteddevice/BluetoothDashboardFragment.java),
[Bluetooth preferences](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/res/xml/bluetooth_screen.xml),
[injected dashboard preferences](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/dashboard/DashboardFragment.java).
SettingsLib at pinned AOSP tag `android-13.0.0_r1`:
[PrimarySwitchPreference separate listener and caption](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/packages/SettingsLib/src/com/android/settingslib/PrimarySwitchPreference.java),
[main switch layout](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/packages/SettingsLib/MainSwitchPreference/res/layout/settingslib_main_switch_bar.xml).
These sources explain the observed structure, not an attestation that the installed APK is
built from exactly that commit. Provider/vendor differences remain a compatibility limit.

### Frozen regressions, measured restoration and checks

Before round-two production edits, froze the 14 residual test definitions in
`core/tests/settings_residual_controls.rs`, SHA-256
`2b40da0dd4c203f7e2c786876a317b1f60b42413cf247e6e4a6c5af5d7b21398` (unchanged).
Bluetooth's off-state fixture was acquired later during restoration; the test definition was
already fixed. All before/after tests use the same final sanitized fixtures. Baseline was rerun
from a source archive of `2cced83` with a **separate Cargo target directory** to avoid stale
same-target reuse. Frozen cases: **2 passed / 12 failed → 14 passed / 0 failed**. They cover
Display navigation/split-switch semantics, both radio states, dedicated activities, Connection
preferences, hostile summaries, SSID/carrier collisions, spoofed toolbar, wrong ownership/roles/
activities/layouts, duplicate/missing/foreign nodes, hidden anchors, anonymous-switch evidence,
and independence from IDs/geometry/request/typed history. Seven additional review tests cover
arbitrary-activity fallback, row reordering, injected rows and collisions, layout/state
independence, switch metadata mutations, tile-count changes and missing-anchor substitution.
They are outside the frozen denominator.

Empty-request filtering of the final fixture set gives these counts. Each pair is **named visible
controls / labelled elements**; controls include disabled visible controls. No task/goal string
selects an identity. Counts are observation coverage, not task success.

| Captured page | First-round patch on current base | Round two |
|---|---:|---:|
| Network, historical SubSettings | 9 / 9 | 9 / 9 |
| Network, dedicated activity | 0 / 0 | 9 / 9 |
| Connected, historical SubSettings | 3 / 3 | 3 / 3 |
| Connected, dedicated activity / brief summaries | 0 / 0 | 3 / 3 |
| Pair new device | 0 / 0 | 0 / 0 |
| Display, historical SubSettings | 0 / 0 | 8 / 13 |
| Display, newly captured dedicated activity | 7 / 12 | 8 / 13 |
| Internet, Wi-Fi checked true | 0 / 0 | 5 / 5 |
| Internet, Wi-Fi checked false | 0 / 0 | 4 / 4 |
| Connection preferences | 0 / 0 | 3 / 3 |
| Bluetooth, checked true | 0 / 0 | 3 / 3 |
| Bluetooth, checked false | 0 / 0 | 2 / 2 |

The pre-first-round historical Display result was 7 controls / 12 labels, as measured below;
round two restores all seven and correctly separates the eighth switch. No previously named
control in these fixtures is lost. Unknown SubSettings, other locales, partially scrolled or
trimmed layouts, populated Connected-device dashboards, different injected-tile counts, multiple
mobile providers, connected Wi-Fi gear variants and unrecorded footer/feature variants still
fail closed. Internet currently requires the recorded one-mobile-provider family. Recognition
of both checked values does not prove eventual connectivity or a successful state change.

Validation on this branch: initial cherry-pick **138 Rust tests with UniFFI passed**; final
**159 Rust tests with UniFFI passed**, **79 Python tests passed, zero skips**. Python used the
freshly built local `ballistic-core` through `BALLISTIC_TEST_CORE_BIN`. `git diff --check` passes.
Rustfmt is unavailable in the installed toolchain; no toolchain download was performed. Python's
mocked injection-check tests intentionally print an ERROR/lock example while unittest reports OK.
Anonymous-switch verification remains unchanged and negative cases pass. A named switch is still
only eligible visible-UI evidence: the model verifier does not enforce semantic goal coverage.
No live model/task/injection validation was run by this implementation round.

Reproduction uses the same commands as the first round below, plus
`cargo test --offline --manifest-path core/Cargo.toml --features uniffi --test settings_residual_controls`
and `--test settings_residual_safety`. Raw capture hashes and sanitization are recorded per fixture;
raw restoration evidence is `settings_observation_round2e/internet_restored.json` and
`settings_observation_round2f/bluetooth_restored.xml` on the paths above.

---

## Historical first round (before round-two repair)

Offline development work based on `6d2bebb`. No live model, emulator, SSH, ADB, phone,
AndroidWorld checker, or OS repository operations. This cannot establish live uplift:
the last full suite remains **4/20 combined, 9/20 independent state**.

## Frozen development cases

Before implementation, froze `core/tests/settings_static_controls.rs` (20 tests),
SHA-256 `fc62db17e81c5dd8c2d2ea627600568e6fcdce87bf405aa7cc3a311650c32cb8`.
These are development regressions, **not held-out evaluation**. The tests contain variants
within each case; pass counts below count Rust test functions, not individual variants.

Cases: recorded network controls; recorded connected navigation; actual checked/disabled
flags; changed node IDs/order/geometry/request; hostile summaries; hostile discoverability
footer; Pair-page non-evidence; device/network names equal static titles; renamed toolbar;
wrong activity/foreground package; cross-package ancestor/title/switch; wrong role/class/
parent; anonymous/mislabelled switches; duplicate/extra/missing rows; hidden/offscreen
anchors; raw control caption; generic IDs on unknown subpage; owner-matched device name;
summary/title collisions; duplicate node IDs.

Fixtures under `core/tests/screens/settings_static/` retain the unchanged recorded screen
and include provenance. Extracted from absolute parent paths under
`/home/wakeel/Desktop/ballistic/ballistic_runs/core4_20261107/`:

- `network.json`: `20261107-024001_SystemWifiTurnOff.json`, reply 7;
  SHA-256 `337c4358a63b567d5fb0daeb5aa70e4f3ebc6faa5a17e10a4ed2534d156de3df`.
- `connected.json`: `20261107-024148_SystemBluetoothTurnOn.json`, reply 7;
  SHA-256 `9282ccc3d0c27eeb082ad7b47b82cd18a2065907ce6449f84aa962f20be50132`.
- `pairing.json`: same Bluetooth recording, reply 23;
  SHA-256 `672d92d0f9a3bac2247f2b98078aa96dc86129fcb9970a4b2f0c7d672815f7bf`.

## Source investigation

Primary source pin: AOSP Settings tag `android-13.0.0_r1`, commit
`e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6`, tree
`f2731a464a5e7e4190c1a39b6f3a0314bb1a1e59`.
The web tool could not open Gitiles; direct HTTPS `?format=TEXT` source retrieval worked.
This pin supports the observed structure; the recordings do not authenticate the installed
Settings APK as this exact commit. No universal Android-version compatibility is claimed.

Package + SubSettings + toolbar is insufficient. SettingsActivity accepts title text;
MobileNetworkSettings sets the activity title from the subscription's display name;
NetworkProviderSettings passes Wi-Fi entry titles to the details subpage. Sources:
[SettingsActivity](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/SettingsActivity.java),
[MobileNetworkSettings](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/network/telephony/MobileNetworkSettings.java),
[NetworkProviderSettings](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/network/NetworkProviderSettings.java).

The NetworkDashboardFragment inflates `network_provider_internet`, whose controls use static
resource titles. Internet is a navigation row into NetworkProviderSettings. Airplane mode
is a separate RestrictedSwitchPreference. Internet's SSID/carrier and VPN's connected provider
belong in summaries, never the identity. The older `network_and_internet.xml` is deprecated
and does not describe this recording.
[NetworkDashboardFragment](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/network/NetworkDashboardFragment.java),
[network_provider_internet.xml](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/res/xml/network_provider_internet.xml),
[InternetPreferenceController](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/network/InternetPreferenceController.java).

ConnectedDeviceDashboardFragment inflates `connected_devices`. Pair new device, See all,
and Connection preferences are static navigation rows. Saved/available device groups add
dynamic rows. BluetoothDevicePreference sets its title to the remote name and uses a gear
widget; DiscoverableFooterPreferenceController incorporates the local device name in its
footer title. Neither those names nor the footer can become static labels.
[connected_devices.xml](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/res/xml/connected_devices.xml),
[BluetoothDevicePreference](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/bluetooth/BluetoothDevicePreference.java),
[PreviouslyConnectedDevicePreferenceController](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/connecteddevice/PreviouslyConnectedDevicePreferenceController.java),
[DiscoverableFooterPreferenceController](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/connecteddevice/DiscoverableFooterPreferenceController.java).

## Implementation and results

No production code changed at fixture freeze. The frozen test file still has the hash above.
Baseline on `6d2bebb`: **10 passed, 10 failed / 20**. The seven positive mapping cases failed;
the other three failures exposed inherited trust when the toolbar was renamed or foreign-owned.
Final: **20 passed, 0 failed / 20**. Four supplemental review tests in
`settings_static_safety.rs` pass too: typed-history collisions, hidden optional-row titles,
missing ownership, and extra gear/foreign descendant nodes. These were added during review,
not included in the frozen before/after denominator.

`core/src/screen_filter/settings.rs` supplies code-owned labels only after checking the whole
page tree against its source-backed signature. It requires the exact foreground package/activity,
one Settings root, content/toolbar/recycler hierarchy, explicit ownership on every structural
node, class/role/resource/flags agreement, one exact title for every expected row, and no extra
rows or descendants. Duplicate IDs, dangling parents, and out-of-order ancestry reject the
signature. Required anchors must be visible/on-screen. There is no row-number, coordinate,
task-name, owner-goal, carrier, SSID, or checked-value selector. Reordering complete rows and
renumbering nodes preserves recognition. The hidden Adaptive connectivity row is accounted for
structurally but gets no actionable identity unless its row and title are visible.

The signatures cover the recorded English Network & internet variant (ten static rows,
nine visible) and Connected devices variant without dynamic device rows (three navigation
controls, Saved devices heading, and opaque footer). Static output strings are constants;
the actual displayed strings still go through content handling. Generic `android:id/title`
and `android:id/switch_widget` are never sufficient by themselves. A complete alternate page
with the same toolbar, or a device row named Internet/Connection preferences, is insufficient.
The source detail/list layouts differ structurally from these dashboards, and their dynamic
titles cannot supply the required complete static sibling structure. This relies on the host
accurately reporting ownership/tree metadata and on the pinned layout family, not cryptographic
APK attestation. A malicious Settings implementation or host is outside this filter's boundary.

The Airplane row receives its real observed switch flags through the existing fold; both on
and off values are tested. No Wi-Fi or Bluetooth state is synthesized. No verifier predicate,
action contract, schema, FFI, model, budget or launch-completion code changed. Markor captions
are preserved. The existing `agent_checks` regression was updated because its original assertion
required the now-proven Airplane control to be anonymous. It checks the actual Airplane identity
and absence of a Wi-Fi identity, then removes its title and confirms that anonymous-switch
citations are still rejected. **The verifier still does not enforce semantic goal coverage**:
a model can wrongly cite a *named* Airplane control for a Wi-Fi claim. This patch makes the
distinction visible; it does not prove the model will use it correctly.

Summaries, local device names in the footer, and unexpected page text remain content, including
after folding. A match against earlier typed text cannot promote a non-editable Settings value
to `(typed)` planner text. The pre-existing full owner-request match exception still quotes
matched content as `(matches request)`; it never gives an anonymous switch control identity.
No raw unrequested summary or footer text reaches the planner in the regression cases.

Full local validation:

- Baseline `6d2bebb`: **97/97 Rust tests with UniFFI** (clean source archive in `/tmp`).
- Final: **121/121 Rust tests with UniFFI**, including the frozen 20 and four supplemental tests.
- Final: **75/75 Python harness tests, zero skips**, including scripted bridge integration.
- `git diff --check` passes. `rustfmt` was unavailable locally; no toolchain download was made.

Reproduction (Cargo executable here is `/home/wakeel/.cargo/bin/cargo`):

```bash
cargo test --offline --manifest-path core/Cargo.toml --features uniffi --test settings_static_controls
cargo test --offline --manifest-path core/Cargo.toml --features uniffi
cargo build --offline --manifest-path core/Cargo.toml --bin ballistic-core
BALLISTIC_TEST_CORE_BIN="$PWD/core/target/debug/ballistic-core" python3 -m unittest discover -s harness -p 'test_*.py'
```

The Python injection-check tests intentionally print an ERROR/lock message for mocked incomplete
records; their unittest results are PASS. They do not start or connect to an emulator.

Offline comparison of all distinct SubSettings trees in the raw Core4 recordings, using the
baseline and final `ballistic-core filter` with an empty request, finds:

- Network & internet: **0 → 9 named visible controls** on each of three distinct observations
  (Wi-Fi replies 7/26 and TurnOnWifiAndOpenApp reply 7).
- Connected devices: **0 → 3 named visible controls**, reply 7.
- Pair new device: **0 → 0**, replies 12/23. Still no Bluetooth state evidence.
- Display: **7 → 0 named controls** (12 → 0 labelled elements), Brightness reply 14.
  This is an observed compatibility loss from the conservative fallback, not a success.

## Limitations and missing evidence

Unknown SubSettings pages now default to content even when the toolbar contains no device/network
keyword. Otherwise a network named `Airplane mode`, `VPN`, or arbitrary innocuous text bypasses
the old keyword guard. Restoring the old fallback to preserve Display would reopen that gap.
Display needs its own source-backed structural identity support before this change can preserve
all previously visible navigation. Its recorded split switches also need care: the separately
clickable widget and row may perform different actions. This patch deliberately does not
identify those controls by their position or infer switch identity from a generic resource ID.

The strict signatures reject different locales, missing summaries, different feature/vendor
rows, reordered child hierarchy, device-populated Connected devices pages, partial/scroll-trimmed
trees, and changed layouts. The Bluetooth controller removes the Pair-row summary when on;
that unrecorded variant is intentionally unsupported. None of these rejected variants imply a
device state. Generic SubSettings suppression can reduce navigation elsewhere too, even when
the old heuristic happened to show safe text. Unit-test success is not end-to-end suitability.

No recorded normalized Internet/Wi-Fi control page, Connection preferences page, or Bluetooth
dashboard with a real main switch was found in the local run JSON search. Source indicates the
route and likely structures but is not a substitute for the missing host observation evidence:

- [network_provider_settings.xml](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/res/xml/network_provider_settings.xml)
  defines a `main_toggle_wifi` RestrictedSwitchPreference amid dynamic mobile/Wi-Fi entries.
  Its key is not present as an individual accessibility resource ID in the available recordings.
- [connected_devices_advanced.xml](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/res/xml/connected_devices_advanced.xml)
  routes Bluetooth to BluetoothDashboardFragment;
  [BluetoothDashboardFragment](https://android.googlesource.com/platform/packages/apps/Settings/+/e41cbfdd301b56e4c6f3ca38ad7116438d94f2b6/src/com/android/settings/connecteddevice/BluetoothDashboardFragment.java)
  supplies a main switch through SettingsMainSwitchBar, with `bluetooth_screen.xml` preferences.
- Needed next: normalized GUI observations of those actual pages, including package/activity,
  full parent hierarchy, exact role/class/resource IDs, and checked flags. Prefer examples with
  device/SSID names colliding with static labels and both checked values. No private state reads
  or benchmark oracle should become production observation.

The Airplane identity repair, tests, and source investigation are offline evidence only. No new
13/20 result, task completion improvement, model cost/latency improvement, or live safety result
is claimed. Integration should account for the Display/unknown-subpage compatibility loss.
