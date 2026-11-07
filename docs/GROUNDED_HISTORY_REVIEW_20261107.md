# Independent offline review — grounded history, 7 November 2026

**Recommendation: REVISE before the small controlled recorded-prompt comparison.**
Keep the conservative identity policy and presentation-only architecture. Address the two
blocking findings below: temporary allocation amplification and loss of existing planning
context outside the target-identity change. This is not approval to merge, deploy, run a
device, or claim task improvement. No new action-authority or completion-verification bypass
was found in the reviewed paths.

Reviewed candidate **`5bb0b9f399ff9e91e26f150ec4882866d761145f`**, based on **`aaf0e6c`**,
in sibling `/home/wakeel/.t3/worktrees/ballistic/t3-670518a3`. Review worktree started at
**`6e33deb93820262eaea9be51bdfa4cc0113e248d`**, which does not contain the candidate.
Its difference from aaf0e6c is documentation only. Source line references below refer to
the candidate, not the review branch. Read the commit/diff, candidate design/evidence document,
`PLANNING_CONTEXT_AUDIT_20261107.md`, and the relevant action/filter/reader/readback/agent code.

## Original brief and review boundary

The eight existing bare eN history records become ambiguous when current controls renumber.
The proposal adds bounded opaque hN history/current annotations, with no action, pick, citation,
permission, or completion authority. Private raw identity equality is intentionally conservative;
do not weaken it to improve Timer correlation. Steps, role caps, reader isolation, grants,
confirmation, cancellation and verification must remain unchanged. User authorization permits
ongoing Astra fix/test loops without overfitting or safety weakening; this assignment permits
only independent offline review, ignored reproduction harnesses and this documentation commit.

The requested questions were: (1) whether replacing all action descriptions unnecessarily loses
typed values, app names, read questions and unsupported-identity fallback; (2) bounded worst-case
CPU/temporary allocation from rebuilding up to eight full keys, especially the late byte cap;
(3) folding, duplicate/reparented identity, app transitions, foreground sequence and Read/Done
snapshot reuse, without claiming detection of identical replacements; (4) pre-dispatch capture,
missing readback, eight-record expiry and non-completion semantics of denied/cancelled/unknown
actions; and (5) fixture integrity and the limited meaning of recorded-reply replay. Add focused
novel probes, not broad test growth.

Reported evidence was 296 Rust / 100 Python passes and 54 identical-action/log/report port
replays, with prompt-only differences; Camera 6/8 correlations (Shutter e2 to e3), Calendar
2/32, Timer 0/8; mean +399.94 and maximum +457 prompt characters. No Gemini/live improvement
was claimed. Last full Core6 remains 8/20 combined, 11/20 state. Newer targeted Core7 is
0/6 combined, 2/6 state and did not use this candidate. Neither result proves this proposal's
performance. Parent has not merged or approved live use. A subsequent fixed-record model
decision comparison may use unchanged budgets and no live execution, after review.

No SSH, ADB, device, model, credential, OS/build-server, child-agent, production edit, merge
or push occurred. The candidate remained unchanged. Only ignored local harness/artifacts were
added besides this report; no CONTEXT or BENCHMARK_RUNS edit was made.

## Ranked blocking findings

### 1. P1 — the key-size cap does not bound temporary memory or construction cost

**Locations:** `core/src/grounded_history.rs:106–120`, repeated by `:140–145`; node/string
prechecks are at `:35–41`. These are finite bounds, but not small temporary-allocation bounds.

Every same-package text node clones its ancestor strings into a separate JSON value tree.
Only after those trees and the full serialized String exist does `MAX_KEY_BYTES` reject the
result. A long root string is therefore copied once per text node, then JSON escaping can
expand it sixfold. `observe` repeats the rejected construction for each retained target key.
Rejecting the final key does not recover the CPU or peak heap already spent. This is reachable
from app-controlled accessibility strings without any model action authorization bypass.

**Offline reproduction:** one unique root, eight uniquely resourced buttons, and enough text
leaves to total 512 nodes. Every node has explicit package `p`, class `C`, unique resource
`r{id}`, and valid parent-before-child IDs. Fill the root label until the sum of all raw
package/activity/node strings is exactly 16,384 bytes. Use either ASCII `x` or U+0001, which is
a valid JSON string character when escaped. The deep variant places leaves below 31 additional
containers, giving their lineage exactly 32 ancestors. Start with eight valid retained keys
from the small button screen, then observe the large screen. All rebuilt keys are rejected.

