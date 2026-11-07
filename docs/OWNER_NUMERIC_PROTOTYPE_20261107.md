# Owner numeric advisory prototype — 7 November 2026

Parent disposition (7 November 2026): **parked, runtime not integrated**. Freeze
`f492d376a103c5688ac5c2dd1189c111fdc8cd6c` and implementation
`0912d6bb22663790d1040d6b8455944aa923eec0` remain on isolated branch
`t3/owner-numeric-constraints`, worktree `/home/wakeel/.t3/worktrees/ballistic/t3-40ac4c52`.
The source/tests and reproduction commands below refer to that checkout, not master.
Parent verified all 364 indexed artifact hashes and reran the 57 CLI cases plus both
original recorded traces: exact results reproduced, with no change to either failing
original request. Parent reproduction is ignored `ballistic_runs/owner_numeric_parent_review/`.
This is insufficient coverage to justify integration or live testing for the current
benchmark objective. The report is retained to avoid repeating the same experiment.
No full Rust/Python rerun by parent is claimed; those suite counts below are child evidence.

## Design decision before implementation

Parent `3895794` (runtime `4de7ade`), isolated offline worktree. No history candidate
code is included. This document and `core/tests/cases/owner_numeric.json` are frozen
in a separate commit before implementation. These are **development cases**, not
held-out data or evidence of model quality. No live evaluation is authorized here.

The exact Core6 records were read from the parent's local artifacts:

- Timer SHA-256 `e5482714e08208be6b4a29d1712ded111b41ad035f7c3ed40aea6f07e5a9f148`:
  step 5 sees twelve seconds and chooses digit 3; steps 6–8 backspace; step 10
  repeats digit 1 while one second is visible. State failed, no accepted Done.
- Calendar SHA-256 `d0e77f6fc50a03f49ec3b912ebefcb8b606293b55e6f93d94b2155365df5ff9f`:
  owner requests 2023-10-27 at 13h for 15 mins. Step 13 selects 15 in the start
  minute picker as if it were duration. Step 15 shows start/end both 13:15;
  later replies derive 13:30 from the wrong observed start. State failed, no Done.

The planning audit's numeric hypothesis is distinct from its parked history
hypothesis. The implementation will preserve owner quantities, without consuming
any screen, reader response, model proposal, history, resource ID or benchmark
metadata. Only a complete bounded grammar will establish roles. Substring
matching, deleting quoted passages, and arbitrary digit extraction would promote
message/title/page content or lose conflicting instructions; they are rejected.

The existing request is one unstructured string. There is no authenticated typed
intent field distinct from quoted payload. A safe small prototype can accept a
controlled subset of that string; broad natural-language role extraction is not
justified. Both original Core6 requests deliberately remain **unknown**: the
calendar has app selection, date, quoted title/description and nonstandard time;
the timer uses a different verb and compound duration. No test or projection may
rewrite those recorded requests and report them as supported originals.

## Frozen supported contract

Case-insensitive ASCII, at most 192 request bytes, optional surrounding ASCII
spaces and optional `please ` prefix. No controls, Unicode normalization, quotes,
free-text slots or added clauses. Consume the full request.

1. `schedule an event at HH:MM for N minutes[.]`
   Two-digit 24-hour hour/minute, canonical integer N (0–1440, no leading zeros
   except zero itself). Keep desired start and duration separately. Derive only
   nominal same-day **wall-clock** end by checked minute addition when result is
   strictly below 24:00. Reject rollover entirely; no partial constraint record.
   Date, timezone, actual elapsed duration across clock changes, target binding
   and persistence remain unknown. Zero duration is a valid arithmetic constraint,
   not evidence that an app supports such an event. No instant/date is inferred.
2. `set a timer for N UNIT[.]` or
   `set a timer for N UNIT. do not start it.`
   UNIT is `second(s)`, `minute(s)` or `hour(s)`, singular iff N=1. Canonical
   integer, at most six digits; checked conversion, total 0–604800 seconds
   (seven-day prototype arithmetic bound, not a widget limit). Keep no-start
   explicit; without the suffix, running state is **unspecified**, never inferred.
   No digit sequence, cursor, predicted display, Start action or keypad contract.

No app is selected by the advisory. It cannot make an ambiguous task executable.
Unsupported syntax adds no prompt text and retains existing planning behavior;
`unknown` means this module cannot derive constraints, not that core stops the task.

## Intended integration and tradeoffs

A private pure module returns a bounded immutable advisory from the original
request once per run. Inputs retain exact UTF-8 byte spans (ASCII in this grammar).
The planner alone receives formatted validated numbers, role/unit enums and
code-owned caveats. Derived end references desired start and duration, never UI
start. Nothing consumes the advisory as authorization or completion evidence.
No changed action schema, FFI, grants, confirmation, target resolution, Done
verifier, output caps, step limits, correction slots, retries or extra model calls.

