# Element-reader candidate scope and navigation feedback — 7 November 2026

Offline candidate on parent `0751a68`, which already includes terminal owner excerpts.
Production changes are confined to the element-read branch's fixed no-match feedback and one
planner paragraph. No device, emulator, SSH, model/provider call, key, buildserver or OS tree
access was used. No raw private trace content is committed.

## Finalized source evidence

Read the exact local finalized JSONs under
`/home/wakeel/Desktop/ballistic/ballistic_runs/core5_7a9238e/`, including the recorded planner
system/user prompts, requested reader candidate sets and actual model replies. These are the
frozen `7a9238e` run, not the candidate below or the parent's later transport change.

- `SystemBluetoothTurnOn/20261107-053018_SystemBluetoothTurnOn.json`, 63.3 s.
  SHA-256 `83baec197033aaf0cb5043faee5eba7dc4c88b3bdc9681cea47e5c199c5b01d3`.
  Step 5 taps into Connection preferences. At step 6 the planner prompt already advertises
  actionable labeled `e4 Bluetooth`, but the planner requests only hidden `c5,c6,c7` while
  looking for a switch/toggle. Reader request id 16 contains only the injected Chromebook,
  Nearby Share and Android Auto tiles; its reply is `none`. Step 7 scrolls `e1` down, with an
  unchanged-screen history result. Step 8 reads the same unrelated candidates (request id 21,
  `none` again); step 9 repeats it and stops before another reader call. Failure is navigation/
  planning: a visible labeled navigation button was ignored while searching for a deeper
  control in unrelated hidden rows. The trace is not a claim that no scroll was attempted.
- `SystemBrightnessMax/20261107-053101_SystemBrightnessMax.json`, 18.7 s.
  SHA-256 `5ec89f3cabf1fe54a8e520a3d85c10fda51643272e13d88f6212e0cb3f38d08e`.
  Step 1 opens Settings. Step 2 requests `c1` through `c7`; reader request id 6 contains only
  homepage rows from Network & internet through Sound & vibration, with no Display row.
  Reply: `none`. At step 3 the planner repeats the identical read and stops. Both homepage
  scrollable containers were advertised, but no scroll was proposed. This is a navigation/
  planning failure before reaching Display or a slider. The earlier Core 4 slider-range
  observation gap does **not** explain this finalized failure.

Both finish pending, done=false, verification unknown, failure category target_resolution,
with `reader made no progress on unchanged screen; stopped`. Bluetooth's intervening scroll
resets the consecutive-read budget under existing code; the immediately repeated step-9 read
still stops. Neither the guard nor that reset behavior changes here. Timer digits are separate
and out of scope.

Parent subsequently reported Core 5 stopped on its first 429 at CalendarAdd after 11 attempted
(4 pass, 5 fail, 2 error; 9 not run), with the emulator released. This is parent-reported run
status, not a new investigation or a score attributed to this patch. Further live validation
requires separate authorization after the quota issue is resolved.

## Small fixed change

Old feedback, exactly: `reader: element none`.

New feedback, exactly:

> reader: element none; no match among requested candidates only. This does not establish absence elsewhere. Inspect current labels; use relevant visible navigation or scroll an advertised scrollable area before reading new candidates. Do not repeat the same read or guess hidden controls.

This is a code-owned constant, never generated from reader prose. The planner paragraph now
explains that visible labeled controls can navigate deeper without a reader pick, and that
`none` describes only the requested hidden candidates. It retains one generic action-schema
example, without app/task names, benchmark markers or task-specific workflows.

Unchanged: valid cited IDs and their source tags; malformed/noncandidate rejection and size-only
logging; propagated element-reader port errors; ordinary validation, grants and confirmation;
known-control profiles; summary/terminal behavior; completion semantics; no-progress guard;
800 planner / 64 reader / 400 verifier output-token limits; step limit, call caps and retry
policies. The existing defensive failure-to-construct-pick fallback keeps its no-match status.
No synthetic pick, automatic navigation/scroll, new authority or absence-based completion is
introduced. The reader's answer can still be wrong; candidate scope is not proof of absence.

Measured Unicode character counts (including final newline for planner.txt):

- Planner instructions: 2,945 → 3,175, **+230 characters** against `0751a68`.
- Fixed no-match feedback: 20 → 287, **+267 characters** per retained history entry.

Provider token cost is unmeasured. History may retain multiple such entries under its existing
limit; the fixed feedback is not added to successful picks or rejected replies.

## Offline checks and limits

`core/tests/element_reader_feedback.rs` exercises `agent::run` → the real `drive` path through
the JSON-lines Bridge with synthetic observations and scripted completions. Seven tests were
written and run before the production edit. Initial test-authoring corrections fixed an
incorrect expectation of `swipe` for the host's `scroll` operation and excluded the system
instruction's literal `none` example when asserting no runtime no-match feedback after rejection.
After those corrections, the pre-implementation baseline was **3 pass / 4 fail**. All four
failures were the missing expanded fixed feedback; the scripted legal navigation/scroll,
validation, isolation and guard checks preceding those assertions already passed.

After implementation, those seven passed. One additional test checks the existing completion
boundary: a later `done` still requires a fresh observation and verifier call, neither unknown
nor hidden-node-only evidence completes, and raw content stays out of verifier prompts.

Coverage includes:

- Two unrelated app/label variants: hidden-candidate `none`, then an explicit planner tap on a
  visible labeled navigation control outside the reader candidates.
- Explicit scroll on an advertised list, fresh content, a fresh reader call, then a valid
  source-cited pick and ordinary tap; no host-chosen action.
- Noncandidate visible and nonexistent IDs, empty replies, prose after `none`, multiline
  directives and JSON-shaped replies: rejected, never converted to `none` or synthetic picks.
  A guessed hidden tap fails before a corrected visible-label tap; no reader prose enters the
  correction prompt. Rejection events contain only the fixed status and character count.
- `none` followed by hidden, disabled or nonactionable target guesses: normal validation still
  rejects both planner attempts, with no Hands dispatch.
- Whitespace/case-equivalent repeated query: one reader call then unchanged-query stop.
  Distinct paraphrases: three reader calls then stop. One-step limit does not extend or act.
  The existing navigation-reset tests also remain passing.
- Failed reader port call remains an error with no later planner or synthetic no-match event.
  Protected planner/correction/verifier messages and events exclude hostile source content;
  output-token caps remain 800/64/400.

Final checks, on this branch:

- `cargo test --offline --manifest-path core/Cargo.toml --features uniffi`:
  **214 passed, zero failed/ignored** (206 existing + 8 new).
- `cargo build --offline --manifest-path core/Cargo.toml --features uniffi --bin ballistic-core`:
  fresh local debug binary built successfully.
- In `harness/`, `python3 -m unittest discover -p 'test_*.py' -v`, with both
  `BALLISTIC_TEST_CORE_BIN` and `BALLISTIC_CORE_BIN` set to this checkout's freshly built
  `core/target/debug/ballistic-core`: **88 passed, zero failures/skips**.
- `git diff --check`: passed. No interface changes, so no JVM run or binding regeneration.

The parent's separate transport integration `a26f7c0` / docs `46ec687` reports 94 Python and
51 JVM tests on its 206-test Rust baseline. Those changes are not in this branch; the 88 Python
passes here must not be reported as validating that later integrated tree.

These scripted tests establish feedback delivery, existing legal follow-up paths, isolation,
validation and unchanged limits. They do **not** demonstrate Gemini planning uplift, live task
success, a live injection pass, or a changed benchmark score. There has been no live validation
of this patch, and none is authorized while quota remains unresolved.