Measured here with the candidate's **unchanged function bodies**, copied into an ignored
release-mode harness, Rust 1.99.1, x86-64 Linux. Setup/filtering is outside the measured interval.
Native CPU uses `CLOCK_PROCESS_CPUTIME_ID`; elapsed time uses `Instant`. A separate build wraps
the System allocator to count requested allocation/reallocation sizes and peak live requested
bytes above entry. The allocator-meter build is not used for the native timing figures:

- Flat ASCII, eight-entry observe: **58.661 ms CPU / 59.226 ms elapsed**;
  **20,510,467 bytes peak additional live requested heap**, 272,202,344 cumulative requested bytes.
- Flat escaped, eight-entry observe: **441.686 ms CPU / 442.991 ms elapsed**;
  **80,875,267 bytes peak**, 1,238,136,328 cumulative requested bytes.
- Deep escaped, eight-entry observe: **515.932 ms CPU / 518.770 ms elapsed**;
  **87,038,489 bytes peak**, 1,315,153,480 cumulative requested bytes and 1,895,152 allocation/
  reallocation calls. Three separate native single-key calls took 62.872–63.825 ms CPU each.
- Control: 512 nodes with textless extra containers, accepted 1,245-byte keys, 100 eight-entry
  observations took 44.604 ms CPU total, approximately 0.446 ms per observation. Measured
  additional peak heap was 16,439 bytes.

These are actual local measurements of constructed stress cases, **not Android/phone timings,
RSS, a typical-screen distribution, or a proven global maximum**. Requested cumulative bytes
include reallocations and are not simultaneously resident memory; peak excludes allocator
metadata. The roughly 87 MB peak is sufficient to reject the current small-memory claim for
phone integration even though all inputs meet the advertised node/raw/depth caps. Merely
checking `raw.len()` earlier *after constructing the JSON values* would still miss the problem.

**Minimal revision:** enforce an expansion/serialization budget before cloning repeated
ancestry strings or building an unbounded JSON value tree. A borrowed, budgeted serializer
that stops before exceeding the cap is one option. Validate/index the observation and compute
shared context once per view instead of rebuilding it independently for each target. Oversized
or ambiguous input must still invalidate correlation; do not truncate identity, normalize text,
ignore changing context, or add a permissive match fallback. Re-run this bounded stress case
and the existing equality/authority probes. Phone measurements remain necessary before any
phone-performance claim, but are outside this review's authorization.

### 2. P2 — every action loses its argument context, including unsupported targets

**Locations:** `core/src/grounded_history.rs:191–200`, selected unconditionally by
`core/src/agent.rs:159` and `:364`; compare baseline `core/src/actions.rs:390–413`.

This replaces much more than ambiguous target references. `type` drops its value, `open_app`
drops its selected app, and `read` drops both its question and candidate list. A failed raw key
does not restore ordinary argument context. Current Reader facts retain successful picks, but
cannot recover the question behind a no-match result. The private `typed` vector only helps
when matching text is observed; it is not a planner-visible attempted-input ledger.

**Reproduction:** `probes.py` runs each binary with the same owner request and identical
scripted observations/results, varying only the first accepted action. On the second planner
call, the entire candidate state prompt is byte-identical for each of these pairs; the
baseline prompts differ:

- Type `alpha` versus `beta` into an editable Search field **without a resource ID**, with
  acknowledged input and an unchanged/unreflected field. Baseline retains `type e1 "alpha"`;
  candidate retains only `type h1 role=edit`. Neither candidate run has current correlation.
- Read c1 with “Which row is alpha?” versus “Which row is beta?”, reader result `none`, and
  deliberate snapshot reuse. Candidate retains only `read` plus the same fixed no-match
  feedback. The existing private read-repeat cap remains, but it cannot tell the planner
  which question already failed.
