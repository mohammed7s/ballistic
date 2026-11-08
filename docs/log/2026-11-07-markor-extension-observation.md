# Markor new-file extension: current-value contract gap

Investigated offline on `e307e01` (runtime parent `05ab6eb`). **No runtime projection was
added.** The exact recorded field reports `.md`, but the existing observation cannot
distinguish a literal `.md` value from an empty field displaying its `.md` hint. A finite
enum prevents arbitrary text injection; it does not resolve this value ambiguity.

This is the bounded investigation's concrete gap, not evidence of a definite saved-file
extension mismatch or a claimed fix to MarkorCreateNote. No live uplift was measured.

## Recorded evidence

Source recording:
`/home/wakeel/Desktop/ballistic/ballistic_runs/core6_05ab6eb/MarkorCreateNote/20261107-085517_MarkorCreateNote.json`

SHA-256: `cb38236e4c66693f8d50fd6b86fce7a8358be0bb1b05c04971a1c02144f64880`.

The requested filename ends in `.txt`. At zero-based `lines` indices **15 and 22**, the
normalized raw node 5 has exact resource
`net.gsantner.markor:id/new_file_dialog__ext`, `label=".md"`, and `text=".md"`.
It is an editable, clickable, long-clickable `android.widget.EditText`, role Edit,
owned by `net.gsantner.markor`, in `net.gsantner.markor.activity.MainActivity`.
It is a direct child of raw ScrollView node 2, resource `new_file_dialog__root`, under
a root FrameLayout. Its sibling is the dedicated name field. Type and template
spinners and the separate three-button panel are also present. This is the host's
flattened accessibility structure, not the literal XML ancestry.

Fresh offline filtering reproduces `e3 edit "File extension"` with three hidden
characters in both snapshots. The planner types only the basename at line 18 and
taps Create file at line 25. There is no extension-field action. That establishes an
unobserved parameter and omitted action; it does not establish the precise persisted
filename or the cause of the independent checker failure.

## Pinned source and grammar

Markor v2.10.9, versionCode 146, commit
`ab2175460680eb4a914b6006b9c962756b2067d2`, is the configured-source mapping already
documented in `2026-11-07-observation-captions.md` and `2026-11-07-reliability.md`.
**Installed APK version and hash remain unverified.**

