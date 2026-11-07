# Observation settling tolerance — 7 November 2026

Base: `05145ed6d685aca047d432ba1236e9f0cb7af11f`. Offline implementation of the
generic tightening proposed in [the diagnosis](OBSERVATION_READINESS_DIAGNOSIS_20261107.md).
The old literal contract permits sparse control changes; this changes that contract.
Camera A's missing Shutter remains unexplained, and no task-score improvement is claimed.

## Matrix frozen before implementation

New behavior tests belong in `harness/test_core_bridge_robustness.py`, using real
normalization, `slightly_different` and `CoreHost.observe` with scripted acquisitions.
Existing observation tests remain unchanged. Freeze this matrix and the tests before
editing production code; record their baseline failures and final results below.

- Six proven changes: button enabled, visible, clickable, text, label, bounds.
  Require rejection of A→B and B→A, then acceptance of A,A (four acquisitions).
- Nonclickable child caption, including a pure wrapper pruned before Rust folding;
  editable values; switch checked/text; clickable row with caption and toggle.
- Text nodes and ancestors with click, long-click, edit, scroll, check, checked,
  selected or focus flags; control roles even with missing action flags; disabled
  or invisible interactive ancestors; deep ancestry and unrelated interactive siblings.
- Insertion, removal, resource, parent, role, class, package and ID changes; every
  normalized flag and all label sources (description, hint, tooltip); bounds changes
  on inert nodes. No tolerance for these even when below the percentage cap.
- Text-only boundaries: 9 nodes/1 change rejected; 10/1 accepted, 10/2 rejected;
  20/2 accepted, 20/3 rejected. Standalone and nested noninteractive ticking text
  accepted, including text appearance/disappearance; changed non-text roles rejected.
- Missing/duplicate/malformed IDs, missing/dangling/forward/self/cyclic parents and
  incomplete/malformed flag metadata reject variation without recursion or hangs.
  Identical observations still follow the original exact-match path.
- Foreground-only cap excludes status/IME/background chrome; accepted observation
  still returns the complete later forest. Activity/size transitions and unready
  acquisitions reset consecutive candidates; failed calls cannot renew sequence.
- Continuous sparse control churn exhausts six acquisitions with unchanged
  0.3/0.6/1.2/2.4/4.8 settling sleeps. Soft deadline exhaustion, remaining-time sleep
  clamp, bracketing, raw acquisition and sanitized transport errors stay covered.

Historical extracts, reproduction script and source hashes remain unchanged.

## Implementation and limits

`slightly_different` compares every non-text field, keeps the existing `floor(n/10)`
changed-node cap, and permits text variation only on `text` nodes with passive
ancestry. Both roles and flags matter: the normalizer maps controls to button/edit/
switch/checkbox/slider/tab/item/list, while a TextView can still carry interaction
flags. Only text/image/container roles can establish passive ancestry; unknown roles
are conservative barriers. Click, long-click, edit, scroll, check, checked, selected
or focused flags block tolerance, even on disabled/invisible ancestors. All ten
normalized flags must be present and boolean. Labels include description/hint/tooltip;
none may change. This is intentionally broader caution than Rust's exact folding rules.

Rust `screen_filter::filter` can prune empty wrappers, fold noninteractive captions
into clickable parents, and fold one checkable child into a clickable row. Therefore
checking only the changing node or its immediate parent is insufficient. A single
forward pass propagates one passive-ancestry boolean per ID. IDs must be unique
nonnegative integers, and non-null parents must already exist; missing, dangling,
forward and cyclic ancestry rejects tolerance. No recursive or per-node ancestor walk.
For fixed node metadata, ancestry work is O(n) time and O(n) scratch space; comparison
also costs the size of supplied strings/field values. There is no new node/text size cap.

Review found another folding case after the original matrix: foreground package
filtering discarded a foreign wrapper's parent edge. Rust can prune that wrapper and
fold its app child into an app control above it. `_foreground_nodes` now records `-1`
for an excluded parent in its **private comparison tree**, instead of calling it a
root. Tolerance rejects it; exact signature matching still works. The returned full
forest/schema is unchanged. A real fresh-binary `filter` test proves this folding
case as well as direct and wrapped captions. A second review test rejects Python's
`False == 0` alias for IDs/parents in the newer sample.

Exact observations still bypass the predicate in `observe`. Foreground bracketing,
activity-transition guards, raw acquisitions, later/full-forest return, retry resets,
six-acquisition limit, 30-second soft deadline, 0.3-second exponential settling
backoff and rejected-sample delays are unchanged. Rust, FFI, action policy and model
transport are unchanged. This does not harden the upstream raw-graph traversal;
ancestry validation applies to the normalized comparison tree reaching the predicate.

Formerly tolerated animations involving labels, bounds, flags, controls, focused
containers or incomplete ancestry can add waits or exhaust the existing budget.
Live latency, compatibility and task impact remain unknown. This still cannot
recognize repeated stale/incomplete trees, transitions wholly between activity reads,
or guarantee that changing noninteractive text is harmless. Camera A's Shutter was
already absent before Rust filtering; its 8→9-node transition remains rejected.
The missing-Shutter cause remains unknown. No Camera fix or benchmark uplift is claimed.
No app/resource/task special cases, expected-control waits, oracle or telemetry were added.

## Validation and freeze chronology

