# Observation readiness diagnosis — 7 November 2026

**Camera A's missing Shutter remains unexplained.** It is already absent in the
accepted normalized host observation, before Rust filtering. The recorded A/B
difference is not an example of the `slightly_different` tolerance: it changes the
node count from eight to nine, which that predicate rejects. Separately, an offline
reproduction demonstrates that the generic tolerance accepts materially changing
controls. Tightening that heuristic is defensible; calling it the cause of A's
failure, or a demonstrated Camera fix, is not.

Review base: `389579429663f44abbb3fcea73fd6f302e861311`, runtime `4de7ade`.
Only this report, frozen extracts and reproduction code change. No runtime patch,
candidate integration, device, SSH/ADB, network, model, credential or buildserver
access occurred. The parked history candidate remains parked. No score changes:
last complete suite is 8/20 combined, 11/20 state.

## Recorded facts and where the absence occurs

The [frozen extracts](reproductions/observation_readiness_20261107.json) retain every
field of both first Camera node lists, observation headers, subsequent planner screen
and relevant lifecycle/warning lines. These are **normalized replies**, not original
accessibility protobufs. Both replies are `lines[8]`, id 4, followed by the planner
request at `lines[9]`, id 5. Original full-file SHA-256:

```text
8f13cee95d966f66c5431b22b6db6a3e2224e1d2eab91457116e0fa7e656c48e  A/live/20261107-105849_CameraTakePhoto.json
bf0ec196451ce0343eea2eada2ca52ca6c30cbdd32f655a40c6b5cc9c18ac785  B/live/20261107-110002_CameraTakePhoto.json
```

Paths are relative to the parent's local
`ballistic_runs/history_camera_pair_20261107_b90500a5/`.

- Both Camera observations identify `com.android.camera2/com.android.camera.CameraLauncher`,
  1080×2400, host sequence 2. A contains eight nodes: root, preview overlay,
  Options parent/image, mode list, filmstrip, zoom out/in. All eight report visible;
  zoom out is disabled and is retained in the raw reply **and** planner screen.
- B contains exactly those eight nodes plus `com.android.camera2:id/shutter_button`:
  image, label `Shutter`, bounds `(0,2022)-(1080,2337)`, visible/enabled/clickable.
  Every other node field matches A after renumbering IDs and parents. B's filtered
  screen exposes Shutter as e2. A has neither this resource nor an anonymous extra
  image where its caption might have been hidden. A gives up immediately.
