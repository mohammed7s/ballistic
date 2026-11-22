# Computer Control app integration — 22 November 2026

This record concerns Ballistic's app only. The OS was not edited, flashed, rooted or granted
permissions through shell commands. No build server, benchmark campaign or paid inference was
used. The ThinkPad built a fresh app snapshot in `~/ballistic_cc_20261122`; its existing
`~/ballistic_cc` and dirty Dropbox clone were preserved.

## Source and compatibility

Base: master `71f5418`. Reused the app components from `5d1fd1e` after inspecting its dependency
on current typed-intent source. Did not import `8be1410`'s privileged emulator setup. `b046a7b`'s
old privileged-install guidance is superseded by the current OS contract. Of the two earlier
remote build fixes, the stub module's `Properties` import is included; `build_core.sh` already
matches current master and correctly resolves Cargo's configured target directory.

Changes include visible-Activity v5 session construction, versioned capability checks,
user-query attribution, promoted ongoing notification metadata and a private Stop receiver,
normal default-assistant Settings navigation, explicit app-policy target/read/input controls,
cancellation generations, Activity destruction/closure/blocking handling, and a fixed display
route per run. Input rechecks state and front app between focusing and typing. Screen reads
filter both window roots and embedded subtrees by target and read policy. Approvals stay on the
owner's default display. Typed intents remain supported outside CC and disabled inside it.

No generated Soong public stub jar was present in the local/ThinkPad delivery. Compile-only
Java headers were regenerated from the exact public API at GrapheneOS `2026110200`, SHA-256
`0ecc8825d00906cbeb37b361bda0ec748ae9a9570c65e58bb0d3fee5825d4bf8`.
That signature also matches the prior inspected AOSP cache. This is a signature-derived
fallback, not the actual metalava/Soong compiled stub artifact. The Gradle property
`computerControlStubs` can select that artifact later. No runtime wrapper is bundled.

## Build and tests

Remote logs/artifacts: `~/ballistic_cc_20261122/`. Local delivery copies:
`ballistic_runs/cc_20261122/` (ignored).

- Fresh `scripts/build_core.sh`: arm64-v8a, x86_64 and host libraries built from this checkout's
  current Rust source, then UniFFI regenerated. The generated Kotlin binding is byte-identical
  to the committed binding (`f3ad8bb931198d09b0b5931050fc19ddba4bf75677281be933ea36ff8087da1e`).
- `cargo test --locked --features uniffi`: **452 passed, zero failed/ignored**, 40 result groups
  including empty binary/doc-test groups. Scripted host tests, not phone or live-model evidence.
- The first Gradle attempt exposed a nonpublic Settings constant and a test/edit synchronization
  issue. The shortcut now uses public Apps settings; subsequent builds use a synchronized
  source snapshot. Host Rust tests explicitly receive `BALLISTIC_HOST_CORE` because Cargo's
  target directory is outside the checkout.

- Final `:core-api:test :app:assembleDebug` with the freshly built host core: **192 tests,
  zero failures/errors, 3 skipped** (optional model download and two llama JNI tests).
  Final Gradle build succeeded in 1m16s; native C++ outputs were compiled during the preceding
  successful 13m3s build. Logs: `native-build.log`, `rust-tests-all.log`, `gradle-delivery.log`.

## Signed delivery and preservation

Final APK: `ballistic_runs/cc_20261122/ballistic-cc-20261122-latitude.apk`, 68,765,648 bytes.
SHA-256: `9ab7e84b652f7fe2ef08fc28d15180b7aac1f52fd457d621811f49ec10bb37bb`.
The Latitude debug key signed it locally; the private key never left Latitude. APK signature v3
verification passed and the certificate matches the previous installed APK:
`39de30edd4fbb9e9ba991af75998fa34480c9401fe59de37f184934a8add8236` (certificate SHA-256).

DEX class-definition inspection found the app adapter and **zero bundled extension implementation
classes** under `com/android/extensions/computercontrol`. All 14 expected native libraries are
present, including current core and llama libraries for arm64-v8a and x86_64. Packaged core hashes:

- arm64-v8a: `87ca2608fb03a2280d01ed9c65e1afa06640d149958e672a85fcb3a6d4715e19`
- x86_64: `ad93dffddb6ca3e75ab23558bfb55de7d5e36791efa856983f8ae4a6f38029cf`

ThinkPad independently verified the transferred artifact hash/certificate, then
`adb -s 56181FDCH0070R install -r` returned **Success**. The installed `base.apk` SHA-256 equals
the final artifact above; last update is `2026-11-22 11:34:26` (device time). First install remains
`2026-11-07 23:08:45`. OS fingerprint remains
`google/blazer/blazer:17/CP3A.261005.005/2026112100:user/release-keys`.

Before/after SHA-256 manifests are identical for all six saved JSON/preferences files and all four
model/metadata files (Qwen and Gemma, approximately 3.85 GB combined model data). No uninstall or
data clear occurred. App launch returned `Status: ok`; no AndroidRuntime exception appeared in the
immediate filtered log. These checks establish installation/data continuity, not live CC operation.

## Owner-gated runtime checks

Normal app installation does not select an assistant or grant system owner switches/target
consent. The owner must select Ballistic as digital assistant, allow its notifications, enable
Ballistic and a harmless target such as Clock in the centralized Screen automation page, save
explicit Ballistic App rules with fingerprint, and review the system target consent.

A bounded live check should open Clock on the controlled display, observe its tree, exercise a
harmless navigation input and observe again, then test Stop and withdrawal of a target switch.
No alarm, message, payment or other externally effective action is needed. If owner interaction
is unavailable, installation can finish while these checks remain pending. Host tests and app
launch do not establish working OS sessions, physical biometric validation or revocation latency.