All work was local/offline. No SSH, ADB, emulator/device, network, model/API, secrets,
physical phone, OS checkout or buildserver access. No live owner was assigned. The
history candidate stays unmerged; root context/benchmark files belong to the parent.

- Original baseline: **95 Python tests pass, zero skips** with a newly built main
  Rust binary. Original focused coverage is 22 tests (included in that passing suite).
- Matrix written before production edits: document SHA-256
  `7c1c653887f491e0b12aa3a59b381b0d9ef8f87e06deb558a942229f6af42ca0`.
  The first test draft had one activity fixture error: it reused a tree already
  associated with the old activity. Corrected to a distinct third tree **before
  implementation**, preserving the activity guard rather than weakening it.
- Frozen corrected test-module SHA-256:
  `75110d8c0ac57d07331ef283467f8f463fc4120317cd7f2d998b12d78cd80eaf`.
  Baseline: **41 focused tests, 75 assertion/subtest failures across 11 methods,
  no errors/skips**. First implementation passes all 41.
- Two explicitly additional review tests (excluded ancestry and boolean/numeric IDs)
  first fail against the initial implementation: **3 assertions/subtests**. Their
  fixes bring final focused coverage to **43 passes, zero skips**.
- Full final matrix against the archived baseline host: **116 tests, 103 passing
  methods, 13 failing methods (78 assertions/subtests), no errors/skips**. Final
  host: **116 passes, zero failures/skips**. These are development regressions,
  not held-out evaluation. Tests call the real predicate/observe; no replacement
  predicate is used to manufacture the old/new result.
- `cargo build --offline --locked --manifest-path core/Cargo.toml --bin ballistic-core
  --target-dir ballistic_runs/observation_settling_20261107/target` builds a fresh
  debug main binary from unchanged Rust source. Cargo was invoked by its local
  `/home/wakeel/.cargo/bin/cargo` path (not initially on PATH). No gratuitous Rust
  test rerun; both Python binary variables point at this artifact. Binary SHA-256:
  `9f7bb665a9cc59fb2daed99b5ca4fc4b84feef7aaea357d9b4510204a9bda1f8`.

Re-run from the repository root after that offline build:

```bash
export BALLISTIC_TEST_CORE_BIN="$PWD/ballistic_runs/observation_settling_20261107/target/debug/ballistic-core"
export BALLISTIC_CORE_BIN="$BALLISTIC_TEST_CORE_BIN"
PYTHONPATH=harness python3 -m unittest test_core_bridge test_core_bridge_robustness.SettlingTest test_core_bridge_robustness.TextOnlySettlingTest
python3 -m unittest discover -s harness -v
python3 docs/reproductions/observation_readiness_20261107.py --strict-contract
```

The unchanged historical reproduction's **default mode characterizes the baseline**:
it passes at `05145ed`, while `--strict-contract` there exits 1 with all six invariant
failures. On this candidate, strict mode passes: each six-case A,B,A,A sequence
returns A after four acquisitions, instead of returning B after two. Count/resource/
small-tree negative controls still need three; ticking text still needs two.
A,A,B still returns incomplete A after two, and A,B,B returns B after three. Explicitly
disabled/invisible nodes are still retained. Default mode on the candidate intentionally
fails its baseline assertion; it is not the candidate's acceptance command.

To rerun historical characterization without changing any historical artifact:

```bash
baseline_dir=$(mktemp -d)
git archive 05145ed harness/core_bridge.py harness/test_core_bridge.py \
  docs/reproductions/observation_readiness_20261107.py \
  docs/reproductions/observation_readiness_20261107.json | tar -x -C "$baseline_dir"
python3 "$baseline_dir/docs/reproductions/observation_readiness_20261107.py"
python3 "$baseline_dir/docs/reproductions/observation_readiness_20261107.py" --strict-contract
# Last command intentionally exits 1 on the baseline.
```

Original A/B full records were not reverified in this round; frozen extracts and
historical hashes/script are byte-unchanged. Parent review already established their
provenance. Python compilation and `git diff --check` pass. Logs, frozen test hashes,
baseline host copy and cost script are in ignored
`ballistic_runs/observation_settling_20261107/` in this worktree.

## Local predicate cost

Python 3.14.4, x86_64; fixtures built before timing/tracing. Each pair changes the last
`floor(n/10)` text nodes. Flat and chain shapes exercise the same allowance. CPU is
median of five batches of 100 calls using `process_time_ns`; allocation is median
peak traced scratch bytes from five isolated warmed calls after garbage collection.
No phone, observation/transport timing or general worst-case performance claim.

- 10 nodes, flat/chain: baseline 0.0106/0.0104 ms, candidate 0.0290/0.0290 ms;
  traced peak 1,632 → 1,400 bytes.
- 512 nodes, flat/chain: baseline 0.4974/0.5072 ms, candidate 1.5377/1.5456 ms;
  traced peak 1,696 → 28,392 bytes.
- 2,048 nodes, flat/chain: baseline 2.0477/2.0345 ms, candidate 6.2789/6.3703 ms;
  traced peak 1,696 → 111,336 bytes.

The per-ID map adds linear scratch allocation and validation adds CPU; depth does
not cause a quadratic walk. Exact-match observe calls do not invoke this predicate.
No delay/budget increase compensates for the tightening. Further acquisition
diagnostics remain a separately scoped proposal in the diagnosis, not this patch.
