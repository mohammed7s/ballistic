# Grounded history revision — 7 November 2026

**Offline implementation revision, ready for parent review; no model uplift or deployment claim.**
This responds to both blockers and the two smaller findings in
[GROUNDED_HISTORY_REVIEW_20261107.md](GROUNDED_HISTORY_REVIEW_20261107.md).
The original candidate report remains historical evidence, superseded here for implementation,
bounds and validation. These are development cases informed by the review, **not held-out tests**.

Worktree: `/home/wakeel/.t3/worktrees/ballistic/t3-a3c6dbf6`.
Starting parent: `d0b3b0610f1f254bf7a6cdef61b10a03df435e1b` (review document, no candidate code).
The required original `5bb0b9f399ff9e91e26f150ec4882866d761145f` was cherry-picked as
`8c282f0629759ac6ce435dbdffe83accb9c8f894`; fixes are the separate commit
`5ff7d570e14a484e0f6b0b8876408240ddd1509c`.
The original candidate, parent and reviewer worktrees were only read. No children, SSH, ADB,
device, model, credential, OS/build-server, push or merge work occurred. CONTEXT and
BENCHMARK_RUNS were not edited. No JVM test or phone-performance claim is made.

## P1: expansion is bounded before allocation

`grounded_history.rs` now serializes borrowed fields directly through `BudgetWriter`.
Every write checks its remaining encoded-byte allowance before allocating/extending. JSON
escaping therefore consumes the budget as it occurs, without building JSON Value trees or
cloning ancestry strings. Oversized identity is rejected in full, never truncated, normalized,
hashed or replaced with a guessed identity.

A `Prepared` observation validates raw limits and parent indices once. A sorted borrowed
resource index computes uniqueness once. Same-owner content and its complete ancestry are
serialized once per owner, caching failure as well as success. Matching filters by exact
private owner and resource before construction, so an eight-entry observe caches at most eight
owners. It does not rebuild rejected content eight times. Capture may independently construct
one new target key; it adds no observation or model call.

The equality payload still contains exact screen package/activity, target owning package,
filtered role and complete Source value, target path including flags, complete target subtree
and relative topology, and all same-owner raw text/labels in reading order with their raw
ancestry. None versus empty, whitespace, case and hidden strings stay significant. No private
string is rendered. The new writer serializes flag object members in struct order rather than
Value-map order; this changes private bytes, not equality information. Keys are run-local and
are not migrated across implementations. Accepted control keys remain 1,245 bytes in the
reviewer controls.

Bounds remain 512 nodes, 16,384 raw string bytes, 32 ancestor levels and 32,768 encoded key
bytes. The latter includes shared content; the per-target writer reserves its exact encoded
length before serializing target fields. Borrowed path/subtree vectors are bounded by depth
and node count, without owned app strings. Each temporary shared-content buffer and target
buffer has at most 32,768 requested capacity. Shared buffers expire after observe. At most eight
retained key buffers use 262,144 requested bytes total; their copied owner/resource lookup
strings together use at most another 131,072 bytes, plus entry/vector metadata. This is a
bound on this helper's payload allocations, not total screen/filter/process memory or RSS.

### Original stress reproduction, unchanged complexity

Copied review artifacts read-only into ignored
`ballistic_runs/grounded_history_revision_20261107/original-review/`.
The before helper and both stress `main.rs` files retain the review's original hashes.
The after helper is the exact revised production file plus the same two inspection wrappers.
Only local dependency paths and the needed direct `serde` dependency changed in Cargo wrappers.
Both run 512 nodes, exactly 16,384 raw bytes, the same eight retained keys, the same flat ASCII /
flat U+0001 / deep U+0001 cases, and the same three single-key calls. Deep lineage still has 32
ancestors. Setup/filtering stays outside measurement, as in the original review.

Actual measurements here: Rust 1.99.1 release, local x86-64 Linux. Native timings use process
CPU clock / Instant. The separate System-allocation meter counts all requested allocation and
reallocation sizes inside the operation; its timings are not used as native timings.

