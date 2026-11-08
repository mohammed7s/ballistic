# Reader-picked long-press rejection feedback — 7 November 2026

Offline candidate on `d577116fa968449ad1d4b387806a0dbc2b667307` (runtime `4de7ade`).
The parked history and numeric prototypes are excluded. No model/API, SSH/ADB, device,
emulator, credential, OS-tree or buildserver access; no push/merge. Parent owns review and
any later bounded live evaluation. Root CONTEXT/BENCHMARK documents are unchanged.

## Exact recorded evidence

Original raw records, read locally and SHA-256 checked:

- `/home/wakeel/Desktop/ballistic/ballistic_runs/core6_05ab6eb/MarkorDeleteNote/20261107-085633_MarkorDeleteNote.json`
  — `229f151035dda78704d9b8e1f5942b3bf8293c3aa8134ba73baae6d932d8f7d6`.
- `/home/wakeel/Desktop/ballistic/ballistic_runs/core7_targeted_4de7ade/MarkorDeleteNote/20261107-094542_MarkorDeleteNote.json`
  — `e2a97bcec332aa97cf6a21d8adaceed4aa3b4aa180b098450dbd3892a1cc905a`.

Both pick **e13** at steps 3 and 4. Core6 proposes `long_press e13` at steps 4 and 5.
**Core7 instead proposes `long_press e20` at step 4**, although its current pick is e13;
that rejection must remain ordinary `Unidentified`. Core7's step-5 proposal targets e13.
Both consume the existing correction opportunity by rereading, then stop at step 5.

The frozen complete raw app observations are in
`core/tests/cases/reader_authority_recorded.json`. Filtering and constructing/relocating the
actual ReaderPick establishes that e13 has no independent label or shown matched title,
is enabled/clickable/long-clickable, and passes the original picked-tap validation. Its pick
is present in each relevant planner request. The read branch reuses the same observation;
there is no intervening navigation/acquisition. “Current” means current in core's view,
not an independently established fresh physical capture. Core7's e20 has no current pick.

Fresh baseline replay equals all **19 asks per original record**, excluding only `seconds`.
The candidate differs at zero-based ask indexes **12 and 16 in Core6**, and **16 in Core7**
(original `lines` indexes 21/28 and 28). The prefix before each first difference matches;
only the final user correction message changes. Core7 ask 12 / original line 21 stays equal.
Thus this is reproduced correction feedback at the actual failure, not just a diagnosis.

## Implementation and admitted-action invariants

`actions::validate` retains the original `identified` result for LongPress. Only its
`Unidentified` rejection can become the new internal `ActionError::ReaderTapOnly`, and only
when the target is in current `reader_picks`, is long-clickable, and **the existing Tap
validator returns Ok**. The latter check retains disabled/field/slider/SystemUI/actionability
restrictions. The long-clickable guard avoids changing the diagnosis for structurally
unsupported long presses. The original rejection otherwise remains unchanged.

The change is in `map_err`: it cannot convert rejection to acceptance. Independently labeled
or matched, long-clickable controls remain allowed even when also picked. Labeled controls
without long-click support remain `NotActionable`; missing/disabled/stale/unpicked cases keep
their existing errors. Unidentified non-long-clickable rows retain `Unidentified`. No Tap,
Type, Swipe, Scroll or notification authority is added. No alternative action is dispatched
or rewritten by this feedback.

The new error maps to `target_resolution` when the second attempt is rejected. Existing
correction construction uses its Display text; no history entry is added and no private
query, raw caption, reader prose, or resource string is interpolated. The sole variable is
the code-assigned element ID. Fixed feedback for e13 is:

> Invalid action: e13 has a current reader pick authorizing tap only; long_press remains unsupported for this target. Use current permitted controls. Return a corrected JSON action.

This describes reader authority for this eligible target; it does not claim all picks can
be tapped, bypass grants/confirmation, mandate a tap, or claim task completion. The planner
chooses the next action. Two attempts per step, three consecutive distinct element reads,
caller step limits, 800/64/400 role caps, retries and eight-entry history remain unchanged.
ReaderPick construction/relocation, filter, Action schema and FFI are unchanged.

## Frozen development cases and results

Before production edits, the following files were frozen (SHA-256):

```text
ad75b4bca8673ca81a0c1da1a126ba16621d262fa9e00e19f21e41c0dc0b2777  core/tests/reader_authority_feedback.rs
a5fab0c8a89f27828c17dfa482ee9d59809fd016b61d5c05fb6abb3ce2e6e31a  core/tests/cases/reader_authority_recorded.json
91381916b239e79a124901edda2b138114f1060bd325d29f1b9b71104092ac74  core/tests/cases/reader_authority_scripts.json
d00e331338e5cd2f7c5cc766a004a40bb32a3f311a50ebb1b4dc4c40fef94e98  harness/compare_reader_authority.py
ec381128d5839ebdd6fbb81500b55c516b64252d4e7df1e9c464a35a6c0a9646  harness/test_reader_authority_feedback.py
```