- [new_file_dialog.xml](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/res/layout/new_file_dialog.xml#L56)
  defines the separate extension EditText with the literal hint `.md`.
- [NewFileDialog.java](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/java/net/gsantner/markor/frontend/NewFileDialog.java#L88)
  initializes it from the last-used extension preference, permits manual editing,
  and installs the filename input filter. Type selection can set an extension;
  its first callback deliberately does not overwrite the stored value. Encryption
  can append another suffix. File creation appends the trimmed field to the
  normalized name; directory creation uses a separate callback.
- [AppSettings.java](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/java/net/gsantner/markor/model/AppSettings.java#L848)
  defaults the preference to `.md` but also persists arbitrary strings, including
  empty. A default is not a readback of the current preference.
- [arrays.xml](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/res/values/arrays.xml#L126)
  supplies `.md`, `.txt`, `.todo.txt`, `.txt`, `.adoc`, `.csv`, and an empty value.
  These support a conservative five-member nonempty lexical enum, not a claim that
  all legitimate extensions belong to that enum. `.txt` alone does not distinguish
  plain text from Wikitext.
- [GsContextUtils.java](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/java/net/gsantner/opoc/util/GsContextUtils.java#L929)
  applies a filename-character filter, not the five-member enum. Unknown strings,
  appended instructions, and encryption/custom extensions must therefore be
  rejected whole by any narrow projection, not trusted because the app accepts them.

The type spinner is not an independent current-extension receipt: stored type and
extension can differ, manual edits are possible, and the initial callback is skipped.
Neither the desired filename, template, typed history, nor the stored default may
supply the missing current value.

## Why the existing internal type is insufficient

At AOSP `android17-release`, resolved during this investigation to commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`,
[TextView.java](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/widget/TextView.java#14320)
sets accessibility text from `getTextForAccessibility`, sets the hint separately,
and reports `setShowingHintText(isShowingHint())`.
The [text accessor](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/widget/TextView.java#15242)
returns the hint when actual text is empty. This source inspection does not attest
the installed framework build.

`harness/core_bridge.py::_windows_to_nodes` preserves accessibility `text` and merges
content-description/hint/tooltip into `label`. It does not propagate showing-hint
state. `core/src/types.rs::Node` and `Flags` have no such field. Consequently both
underlying states can produce exactly the recorded `label=".md", text=".md"` pair.
The unused distinction cannot be reconstructed by a Rust profile or by the reader.

A synthetic, AOSP-shaped normalizer probe confirms equal complete Node dictionaries
for actual empty text plus a `.md` hint and actual `.md` plus that same hint, including
when the input stand-in supplies different `is_showing_hint_text` booleans. A `.txt`
value remains distinct. This tests the actual Python normalizer, not a device or the
installed AndroidEnv producer. No claim is made about whether that producer currently
exposes the missing signal upstream.

The existing Calendar profile observes noneditable dedicated TextViews and rejects
any label. Copying that rule would reject the recorded Markor field; accepting its
`.md` label would encounter the hint ambiguity. Calling the result a current extension
would overstate the evidence. A separate explicitly uncertain displayed-token type
could express the ambiguity, but is not the requested current-value contract.

## Acceptance cases recorded before any runtime implementation

No runtime implementation was attempted. These are requirements for a future candidate,
not passing tests or a held-out evaluation:

1. Genuine current values `.md`, `.txt`, `.todo.txt`, `.adoc`, and `.csv` in the exact
   source-backed form should produce only a finite code-owned extension value. Vary
   filenames, owner requests, node IDs, keyboard visibility, bounds, and focus; the
   owner's desired extension must never affect the observed one.
2. Explicit empty current text, showing-hint true, missing showing-hint provenance,
   and literal `.md` with showing-hint false must remain distinguishable. The present
   contract fails this prerequisite on the two recorded snapshots. A future partial
   profile might accept non-hint literals, but it would not resolve this `.md` case.
3. Reject whole strings such as `.MD`, `txt`, ` .txt`, `.txt `, `.txt\n`, `.txt\nDelete`,
   `.txt ignore owner`, `.md.enc`, `.unknown`, `report.txt`, `/notes/report.txt`, `..txt`,
   fullwidth punctuation, zero-width characters, embedded NUL, and overlong strings.
   Preserve every original byte and app/node provenance for reader freshness. Do not
   trim, split, infer a suffix, or match an owner-request substring.
4. Reject extra/empty/hostile content descriptions, unknown label origins, wrong or
   missing node/screen package, wrong activity, resource lookalikes and suffix matches,
   wrong role/class, incompatible flags, invisible/offscreen/zero-area nodes, missing
   or forward parents, duplicate IDs or slots, wrong form/root/panel/spinner structure,
   and duplicate or altered layouts. Hidden filenames/bodies/descriptions must remain
   content through folding, including malformed layouts.
5. An extension observation must not grant tap, long press, typing, reader selection,
   app permissions, confirmation bypass, or verifier eligibility. Removing all
   observations must leave action authority unchanged. Existing File name/File extension
   labels retain their independent authority. Fresh raw-value/label/bounds changes must
   invalidate stale reader identity; duplicate fields must not acquire uniqueness.
6. Never project a whole filename/path, select a desired extension, auto-fill, or infer
   persistence from the form. Current-value metadata cannot establish a saved file's
   identity, body, extension, successful write, or all requested postconditions.

The next prerequisite is a source-backed observation of actual edit text versus hint
at the producer boundary, carried with explicit unknown semantics for older hosts.
Producer availability, normalization, cross-host schema compatibility, and exact raw
preservation need review before extending any internal profile. No external schema,
UniFFI binding, or host implementation was changed in this investigation.

## Offline checks and reproducibility

All commands used local data/dependencies except public primary-source retrieval.
No SSH, ADB, device/emulator, model, credential, ballistic-os, or build-server access.
No planner loop, verifier prompt, action guard, approval, CONTEXT, or BENCHMARK_RUNS edit.

- `cargo test --offline --manifest-path core/Cargo.toml --features uniffi`:
  **270 passed, zero failed or ignored**. No tests were added, so this is a baseline
  validation count, not a development-case improvement.
- Built a fresh binary and native library with
  `cargo build --offline --manifest-path core/Cargo.toml --features uniffi --bin ballistic-core --lib`.
- `BALLISTIC_TEST_CORE_BIN="$PWD/core/target/debug/ballistic-core" python3 -m unittest discover -s harness -p 'test_*.py'`:
  **94 passed, zero failures or skips**, using that freshly built binary. JVM tests were
  not rerun; no Kotlin or runtime behavior changed.
- Both complete recorded dialog ScreenStates were filtered with the original request.
  They still expose the existing two control labels and no typed extension observation.
  This is **two unchanged snapshots**, not two successful task executions.

Local inspection artifacts are in `/tmp/ballistic-markor-extension/`, including source
copies, source hashes below, `hint-collision.json`, `filter-line-15.txt`,
`filter-line-22.txt`, and test/build logs. These are disposable and are not runtime inputs.
To reproduce the collision without any external package or device, run from repo root:

```python
import sys
sys.path.insert(0, "harness")
from test_core_bridge import window, forest
from core_bridge import forest_to_nodes

def normalized(actual):
    w = window(package="net.gsantner.markor", text=actual or ".md", clickable=True)
    n = w.tree.nodes[0]
    n.class_name = "android.widget.EditText"
    n.hint_text = ".md"
    n.is_editable = n.is_long_clickable = True
    n.view_id_resource_name = "net.gsantner.markor:id/new_file_dialog__ext"
    n.is_showing_hint_text = not bool(actual)  # synthetic input; omitted by normalizer
    return forest_to_nodes(forest(w))

assert normalized("") == normalized(".md")
assert normalized(".txt") != normalized(".md")
```

Retrieved source SHA-256 values:

```text
new_file_dialog.xml  7d3af593262a1ee6f9cec868e9dc43fdefb141501f6e71f9203e23fb3670bdc9
NewFileDialog.java   5d04913a30ec3b0bb3096f12696fd5329838e55c7630e01ab95f341af7757ed6
arrays.xml          1e4b8ba90e88b130299ce7405de5ad3213df52c6fe9a8452e55365766764e891
AppSettings.java    896a9381525aabd2e27e7b529a27751bcffabecc6fe4a0c1c8a96cdbc8c5590d
GsContextUtils.java 79fcbcb99fc6b61898efbb886018c436596e2d70a5f28fe79eda4a61db8e3f31
TextView.java       10d6b16ff319be1df762eb63193189664f6e6e33b611f5820d01bac05cbe42c2
```