| Eight-entry observe | CPU ms before → after | Elapsed ms before → after | Peak additional requested heap, bytes before → after | Cumulative requested bytes before → after | Calls before → after |
|---|---:|---:|---:|---:|---:|
| Flat ASCII | 58.442 → 0.135 | 58.440 → 0.134 | 20,510,467 → 43,304 | 272,202,344 → 135,875 | 136,112 → 24 |
| Flat escaped | 437.817 → 0.163 | 437.928 → 0.162 | 80,875,267 → 43,304 | 1,238,136,328 → 129,320 | 136,168 → 28 |
| Deep escaped | 522.320 → 0.174 | 522.545 → 0.174 | 87,038,489 → 43,304 | 1,315,153,480 → 129,320 | 1,895,152 → 28 |

All stress keys still reject; no correlation is recovered by weakening equality. Three native
deep single-key calls take 63.081–64.132 ms before and 0.196–0.222 ms after. These measurements
are individual local runs, not a latency distribution, phone extrapolation or global maximum.
Cumulative requested bytes include reallocation sizes; peak excludes allocator metadata.

Accepted controls (100 eight-entry observations, same setup in both versions):

| Control | CPU ms total before → after | Elapsed ms total before → after | Peak additional requested bytes before → after | Cumulative requested bytes before → after | Calls before → after |
|---|---:|---:|---:|---:|---:|
| Original reviewer 512 nodes, textless extras | 44.695 → 8.671 | 44.694 → 8.671 | 16,439 → 29,184 | 17,018,400 → 9,711,200 | 291,200 → 7,800 |
| Nine nodes, eight ordinary buttons | 15.330 → 1.702 | 15.338 → 1.700 | 16,439 → 4,225 | 17,018,400 → 4,888,100 | 291,200 → 7,300 |

The 512-node accepted case uses **more peak scratch memory** for the once-per-view index;
that tradeoff is included, not hidden. An intermediate revision scanned every resource against
every node and took 97.515 ms for this control; the sorted borrowed index removes that regression.
The final figures above are the frozen implementation's measurements. Phone performance remains
unmeasured and outside this assignment.

### Allocation regression, red before / green after

`core/src/grounded_history_allocation_tests.rs` meters actual requested bytes thread-locally,
including all reallocations, while observing the three unchanged reviewer inputs. It asserts
at most **256 KiB cumulative requested bytes per eight-entry observe**, without wall-clock
assertions; this also bounds newly allocated live bytes. No source preparation/indexing is
moved outside the measured helper. Only input/filter/initial-handle setup is excluded.

The identical test file was compiled against the unchanged copied candidate helper:
**all three fail**, reporting exactly 272,202,344 / 1,238,136,328 / 1,315,153,480 bytes.
All three pass in the revised full Rust suite. Separate tests check escaped-writer capacity,
exact ordinary quoting, explicit large-argument omission, ancestry ambiguity and renewal.
This is a development regression gate, not a claim that 256 KiB is a proven global helper bound.

## P2: restore existing action arguments and their provenance

Reviewed `actions::describe` before changing the history formatter. Type retains its exact
Debug-quoted text and submit flag; open_app retains its selected app; read retains the cN
candidate list and exact quoted question; give_up retains its quoted reason. Done/Answer
remain as described by the baseline. Only actionable historical eN target identity becomes
hN/role. Unsupported private matching still retains these ordinary arguments. Read cN values
are recorded historical candidates, never current reader picks or new authority.

This does not newly copy node labels/resources, withheld content, reader prose or model `why`.
These are the same previously visible planner action arguments: intent/attempt records, not
owner instructions, app truth or observed effects. A 108-character system-prompt addition makes
that provenance explicit. Existing source-tagged Reader facts and fixed selected-reader feedback
are unchanged, as are argument validation, picks and confirmation.

Each quoted argument and rendered candidate list streams through a **2,048-byte** history
budget. Ordinary arguments preserve baseline spelling, Unicode and quoting exactly. Oversize
uses an explicit whole-argument omission marker with original UTF-8 length, or candidate count;
it never displays a truncated prefix as a complete value. For example:
`[argument omitted: exceeds 2048-byte history limit; utf8_bytes=4096]`.
Two omitted equal-length values may remain indistinguishable; the omission is explicit.
This history-only bound does not alter the actual action, log or dispatcher arguments.
Read has two separately bounded fields (4,096 bytes total); the other forms have one.
The original report's sub-768-character history-line claim no longer applies. Eight records,
512-character outcome handling and existing finite readback vocabulary remain unchanged.