- [`forest_to_nodes` / `_windows_to_nodes`](../harness/core_bridge.py#L65) traverse
  the supplied forest and preserve enabled/visible flags; they do not discard
  disabled/invisible nodes. [`_foreground_nodes`](../harness/core_bridge.py#L143)
  selects active/focused application/system windows and package nodes **for readiness
  and comparison only**. Once accepted, [`observe`](../harness/core_bridge.py#L277)
  returns normalization of the entire forest. Window selection therefore does not
  directly remove nodes from the returned screen.
- Rust's [visibility/container pruning](../core/src/screen_filter.rs#L427) occurs
  after that reply. It cannot explain a Shutter absent before filtering. A malformed
  producer graph could also affect traversal; the original graph is unavailable,
  so neither producer omission nor graph loss is proven.

Conclusion by layer: **missing in the normalized snapshot: proven; removed by Rust
filtering or host disabled/visible filtering: unsupported by the trace and source;
omitted by upstream collection, never exposed by the app, or sampled while stale/
transitional: unknown.** No recording establishes that a disabled/invisible Shutter
existed at acquisition time, or that a later ready Shutter would have appeared in A.

The pair archives contain Ballistic source, not the upstream collector/app source.
`inventory_before.json` pins AndroidWorld `55ddaf5edc6f6c25673aed343907d5b9dca66f84`
and file hashes, including `android_world_controller.py`
`7027728b5088a9d907bb2c1b3ed65b8767e4d049c2827663c5eee5293823bd70`
and `camera.py` `9cbeb7c43adc42f5b4edadc280359371bda8759c392e41f20564f9dc741a7944`.
No corresponding source bytes were found in the inspected local artifacts. Hashes
are not source inspection. Upstream visibility/disabled pruning and Camera startup
behavior therefore remain unverified here; no current online source is substituted
for the installed version.

## What readiness proves, and the generic counterexample

[`foreground_tree_ready`](../harness/core_bridge.py#L130) requires **any** visible,
positive-area foreground node with label/text/click/edit affordance. Both recorded
Camera trees satisfy it. This is a useful-content gate, not an app-completeness
contract. It cannot know that a task-relevant control is missing.

[`observe`](../harness/core_bridge.py#L217) brackets each raw acquisition with activity
reads; rejects unready or contradictory activity/tree associations; then accepts
two same-component, same-size trees that match or pass
[`slightly_different`](../harness/core_bridge.py#L179). It returns the later sample.
Host sequence counts successful calls, not producer capture generations.

The [standard-library reproduction](reproductions/observation_readiness_20261107.py)
calls the real normalizer and `CoreHost.observe` using the existing scripted `FakeEnv`:

- Reconstructed A,A,B returns A after two samples without seeing B. A,B,B waits
  for the third and returns B. Reconstruction supplies synthetic window metadata;
  these are possibility demonstrations, **not** recovered A/B acquisition sequences.
  Identical incomplete/stale trees are indistinguishable from legitimately stable
  trees at this boundary. More fixed delay cannot establish otherwise.
- Supplying an explicitly disabled **and** invisible Shutter preserves the node and
  both flags in the normalized reply. This tests the suspected host filtering path,
  not upstream collection.
- In a generic ten-node forest, changing one button's enabled, visible or clickable
  flag, button text (`Continue`→`Delete`), content description, or bounds independently
  accepts the second sample. A third scripted reversal is never acquired. Six cases
  demonstrate actual observable control changes being called settled.
- Negative controls: a nine-node flag change, resource replacement, and added node
  each require a third matching sample. A nonactionable ticking text still accepts
  the second sample. No task labels or Camera logic enter the generic cases.

This is a **generic heuristic weakness relative to the intended animation/ticking
value tolerance**, not a failure to implement the literal current rule: its docstring
explicitly permits changes to text, labels, bounds **and flags** on ≤10% of nodes.
`--strict-contract` intentionally fails the proposed invariant that actionable-control
changes must reset settling. It does not pretend that invariant is already enforced.
Existing [settling tests](../harness/test_core_bridge_robustness.py#L225) cover ticking
text, broad changes, count and movement, but not this sparse material-change case.
The eight-/nine-node Camera replies permit **zero** changed nodes under the tolerance;
this counterexample cannot account for their difference.

For a separate implementation round: keep the current acquisition/deadline/activity
guards and later-sample return; narrow `slightly_different` to reject flag and bounds
changes and changes to controls or their folded labels. Do not replace it with an
expected-label wait. A conservative generic variant can require identical skeleton,
all flags/bounds/labels, and allow bounded text-only changes on noninteractive text
nodes outside actionable ancestry. That still does not prove the text is a time
counter or harmless; semantic freshness checks must stay independent. Unrecognized
animation may then time out, a compatibility cost to report. Test the six failures,
folded child captions, edit values, switch state, node insertion/removal, sizes 9/10/20,
legitimate counter changes, chrome exclusion, activity transitions, retry resets and
deadline exhaustion. Do not increase budgets or weaken freshness to recover failures.

## Setup comparison and missing diagnostic evidence

The frozen `pair_task.py` matches [`run_task._main`](../harness/run_task.py#L142) for
seed initialization, environment construction, `reset(go_home=True)`, task parameter
generation/initialization and the ten-step `_run_core` call. There is no additional
pre-agent Camera launch or warm-up. It **does** add `task.tear_down(env)` in `finally`
before closing; the normal core branch just closes the environment. The orchestrator
also spaces arms by five seconds. Thus the task setup calls match, but the inter-run
lifecycle differs from normal runs; B follows A's added teardown. No causal effect
of that difference is measured.

Both arms warn that the Camera snapshot is absent during setup and teardown. A
successful identical snapshot restore is therefore not established; seed 0 and the
same calls do not establish equal Camera state. The unequal first Camera observations
directly demonstrate unequal inputs. Missing snapshots do not prove which app state
persisted, or why Shutter differed. No private before/after photo inventory was saved.

A's sole `Could not get a11y tree, retrying` warning is timestamped **10:58:33.180879**,
before `task_setup_start` at **10:58:34.181823 UTC**, and before the agent starts.
It is an environment-setup warning, not evidence of a failed Camera observation.
Both runs subsequently produce successful host replies. Calling that warning the
cause of Shutter omission would be unfounded.

[`serve` / `_answer`](../harness/core_bridge.py#L370) log port requests and final
replies. `observe` does not log individual acquisitions, before/after components,
window selection, rejected candidates, exact-versus-slight acceptance, or per-sample
timings. Consequently the actual number and differences of accepted Camera samples
are unknown. A bridge replay cannot recover them.

Minimal telemetry proposal, **not implemented**: one bounded host-only diagnostic
record per acquisition (maximum six per observe), linked by observe-call/attempt
number, with monotonic elapsed duration, fixed rejection/acceptance enum, component
equality/change booleans, selected-window counts, raw/normalized/foreground node
counts, and aggregate changed-field counts (skeleton, flags, bounds, label, text).
Include `exact` versus `slight` acceptance and the already requested sleep duration.
No text, resource IDs, raw components, coordinates, exception payloads, content hashes
or screenshots are needed; do comparisons in memory. Keep diagnostics out of core,
planner, verifier, approval and completion evidence. Even this would locate an
observed transition, not certify capture freshness or explain an unchanged missing
control; upstream collection diagnostics would be a separately scoped follow-up.

## Offline validation

```bash
python3 docs/reproductions/observation_readiness_20261107.py
python3 docs/reproductions/observation_readiness_20261107.py --strict-contract
PYTHONPATH=harness python3 -m unittest test_core_bridge test_core_bridge_robustness.SettlingTest
```

The first passes all characterization/control checks; the second intentionally exits
1 with all six proposed-invariant failures. The focused host command passes **22
tests**, zero failures/skips. Python compilation and `git diff --check` also pass.
Logs are in ignored `ballistic_runs/observation_readiness_20261107/`
(`reproduction.txt`, `strict-contract.txt`, `host-tests.txt`).
Add `--verify-pair <parent-artifact-dir>`
to independently verify the two original record hashes/extracts and both archived
host/runner hashes. Verified here: both archived hosts and current/runtime `4de7ade`
host are byte-identical, SHA-256
`c15a7391b60cc17f11658638056189f68a502c23fddc8f0c4a47c56df5bb7970`.
No Rust/JVM rebuild is needed for these documentation/reproduction-only changes;
existing full-suite totals are historical, not rerun claims.