- Open `app.alpha` versus `app.beta`, both listed/granted, with identical unchanged readback.
  Candidate retains only `open_app: opened_ui; screen unchanged`. This acknowledges transport,
  not a successful foreground transition, so the selected app still matters for recovery.

These establish **information loss**, not a measured model error. They are ordinary navigation,
typing and reader-recovery cases independent of the Camera/Timer/Calendar recordings. General
action validation still works, but saying “normal fallback remains available” does not mean
its planning information was preserved. With 40/48 adjacent targeted transitions unavailable,
the representation often removes useful information without supplying current correlation.
The selected 54 replies include two Calendar type actions but no reader calls; replaying those
fixed replies does not assess the decision effect of either retained or deleted arguments.

**Minimal revision:** narrow the change to historical target identity; preserve the pre-existing
action-argument distinctions and their provenance with explicit bounded rendering/fallback.
Do not newly copy node captions, raw resources, withheld content, reader prose or model `why`.
Retaining already-present action arguments does not make them owner authority or observed
effects. If the experiment requires excluding all planner-authored argument text, separate that
sanitization hypothesis from hN correlation: retain bounded owner-derived values/app selections
where supported, and compare the deletion arm separately. Do not silently bundle universal
argument removal into the correlation-only claim. No longer history, extra calls, extra action
budget, new arithmetic planner or weakened identity is needed to address this objection.

## Smaller findings and unresolved limits

**Eight-record renewal churn:** `grounded_history.rs:170–180` prunes entries before looking up
the current target. Tap e1 on step 1, wait on steps 2–8, and tap it again on step 9 with identical
legacy observations. Step 9's prompt still shows its retained step-1 history and `[history=h1]`,
but capture deletes that entry before renewal, recording h2 instead. Step 10 shows h2. This does
not exceed storage or grant authority, and different handles do not prove distinct objects;
it unnecessarily breaks continuity at exactly the renewal boundary. Renew the currently
matched target before evicting entries no longer mentioned by the resulting eight records.
This is a small usefulness issue, not a separate safety blocker for a recorded comparison.

**“Full ancestry” is not unique identity for every context node:** `grounded_history.rs:109–117`
serializes sibling-content ancestor attributes but does not apply the target path's uniqueness
requirements to those ancestors. A probe with two textless anonymous `Row` containers under
one root, a separate unchanged button, and one text node reparented between the identical Rows
keeps h1 on the button. Raw text and reading order do not change; the two serialized lineages
are identical. This is not an identical replacement: the parent edge changed. It also does
not demonstrate a wrong target or unauthorized dispatch, because the button itself is unchanged.
Nevertheless, the document's general claim that moving text to another container invalidates
continuity is too broad. Either reject such ambiguous content ancestry conservatively or
state this limit explicitly. Do not infer that the private key represents the complete raw graph.

Exact app/activity, unique target/resource ancestry, folded target subtree, source enum, raw
text changes and missing/reappearing target checks otherwise remain conservative in the reviewed
paths. No persistent object generation exists; identical replacements, unseen leave-and-return,
and same-activity stale legacy trees remain indistinguishable. These limits must stay explicit.
No claim that every possible folding or topology mutation was exhaustively tested is made.

## Execution, authority and evidence checks

Capture occurs after schema/current-target validation and before capability checks/dispatch.
That captures the pre-action identity but not completion. `agent.rs:284–351` still enforces
grants, confirmation, cancellation and unknown-execution stopping; `:356–369` records actual
outcomes and delays normal acknowledged-action readback until the next observation. A denial
terminates without a subsequent actionable history; cancellation may leave only an unobservable
private entry. My explicit NotExecuted probe retains an annotation but says “host did not
execute action” and does not complete. Existing denial/cancellation/unknown tests pass.

Failed guarded observation clears keys (`agent.rs:332`). Empty successful observations lose
their targets on the next view. An ordinary next-observation error propagates and produces no
next planner prompt. Missing readback is not replaced with success. The helper never enters
the verifier's fresh filtered view, current action resolver, confirmation key, reader content,
or pick relocation. hN is rejected as both action target and verifier citation. No private key
or historical raw caption is rendered; selected-reader history has fixed feedback and current
Reader facts remain separately governed. Existing caption and terminal reader-prose rules stay.

