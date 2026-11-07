# Bounded element-reader correction — 7 November 2026

Offline candidate based on `e307e01` (core code `05ab6eb`). Production changes are confined to
planner validation and the element-read no-progress check in `core/src/agent.rs`. Done/verifier
instructions, action validation, reader selection/provenance and summary code are untouched.
No SSH, ADB, device, provider/model call, credential, OS tree or buildserver access was used.
No live score or unchanged actual model-call count is claimed.

## Finalized evidence and scope

Read the finalized report, per-task TSV and exact prompt/reply/action records under
`/home/wakeel/Desktop/ballistic/ballistic_runs/core6_05ab6eb/` (read-only). The complete Core6
result remains 8 PASS / 10 FAIL / 2 ERROR, independent state 11/20, zero false-Done acceptance.
The >=13/20 goal remains unmet; these development tests do not change those results.

- `SystemBrightnessMax/20261107-084320_SystemBrightnessMax.json`, SHA-256
  `dbe4d682372d5dc77fdb3a8d8f0d6f3db8e8c3e49d79fa8e2c99f421e14e8122`.
  Step 2 reads c5,c6 with “which button opens display settings?”, getting none. Step 3 reads
  c1–c7 with “which button is display settings?”, also none. Step 4 repeats that exact second
  query on its first planner attempt. The prompt advertises scrollable e1/e5; no scroll occurs.
  This is an unused correction opportunity before the existing stop, not slider-value evidence.
- `FilesDeleteFile/20261107-090341_FilesDeleteFile.json`, SHA-256
  `806a60d33ed0be955d7c50fdcb393a14e756581b30426713b961335eb56bc754`.
  A step-2 scroll precedes a read of c2–c5 seeking the storage row. The reader sees only the
  four category candidates and returns none. Step 4 repeats the query on its first attempt.
  The correction can let the planner choose legal navigation; no successful choice is inferred.
- `MarkorDeleteNote/20261107-085633_MarkorDeleteNote.json`, SHA-256
  `229f151035dda78704d9b8e1f5942b3bf8293c3aa8134ba73baae6d932d8f7d6`.
  Steps 3 and 4 select e13. At step 5, the first planner reply is `long_press e13`, which is
  rejected: an element-reader pick permits a plain tap, not long press. The second reply asks
  to read c6 after three element reads. **The correction slot is already used. This candidate
  must still stop on this recorded path.** It cannot fix this terminal case without another
  behavioral change; it does not add an attempt or relax the pick policy. The synthetic exhausted
  read → current-pick tap test covers the distinct case where the first attempt is a read.

No trace text, goal, package, app-specific control or benchmark marker is used as a production
branch or copied into the synthetic fixtures.

## Implementation and limits

The existing `for attempt in 0..2` loop first parses and resolves the action normally. On its
first attempt only, an element read whose private serialized query is already present, or whose
consecutive read count is at least three, receives this fixed user message:

> Invalid action: this element read repeats an unchanged query or exceeds the consecutive-read limit. No reader call was made. Use current labels or a current reader pick for a legal action, or navigate or scroll an advertised scrollable area to obtain new evidence. Do not guess hidden controls. Return a corrected JSON action.

The planner's reply is echoed as an assistant message just as for existing validation errors.
Neither the private query, source text nor reader reply is added to feedback. The correction
is parsed/resolved normally and remains subject to grants, confirmations, guarded-input limits,
readback and verification. Core selects no action, navigation or scroll on the planner's behalf.

The same helper constructs both validation and dispatch queries, preserving exactly:
`split_whitespace`, joining with one ASCII space, Unicode lowercase, numeric sort/dedup of cN
targets, the existing reader message builder, and equality of the entire serialized messages.
No lossy hash, question-only equality or semantic similarity replaces this comparison.
Validation changes no read counter, pick or observation. The original element branch still
checks the same guard before dispatch and preserves its terminal event/report: done=false,
pending, target_resolution, with the same fixed no-progress reason. An invalid second action
still stops through normal invalid-action reporting. A different legal read below the cap is
still allowed; the original cap/reset behavior is preserved.