Replayed the review's original ordinary argument probes against the original saved candidate
and a freshly built revision, changing only binary paths and expected assertions:

| Same owner request and unchanged readback | Original candidate next prompt | Revised next prompt history |
|---|---|---|
| Unsupported Search type alpha / beta | Identical | `type h1 role=edit "alpha"` / `"beta"` |
| Read c1, result none, question alpha / beta | Identical | `read c1 "Which row is alpha?"` / `"Which row is beta?"` |
| Open app.alpha / app.beta, unchanged screen | Identical | `open_app "app.alpha"` / `"app.beta"` |

The baseline already distinguishes all three pairs. Revision restores that distinction, with
no current correlation in these cases. Each of the six revised runs preserves all original
non-message/non-timing port output, dispatches, logs and reports. Complete prompts and the
comparison are saved in `before/`, `after/` and `argument-comparison.json`. Unit coverage also
checks multiple read candidates and quoted/control/Unicode argument spelling. These demonstrate
information restoration and plumbing, **not semantic model recovery**.

## Smaller findings and authority isolation

- **Renewal:** lookup and renew the currently displayed handle before pruning; on a new target,
  prune before insertion. Step 1 tap, seven waits, step 9 tap now records the displayed h1 again.
  Step 10 still has eight records and h1. Existing eight-live-key and 1,000-update bounds pass.
- **Ambiguous content ancestry:** apply the target-path uniqueness/ownership/class requirements
  to every text/label ancestor. Anonymous nested Rows reject, including the review's separate
  unchanged button with a text node moving between two identical anonymous Rows. Such paths
  never acquire continuity. This intentionally narrows support; it does not approximate a parent.
- **Isolation:** source/filter, action resolver, current eN validation, reader selection/pick
  relocation, grants, confirmation, cancellation, unknown execution and verification code are
  unchanged from the candidate. No extra observation/model call, numeric plan, dispatch rule,
  repeat veto, budget, retry, step/output cap or correction-slot change. hN action and citation
  rejection tests still pass. Full integration tests cover denial/cancellation/reader/verifier
  behavior; the independent review probes retain negative NotExecuted feedback, deliberate Read
  snapshot reuse and fresh/stale rejected-Done handling with unchanged observation counts.

Identical replacements, unseen leave-and-return and same-activity stale legacy trees remain
indistinguishable. No object authenticity, complete-graph identity, persistence, exact capture
count or completion follows from correlation. The private key is still deliberately conservative;
unsupported paths are unknown. Successful reproduction of these probes is not an exhaustive
proof over every possible tree or a general safety certification.

## Final offline gates and frozen recorded-port comparison

**303 Rust/UniFFI tests and 102 Python tests pass; zero failures, ignored tests or skips**, with a
fresh binary/library from the fix commit. Seven Rust tests and two Python methods were added
net; Python methods include subcases. The suite's printed injection-error/emulator-lock messages
are mocked fixtures, not live calls. No JVM or Android test was run.

The unchanged replay script revalidates the three original Core6 file hashes. All **54** supplied
planner calls preserve every non-message/non-timing output: Hands operations, original action
logs, port order, role/output caps and reports. Correlation remains **Camera 6/8, Calendar 2/32,
Timer 0/8**, including Shutter e2 → e3. None of those figures measures new model decisions.

| Trace | Calls | Baseline mean / max characters | Revised mean / max | Paired overhead mean / max |
|---|---:|---:|---:|---:|
| Timer | 10 | 10,490.50 / 10,763 | 10,994.80 / 11,312 | +504.30 / +555 |
| Calendar | 34 | 11,678.62 / 12,769 | 12,212.88 / 13,283 | +534.26 / +556 |
| Camera | 10 | 9,924.30 / 10,096 | 10,435.20 / 10,650 | +510.90 / +565 |

