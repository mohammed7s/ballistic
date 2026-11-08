# Markor accessibility caption regression

Scope: offline observation fidelity on parent `215733e`. No device, emulator, ADB, SSH,
model calls, wallet work or benchmark score change. These are development regressions,
not independent held-out tests.

## Source and grammar

AndroidWorld's [app setup](https://github.com/google-research/android_world/blob/main/android_world/env/setup_device/apps.py)
names `net.gsantner.markor_146.apk`. Markor tag v2.10.9, commit
`ab2175460680eb4a914b6006b9c962756b2067d2`, sets versionCode 146 in
[app/build.gradle](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/build.gradle).
This establishes the upstream version mapping, not the installed APK hash (no device read).

[GsFileBrowserListAdapter.java](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/java/net/gsantner/opoc/frontend/filebrowser/GsFileBrowserListAdapter.java)
builds the row description from localized kind, a space, the complete displayed title,
a space, and the description text. It sets description text asynchronously, so the
caption can end with an empty description. Titles retain extensions. Selected items
use a different prefix; virtual titles can contain additional context.
[MarkorFileBrowserFactory.java](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/java/net/gsantner/markor/frontend/filebrowser/MarkorFileBrowserFactory.java)
binds the File/Folder/Selected resources.

The narrow projection requires explicit Markor ownership and foreground MainActivity,
the exact item/root and parent list identifiers, classes, roles and ordinary row flags.
Only English `File ` / `Folder ` captions with the final space are recognized. Remove
one prefix and one terminal separator only; never search for a filename inside the
remainder or remove an extension, date, description or instruction. All remaining bytes
must pass existing full-content owner matching and length limits. A trailing separator
alone cannot prove that an optional description is empty: any such suffix remains part
of the match candidate, so it cannot expose just a shorter name. Kind is observation
metadata, never a label or an action/evidence identity. This is not a filesystem receipt.

## Expectations frozen before implementation

1. Full owner-known folder/file names appear quoted, with separate kind metadata; names
   differ from the recording. Unicode, internal spaces, case-insensitive matching and
   extensions survive. Bounds, node ids and flags remain those of the observation.
2. Unrelated/renamed names, prefixes, appended instructions, newlines with instructions,
   and forged extensions remain hidden. The entire original caption remains reader data
   with app provenance. Typed history must not promote a filename into trusted text.
3. A request containing only a filename stem never exposes the full filename.
4. Duplicate rows remain distinct; hidden distractors stay hidden and cannot be cited
   alongside a readable row as evidence. No uniqueness or completion is inferred.
5. Generic File/Folder kind grants neither tap nor long press nor readable completion
   evidence. An explicit constrained reader pick still permits only its existing tap.
6. Same resource id with wrong node/parent package, role, class, list context, foreground,
   activity or non-row flags does not gain normalized title/kind. Malformed/localized/
   selected/description-bearing captions fall back conservatively without title extraction.
7. Long titles remain complete in reader data, never truncated into a visible match.
   Changed caption, extension or bounds invalidates a reader pick on a fresh screen.
8. Actual edit-field typed values remain typed. Existing fresh-screen verification reuse,
   opaque evidence rejection, foreign-package rules and inert Tools contracts remain intact.

## Offline results

The existing suite passed at the baseline: **86 Rust tests with UniFFI**. Before editing
production code, the ten frozen tests compiled and ran: **3 passed, 7 failed**. After
the change all ten pass. A supplemental folding regression was added during safety
review (not part of that baseline count); it also passes. Final full suite:
**97 passed, 0 failed**, including the existing completion/fresh-screen, reader,
foreign-package, cancellation, generic Tools and UniFFI tests.

Command: `cargo test --manifest-path core/Cargo.toml --features uniffi`.
This environment needs `/home/wakeel/.cargo/bin/cargo` because Cargo is not on PATH.
No external Node, JSON or UniFFI schema changed. Action and verification code received
comments documenting that kind is not identity; their validation logic is unchanged.

The baseline binary was rebuilt offline from `git archive 215733e core` in a temporary
directory. Both binaries filtered every recorded observation with a filesystem row in
the three `core4_20261107/*Markor*.json` files, using each recording's original request:

| Recording | Observations / rows | Baseline visible matches | Final visible matches | Final kind metadata |
|---|---:|---:|---:|---:|
| CreateFolder | 11 / 94 | 0 | 10 | 83 |
| DeleteNote | 1 / 14 | 0 | 0 | 13 |
| CreateNote | 3 / 6 | 0 | 0 | 0 |

These are repeated observations, **not ten task successes**. Every newly visible match
is the exact requested folder title. The recorded `e10` changes from an opaque content
reference to `item [kind:folder] "folder_20261107_025452" (matches request)`. Unrelated
names stay hidden. DeleteNote's date prefixes/extensions do not match its stem-only
request; no extraction or comparison of stems was added. CreateNote's recorded button
roles are outside the supported envelope.

The synthetic tests use different names, including Unicode and spaces. They cover raw
caption preservation/provenance, full-title matching, hostile suffixes/newlines and
forged extensions, duplicate/distractor rows, invalid structural context, typed-history
isolation, long names, invisible rows, reader-pick freshness and kind-only rejection
by actions and completion checks. A folded caption remains content even when its
destination would ordinarily treat a label as trusted.

## Limits

Only the inspected English caption shape in Markor's main browser is supported. Other
locales, selected rows, other activities/roles, missing ownership, ambiguous boundary
whitespace, navigation pseudo-items and changed formatting get no normalized title.
Descriptions are never removed from a match candidate; this can hide a legitimate
name when a caption includes a date/path. No app version is available in ScreenState,
so identical-looking future formats cannot be distinguished by version. No installed
APK hash, live behavior or independent variants were tested here.

The kind is an app observation, not verified filesystem type. Duplicate names are not
disambiguated. A readable title only makes the existing model assessment possible;
it does not prove creation, persistence or complete postcondition coverage. The
measured task score remains **4/20 combined, 9/20 independent state** until a new live
evaluation. Parent-owned live variants remain the next validation step.