This tests representation/plumbing, not broad language understanding or the original
recorded numeric failures. A richer future alternative is an owner-confirmed typed
intent interface with roles/units/date/timezone and explicit data fields, validated
before entering core; a model-authored span alone cannot certify an instruction.
That interface and timer control semantics are outside this prototype.

Prompt overhead must be measured per supported request and across original recorded
prompts. No tokenizer/model is available offline: report bytes/characters, not guessed
tokens. Repetition each planner attempt can increase prefill cost, and altered model
choices could consume existing correction slots. Neither latency nor quality is known.

## Validation plan frozen before code

Run fresh baseline and final Rust/UniFFI tests and Python harness tests with built
binaries. Evaluate every frozen case before/after through actual planner prompt
assembly. Test changing UI values cannot move the owner anchor, hostile app/reader
text cannot create intent, and arithmetic is never passed to the verifier as evidence.
Check ordinary denied grants, confirmation and unsupported target behavior remain.
Replay both original records with their supplied replies, comparing baseline/final
port asks/logs/reports modulo timing and retaining exact prompt artifacts. This is
offline transport testing, not new model decisions, execution or a task score.

Results and artifact inventory will be appended after implementation.

## Results

Freeze commit: `f492d37` (before runtime edits). The 58-case fixture is unchanged,
SHA-256 `3ecb905dfb4cbfbec7621b2e6d33ea890fda01b3cc26ef7888d6e2f173ef3a80`.
The pre-implementation version of this report hashes to
`8a27de6bedb8aa844819b49200e6b8010eb3c7e17e31aa8a3cce959d26363c0b`.
Sixteen expected supported cases and 42 expected unknown cases were frozen. No
cases were relabelled or added to that fixture after implementation.

Implemented `core/src/owner_numeric.rs`, private to core, and a four-line
initialization plus planner-state insertion in `agent::drive`. All runtime
callers of the module are in that initialization. Values cannot be changed by
subsequent observations or responses. The input size check precedes allocation;
case folding handles at most 192 ASCII bytes. Parsing and output are bounded by
this input cap and fixed numeric ranges; there are no dynamic content fields or
regex backtracking. No dependencies were added. The advisory contains exact
original-request byte spans; its fixed-format output is below the tested
640-byte ceiling. This is not a global allocation/performance measurement.

Examples from the **actual captured candidate planner prompts**:

```text
Owner request: Schedule an event at 07:05 for 40 minutes.
Owner numeric intent (advisory; immutable; source=owner:request; spans=byte[start,end)):
desired_start=07:05 @21..26; desired_duration_minutes=40 @31..41; derived_same_day_wall_clock_end=07:45 (desired_start + desired_duration).
Duration is not a start-minute. Never re-anchor end to observed UI start. Date, timezone and elapsed-time interpretation unknown; UI binding and completion unproven.
```

```text
Owner request: Set a timer for 7 minutes. Do not start it.
Owner numeric intent (advisory; immutable; source=owner:request; spans=byte[start,end)):
desired_duration_seconds=420 @16..25; running=must_not_start @27..43.
Keypad semantics and input sequence unknown; no inferred digit buffer or actions. UI binding and completion unproven.
```

The no-start marker is **advisory**, not a new dispatch veto. The ordinary planner
can still choose a wrong field, time, app or Start control. This implementation
does not claim to prevent those errors. A nominal wall-clock sum is not a resolved
end instant: dates, timezone/DST effects and actual elapsed time remain unknown.
The event grammar literally requires `minutes`, including N=1; `1 minute`, explicit
end-only requests, title/description fields and compound durations are unsupported.
Unknown behavior is absence of an advisory, not permission, a rejection of the
whole request, or proof that the ordinary model will act safely.

### Before/after development checks

Fresh baseline: **285 Rust/UniFFI tests, 95 Python tests**, zero failures/ignored/
skips in the completed gates. Fresh candidate: **294 Rust/UniFFI, 95 Python**, zero
failures/ignored/skips. Rust checks include the 58 exact fixture inputs through
both the pure recognizer and real planner assembly; all 58 meet the frozen
expectations. Additional tests exercise 11,520 start/duration combinations, the
192/193-byte boundary, million-byte rejection, wrapped/quoted/negated clauses,
changing typed UI values, hostile app and rejected reader text, independent Done
verification, absent grants, denied confirmation and existing correction limits.

`core/tests/compare_owner_numeric.py` runs both binaries using supplied stdin
responses, empty subprocess environments and a bounded timeout. On **57** CLI-
representable fixture requests, exact advisory-contract matches are **41/57
baseline → 57/57 candidate**. The 16 new matches are the supported forms; all 41
CLI unknowns keep identical prompts. These are development contract checks, not
model decision accuracy or task scores. The 58th request contains NUL, which Unix
argv cannot represent; it is tested without modification by the in-process Rust
drive test. It is not silently removed, escaped into a different request or counted
as a successful CLI comparison.

The actual changing-screen test exposes start 07:45 and then 08:00 while owner
start stays 07:05, duration stays 40 and derived end stays 07:45. A scripted Done
reply with empty citations remains unknown despite matching displayed arithmetic;
the fresh verifier prompt has no numeric advisory. App/reader text cannot create
one, and unknown targets still get only the existing two planner attempts.