Unchanged hard limits: two planner attempts per step, three distinct consecutive element reads,
caller step budget, 800/64/400 planner/reader/verifier output tokens, host retry policies and
history size. Non-read actions retain the existing query reset and observation path. Cancellation
still gates the next model call and dispatch. Facts and terminal owner summaries are not checked
by this element-only correction; no summary continuation is introduced.

Planner file: **3,175 → 3,175 Unicode characters (+0)**. The new fixed correction has **326 ASCII
characters/bytes** plus the echoed first planner response and protocol framing in that one
correction prompt. It does not add a new history entry or enlarge other prompts. This may use a
previously unused planner correction slot, so actual calls/tokens/latency may increase and later
behavior may change. Provider token cost and live uplift are unknown. The three-call element
reader cap is not a cap on all model calls or terminal summary calls.

## Frozen development checks

Before production edits, twelve synthetic tests were written in
`core/tests/reader_no_progress_correction.rs`; frozen SHA-256:
`f8cc4fe90cb15714cc0dc46ae920d65e1fbe90741410bcce2c9740fbb679005a`.
Before the final freeze, test setup was corrected to use the bridge's string `denied` response
and to avoid accidentally using a guarded word in the non-confirmation source fixture.
The corrected baseline on `e307e01` was **3 pass / 9 fail**; after implementation **12/12 pass**
with the frozen file unchanged. These are scripted development regressions, not held-out tests
or evidence that Gemini chooses the scripted actions.

Coverage: identical/case/ASCII and Unicode whitespace queries, target order and dedup; corrected
visible tap, scroll, back and a current source-cited pick; repeated/invalid corrections with no
dispatch or third planner attempt; three-reader cap with a distinct fourth/fifth query; cap
correction to a current pick; already-used correction slot; grants, disabled targets and denied
confirmation; reader-prose isolation; one-step budget; fresh observation after navigation;
summary terminal behavior after element exhaustion; cancellation during the first or second
planner call of the correcting step.

Three existing guard test functions were updated only to supply the newly expected second
planner reply and assert the new planner count. All reader-call bounds, no-dispatch, observation,
terminal pending/reason and done=false assertions remain. No old safety assertion was weakened.

One additional Python integration test, with three scenarios, launches the fresh real core
binary through `CoreHost.serve` and the actual `ModelClient` request serialization. Scripted HTTP
responses exercise repeat-stop, legal navigation, and rejected hidden tap; it checks exactly four
calls (planner/reader/planner/planner), zero transport retries, caps 800/64/800/800, one observation,
no raw source in protected prompts and zero unauthorized dispatches. HTTP is mocked; no network
or device is accessed.

Validation on this candidate:

- `cargo test --offline --manifest-path core/Cargo.toml --features uniffi`: **282 pass**, zero
  failures/ignored (270 existing + 12 new).
- `cargo build --offline --manifest-path core/Cargo.toml --features uniffi --bin ballistic-core`:
  fresh local debug binary/library built. Binary SHA-256:
  `dfdfe743b04cbf4d003a3c8b3fa44554dc7aa503cfd85b815acebd117dba3b6c`.
- `python3 -m unittest discover -p 'test_*.py' -v` in `harness/`, with both
  `BALLISTIC_TEST_CORE_BIN` and `BALLISTIC_CORE_BIN` pointing to this checkout's fresh binary:
  **95 pass**, zero failures/skips (94 existing + 1 new). The suite prints synthetic injection
  error/lock messages as expected test output; these are not live runs or device interactions.
- `git diff --check`: pass. No FFI/API change; no JVM run, binding regeneration or APK build.

No live run was performed. Parent-reported 51 JVM passes belong to the base and are not counted
as validation of this candidate. The baseline benchmark result and unfulfilled target remain
unchanged; usefulness, actual cost and safety under a live planner require separate measurement.