Freshness probes: after tap with sequence 1 and the next view at 2, an element Read reuses
sequence 2 and retains h1 without another observation. A rejected Done uses its verifier
observation as the next view: sequence 3 retains h1, stale sequence 2 clears it. Both remain
done=false; observation/model-call counts are unchanged. Eight records/entries stay bounded;
the renewal churn above is separate from retention alignment in ordinary expiry.

Independently reran **296 Rust/UniFFI tests and 100 Python tests**, zero failures/ignored/skips,
using offline Cargo and the candidate binary whose SHA-256 matches the reported artifact.
The Python log's mock injection-error and emulator-lock messages are unit-test fixtures; no
emulator or remote process was contacted. No JVM, Android or model test was performed.

The replay script verifies the original three full-record hashes, uses only recorded port
replies plus core arguments, and strips messages/timing for comparison (`compare_grounded_history.py:
21–42, 80–87`). It neither executes an oracle nor sends Hands asks to a device. I reproduced
all 54 calls, equal actions/logs/reports, the 6/8, 2/32, 0/8 correlations, and **21,597 total /
399.94 mean / 457 maximum additional characters**. The synthetic matrix hash also matches.
The transition extractor assumes these particular one-plan-per-action traces and is not a
general reader/correction-loop evaluator. Freeze chronology is supported by retained notes and
artifacts, not independently provable from the candidate's single final commit. No fixture
hash mismatch or task-specific route in the production helper was found.

## Review artifacts, reproduction and disposition

Ignored artifacts are in this review worktree's
`ballistic_runs/grounded_history_review_20261107/`. `candidate_history.rs` is byte-for-byte
candidate source followed only by two inspection wrappers; original function bodies were not
edited. `main.rs` constructs the stress inputs and meters them. `probes.py` drives only local
binaries with scripted replies and saves full outputs. Initial harness mistakes (answering
fire-and-forget `log`, and using `reason` instead of the required NotExecuted `message`) were
corrected before successful assertions; they were harness issues, not candidate failures.

From the review worktree:

```sh
python3 ballistic_runs/grounded_history_review_20261107/probes.py
/home/wakeel/.cargo/bin/cargo run --offline --release \
  --manifest-path ballistic_runs/grounded_history_review_20261107/Cargo.toml
/home/wakeel/.cargo/bin/cargo run --offline --release --features allocation-meter \
  --manifest-path ballistic_runs/grounded_history_review_20261107/Cargo.toml
```

SHA-256 anchors:

```text
e9617564fa6370686d72f121e0c3527574c39e7291bec7182e2149b50af32a1b  candidate debug ballistic-core
6076da32f42c4790b108941917aed542fa862400512d52e8b1c21e8e6af3ac1e  aaf0e6c baseline-core
038c699eae3b97ae263860651ae1d1724b36b085aa8d78d634ba935785eea99a  candidate_history.rs
c399b154a80816e682621ee02cbae8c423310c09033d5f551791f83dc1308caf  main.rs
b7a064c580500c6a3d8a5867527324c640d39400b1d71302c1013a97584f3d9c  probes.py
67846427baf251fc6e571ded9c281d3efba2db3106d510dc8c85772e77e2562b  perf-native.txt
5753c41c3aa2786d7d0a045aae7ffd6f7bdc92e30900ad9812e7352195534f3f  perf-meter.txt
8eb81a42841149666e044727b8fe0e035145638a78f05d22b349a69dbd7fd4a0  replay/comparison.json
```

The two blockers remain unresolved; this reviewer made no candidate fix. After revision, freeze
the exact baseline/candidate prompts and evaluate a **small, predeclared recorded-state decision
comparison**, including unsupported-identity argument-recovery examples as well as renumbering.
Keep model/configuration, role caps, correction slots and action/step budgets identical; execute
no generated actions. Score target choice, recovery/repetition, invalid hN use and completion
claims, and report actual prompt tokens/cost separately. Existing deterministic replay is the
plumbing gate, not that decision experiment. Running today's frozen prompts is technically
possible offline, but a baseline-versus-candidate difference would conflate target correlation
with blanket memory deletion; I recommend revising first. Any such decision comparison remains
distinct from production deployment, closed-loop task outcomes or a >=13/20 claim. No automerge.