Total added characters **28,317**, mean **524.39**, maximum **565** over baseline, measured
across actual message contents. Original candidate was 21,597 / 399.94 / 457; restoring arguments
and adding provenance increases total by 6,720, mean by 124.44. No tokenizer/model was invoked;
actual tokens, cost, latency and decision quality remain unmeasured.

Last full Core6 remains **8/20 combined, 11/20 state**; targeted Core7 remains **0/6 combined,
2/6 state**. Neither used this candidate. The >=13/20 goal remains unmet. Parent review must
precede any small, predeclared recorded-state model decision comparison, with unchanged
model/configuration/budgets and **no action execution**. Include unsupported-identity argument
recovery and renumbering, score target choice/recovery/repetition/invalid hN/completion claims,
and report actual prompt tokens/cost separately. Such a comparison was not performed here;
no full suite, production deployment or automerge is proposed by this revision.

## Reproduction and artifact anchors

Artifacts below are under this worktree's ignored
`ballistic_runs/grounded_history_revision_20261107/`. `manifest.json` records 95 artifact hashes,
including both original/revised binaries, before/after outputs, full test logs and full replay
prompts. No artifacts were written in the reviewer or original candidate worktrees.

```sh
/home/wakeel/.cargo/bin/cargo test --offline --manifest-path core/Cargo.toml --features uniffi
/home/wakeel/.cargo/bin/cargo build --offline --manifest-path core/Cargo.toml --features uniffi
BALLISTIC_TEST_CORE_BIN="$PWD/core/target/debug/ballistic-core" \
  python3 -m unittest discover -s harness -p 'test_*.py'
python3 core/tests/compare_grounded_history.py \
  --baseline ballistic_runs/grounded_history_revision_20261107/baseline-core \
  --candidate core/target/debug/ballistic-core \
  --records /home/wakeel/Desktop/ballistic/ballistic_runs/core6_05ab6eb \
  --output ballistic_runs/grounded_history_revision_20261107/replay
# Run for before and after, native and allocation meter separately:
/home/wakeel/.cargo/bin/cargo run --offline --release --bin review \
  --manifest-path ballistic_runs/grounded_history_revision_20261107/after/Cargo.toml
/home/wakeel/.cargo/bin/cargo run --offline --release --bin review --features allocation-meter \
  --manifest-path ballistic_runs/grounded_history_revision_20261107/after/Cargo.toml
# --bin normal uses the supplemental identical nine-node control in both versions.
python3 ballistic_runs/grounded_history_revision_20261107/after/probes.py
# Expected failure: three unchanged stress allocation regressions against original code.
/home/wakeel/.cargo/bin/cargo test --offline --release \
  --manifest-path ballistic_runs/grounded_history_revision_20261107/before-regression/Cargo.toml allocation_tests
```

SHA-256 anchors:

```text
6076da32f42c4790b108941917aed542fa862400512d52e8b1c21e8e6af3ac1e  baseline-core
e9617564fa6370686d72f121e0c3527574c39e7291bec7182e2149b50af32a1b  original-candidate-core
a8f7005e94d37677118ef13a7cd594354d3a9fc8572611a76e75a5ffc27be711  revised-core
40716b41231f28dc1956c0a847088bae18f8900e320f113f8952671a94519b9f  revised-libballistic_core.so
038c699eae3b97ae263860651ae1d1724b36b085aa8d78d634ba935785eea99a  before/candidate_history.rs
c399b154a80816e682621ee02cbae8c423310c09033d5f551791f83dc1308caf  before/main.rs AND after/main.rs
1550db0bc3671b25084215516e7c9e75574213e636605aef2e18ac072d6bf1c8  after/perf-native.txt
866c402d7021c4ebb3b358561908f878ea638d81fb23cabdf57808e807539e7e  after/perf-meter.txt
1fdc2ff98369f9cf33948d37b8449afd2f089cc783349e51a607824959f1539a  replay/comparison.json
d9b9fd6af6cc9d4b97d7aec8ab107abe9370eb22018e34ad77f515a59c6f70a1  argument-comparison.json
2435b8f01e4dc4cd33eb65347f6122f4b9963722f73d3df2b10a8345a1292500  manifest.json
```