### Exact original-record replay and cost

Both original requests are unchanged and unsupported. Timer's **41 protocol asks /
10 planner calls** and Calendar's **137 asks / 34 planner calls** compare equal to
the original recordings and between baseline/candidate, ignoring only log `seconds`.
All prompt messages, supplied action dispatch requests, caps, logs and terminal
reports match. Both terminal reports still exhaust the step budget with `done=false`,
verification `unknown`. Their `execution_status=executed` comes from supplied recorded
acknowledgments, not new device actions. No checker was run and no real inputs were sent.

Across the 16 supported examples, added planner context is **264–397 ASCII
characters/bytes per call**, mean **332.5**, total **5,320** across one call per
example. Both original recorded runs and all unknown fixture forms add **zero**.
The comparison also asserts that removing the sole new advisory restores the
baseline synthetic protocol, including system prompt, caps, report and call count.
Corrections retain the same advisory and can repeat its prefill cost. Actual model
tokens, latency, correction frequency, phone CPU/allocation and outcome changes
remain unmeasured. The unchanged caps are planner 800 / reader 64 / verifier 400;
no step, retry, output or call-budget increases were introduced.

### Reproduction and artifacts

Ignored artifact root in this assigned worktree:
`ballistic_runs/owner_numeric_20261107/`. Canonical evidence is `final-comparison/`:
baseline/candidate complete protocol captures for 57 requests and both originals,
plus `summary.json`. `baseline-{rust,build,python-with-binary}.log` and
`final-{rust,build,python}.log` are the completed gates. Preserved intermediate
logs include an initial wrong cargo PATH, three skipped Python binary tests before
setting the correct environment variable, and the first comparison stopping on
NUL. `comparison/` is partial, `comparison-complete/` is an earlier successful
comparison; neither replaces the final directory. Rustfmt was unavailable locally;
no component or dependency was downloaded. No JVM test, APK build or device test
was performed; the action/host/UniFFI interfaces are unchanged.

Toolchain: rustc 1.99.1, cargo 1.99.1, Python 3.14.4. Reproduce the completed gates
from this worktree with the locally installed cargo path:

```sh
/home/wakeel/.cargo/bin/cargo test --offline --manifest-path core/Cargo.toml --features uniffi
/home/wakeel/.cargo/bin/cargo build --offline --manifest-path core/Cargo.toml --features uniffi --lib --bin ballistic-core
BALLISTIC_TEST_CORE_BIN="$PWD/core/target/debug/ballistic-core" python3 -m unittest discover -s harness -p 'test_*.py'
python3 core/tests/compare_owner_numeric.py \
  --baseline ballistic_runs/owner_numeric_20261107/baseline-core \
  --candidate ballistic_runs/owner_numeric_20261107/candidate-core \
  --out ballistic_runs/owner_numeric_20261107/reproduced-comparison \
  --record /home/wakeel/Desktop/ballistic/ballistic_runs/core6_05ab6eb/ClockTimerEntry/20261107-083805_ClockTimerEntry.json \
  --record /home/wakeel/Desktop/ballistic/ballistic_runs/core6_05ab6eb/SimpleCalendarAddOneEvent/20261107-085242_SimpleCalendarAddOneEvent.json
```

SHA-256 binary identities (debug builds with UniFFI, retained in artifact root):

- Baseline core: `505885946c81cb551cd3da4d283fdddd342fab0069761c3da055ebf08cb1ea55`.
- Candidate core: `4556d374b2357fca76cd45c87e663f50fac416e078ec19ecbcafdf214ae967b4`.
- Candidate shared library: `0fd90402b56dc0613844e0269bde040fb4fc610f591b72e3f2787803c918b3c5`.

`SHA256SUMS` indexes 364 retained artifact files, all rechecked. Its SHA-256 is
`5174b1a3f20d9ac5b4f5e868819d0a544fd24116778eeb6057ee8bc91fc9ee21`.
Canonical `final-comparison/summary.json` SHA-256 is
`0c77fc73bc82e37753488ac492bca6ce7da16028d8efd06e7a950ae6f33cdfae`.
The freeze and implementation are separate focused commits, with no merge/push.

### Recommendation and limits

Review this as an isolated controlled-language interface prototype. It does not
address either original Core6 request and provides no reason to rerun those tasks
expecting a different decision. Broad support would require a separately designed
owner-intent contract that distinguishes operative fields from quoted content;
simply searching the existing request for times or trusting model span labels is
not that contract. Timer shift-entry remains blocked on a genuine control contract.

No history feature was resurrected, verifier weakened or benchmark oracle exposed.
No SSH, ADB, emulator/phone, model/API, credentials, OS repository or buildserver was
accessed. Root context/benchmark records and unrelated changes were not edited.
Last measured full-suite result remains **8/20 combined, 11/20 state**; >=13/20 is
still unmet. There is no offline-fixture basis for a score claim or deployment.