Hashes remain unchanged after implementation. These are development regressions, not held-out
evaluation. Synthetic packages, IDs and text differ from the records; production code has
no task/app/record matching. Cases cover fresh/unpicked/missing/stale/renumbered picks;
changed package/resource/role/bounds/content; independently labeled and matched long presses;
non-long-clickable and non-tappable controls; disabled/field/slider/shade restrictions;
ordinary taps, swipes and scrolling; malformed/no-match reader answers; corrected visible
controls/navigation; denied grants and denied/approved guarded taps; cancellation during
either planner attempt; one-step limit, second-attempt rejection and repeated/three-read stops.

- Untouched baseline: **285 Rust/UniFFI passes; 95 Python passes**, zero failures/ignored/skips.
- Frozen new Rust tests: **3 pass / 8 fail before**, **11/11 pass after**. Failures were the
  expected specific-feedback assertions. Two new Python test methods fail before (15 failing
  subtests) and pass after. Their 24 scripted scenarios plus actual CoreHost/ModelClient
  serialization use supplied replies and mocked HTTP only.
- Final full suites: **296 Rust/UniFFI passes; 97 Python passes**, zero failures/ignored/skips.
  The initial full candidate Rust run found one old test asserting the old error enum.
  `readback_and_reader::a_pick_authorizes_a_tap_only` now asserts `Err(ReaderTapOnly)`;
  the rejection and freshness assertions remain. No safety assertion was weakened.
- Differential comparison: **24 synthetic + 2 original runs**, **248 asks**, **17 identical
  Hands dispatches**, **86 planner / 32 reader calls** per arm. Thirteen correction messages
  change; one synthetic final invalid-action report changes only its reason. All other asks,
  messages, events and final reports match (excluding wall time). No new accepted actions in
  these cases. Finite scripted equality does not establish source equivalence or model quality.

Commands, from this checkout (`cargo` is `/home/wakeel/.cargo/bin/cargo` here):

```bash
cargo test --offline --manifest-path core/Cargo.toml --features uniffi
cargo build --offline --manifest-path core/Cargo.toml --features uniffi --bin ballistic-core --lib
BALLISTIC_TEST_CORE_BIN="$PWD/core/target/debug/ballistic-core" \
BALLISTIC_CORE_BIN="$PWD/core/target/debug/ballistic-core" \
python3 -m unittest discover -s harness -p 'test_*.py' -v
python3 harness/compare_reader_authority.py \
  --baseline ballistic_runs/reader_authority_feedback/baseline-core \
  --candidate core/target/debug/ballistic-core \
  --output ballistic_runs/reader_authority_feedback/final-replay
git diff --check
```

Baseline was built/tested before any production edits and its binary retained separately.
Fresh candidate build includes the UniFFI host library. No JVM, APK or device test is claimed.
The Python suite's synthetic injection ERROR/lock messages are expected test output, not live
interactions. Logs, binary and complete per-arm replay outputs are in ignored
`ballistic_runs/reader_authority_feedback/`; the comparison script rechecks original hashes.

```text
baseline binary  2f228a6a4c70fcb92731986a6b626d5888eac9d0b353fa09fd48a22cf0ef155c
candidate binary f3e9c4ba141719ec2351ff30a19b4d6361b2208f5116064290094b8ca18cc080
candidate library f432fb9b39d247253bffa1a96dcb397f66959f75a92d57c94278b8e951986f93
```

## Prompt cost and limitations

Planner file stays **3,175 characters (+0)**. The error reason grows by exactly **10 ASCII
characters/bytes** for any same target ID; complete e13 correction **169 → 179**, e2
**168 → 178**. This is incurred only in the existing second-attempt prompt after an eligible
first rejection. The echoed planner reply and message framing are unchanged. A second eligible
rejection changes the final report's reason by 10 characters but generates no additional prompt.
History receives no new feedback. With supplied replies Core6 adds **20 total prompt
characters**, Core7 **10**; ordinary prompts add zero. No token estimate or live cost claim.

**Both full recorded replays still fail to complete:** seven planner calls, three reader calls,
one app-opening dispatch, zero long-press dispatches, and the same step-5 read-limit stop with
`done:false`, pending, unknown verification and target_resolution. Improved actual decisions
are unknown. No live call or benchmark uplift is claimed; >=13/20 remains unestablished.

Existing freshness relies on equality of package, resource ID, role, bounds and withheld
content. Host staleness, identical-content object replacement and future reappearance of the
same identity are not resolved here. Permission/confirmation may still deny a tap that passes
target validation. The new wording's effect on planner choices and task success needs parent
review and separately authorized bounded evaluation.
