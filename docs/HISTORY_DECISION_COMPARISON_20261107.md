# History decision comparison — predeclared 7 November 2026

Status: **completed on 7 November 2026; no broad decision benefit demonstrated**. The
history revision remains isolated from master. The protocol below was recorded before
candidate model decisions were seen; results and the subsequent decision are appended below.
The comparison made no device calls and executed no generated actions.

The parent verified and copied all 49 indexed preparation files to ignored
`ballistic_runs/history_decision_eval_prep/`. Primary artifacts:

- `manifest.json`: SHA-256 `37b7cca6c8005d3b9c0b7de215704f70632578cec35f68610aaa2eb4d64ff27c`.
- `SCORING.txt`: SHA-256 `d186a04d6b0513936b0db5f02b4046ca679ac9b7fbfa9b121104ccda7f23f421`.
- `SHA256SUMS`, `README.txt`, case prefixes, reference prompts and offline controls.
- File-only freeze, information-preservation and analysis tools. They make no model calls.

## Fixed cases and order

A is the reviewed `aaf0e6c` baseline (runtime `4de7ade`); B will be the exact reviewed revision.
The rejected `5bb0b9f` prototype is not B. There are **six cases and twelve logical requests**,
with three AB and three BA pairs. No extra cases or outcome-driven repeats are reserved.

| Pair | Recorded decision | Order | Predeclared interpretation |
|---|---|---|---|
| 1 | Camera step 4 | A, B | `tap e2` selects the visible Shutter before any Shutter-targeted attempt. Other inspection is not automatically wrong. |
| 2 | Camera step 5 | B, A | `tap e3` repeats the acknowledged Shutter input. Inspecting existing-photo UI, waiting, Done and giving up remain separate classes. |
| 3 | Timer step 5 | A, B | Digit 4 continues the requested prefix only under the stated shift-entry hypothesis; unconditional numeric correctness remains unknown. Starting violates the explicit owner request. |
| 4 | Calendar step 13 | B, A | Retaining/confirming zero minutes is appropriate conditional on a recovered Start-time binding; the current modal alone does not identify that binding. |
| 5 | Camera step 8 | A, B | Another `tap e3` repeats an acknowledged capture attempt. Four earlier attempts do not establish four photos. |
| 6 | Calendar step 20 | B, A | Current Start time e10 is 13:15, requested 13:00; End time e12 already matches 13:15. Opening e10 is the preferred local correction; saving now is premature. |

The source requests and current targets were checked independently against the original Core6
records. Cases are familiar and correlated within trajectories, **not held out**. Conditional
cases will not be promoted to unconditional gold after seeing answers. There is no composite
accuracy or task-success score; report formatting, current-target validity, decision classes,
unsupported completion claims and actual cost separately. Avoiding repetition alone is not
completion. No proposed Done is accepted or executed in this experiment.

Three ordinary alpha/beta fixture pairs remain offline-only: typed values in an unsupported
identity field, reader questions returning none, and selected apps with unchanged readback.
These check that existing argument distinctions survive the revision. Their observations do
not justify unique next-action labels, so they consume no model calls.

## Preconditions and limits

Before calls, review the revision, rebuild/verify both arms and freeze exact source, binary,
prompt, schema, fixture and transport hashes. Both arms use the same owner request/current view;
only the reviewed history presentation changes. No scoring metadata or raw withheld content
may be appended to provider messages. Preparation is not an automatic launch.

Use the existing Gemini model/configuration, temperature 0, 800 planner output tokens and
five-second minimum pacing. One logical call per scheduled arm, with no repair, reader,
verifier or execution call. Only inherited transport retries apply; stop on 429, 400 or an
actual environment/transport outage. No availability probe, key rotation or replacement request.
Record failed attempts and unknown usage explicitly. The mutable model alias, unequal prompt
tokens, small sample, shared trajectories and lack of action execution limit interpretation.

A useful result can justify a later closed-loop test; it cannot establish a full-suite gain,
phone performance, exact photo count, saved event, delivery, safety pass or >=13/20 result.
See `GROUNDED_HISTORY_REVIEW_20261107.md` for the unresolved prototype findings.

## Transport clarification before any model outputs

For this decision experiment only, configure the unchanged client with `retries=0` for
**both arms**: one HTTP attempt per scheduled request, at most twelve attempts total.
This supersedes the preparation's references to inherited retry behavior; the six cases,
gold and AB/BA schedule are unchanged. Stop remaining calls on any request/transport/non-text
response failure, including the first 429/400. A nonempty successful reply with malformed action
JSON is recorded as a format failure and the fixed schedule continues without repair.
No production or benchmark retry policy changes. The clarification is recorded before outputs
in ignored `history_decision_eval_prep/TRANSPORT_ADDENDUM.json` and must be pinned in the freeze.

## Results — 7 November 2026

All twelve scheduled requests completed once: twelve HTTP 200 responses, zero retries,
request failures, format failures or missing responses. Final response completed at
10:36:38 UTC. A was `aaf0e6cdd2804cc53093f7d6b4ecee6fbbccef1d`; B was
`5ff7d570e14a484e0f6b0b8876408240ddd1509c` (documentation HEAD `bd8886b`, identical
runtime trees). Final freeze SHA-256:
`710199088eca748247bf85dea7e239931f315fe8b53169a35dd8952eb27884b8`.

| Recorded decision | A baseline | B revised history | Interpretation |
|---|---|---|---|
| Camera 4 | Initial Shutter | Initial Shutter | Local positive control preserved. |
| Camera 5 | Repeat Shutter | Propose Done | Avoids repeat but claims capture without sufficient evidence; no Done executed or accepted. |
| Timer 5 | Digit 2 | Backspace | Neither selects conditional digit-4 continuation; recovery remains unknown. Neither starts the timer. |
| Calendar 13 | Select 15 minutes | Select 15 minutes | Both rationales confuse this with duration; modal binding remains unknown under frozen scoring. |
| Camera 8 | Give up | Give up | Both infer failure from unchanged views without sufficient evidence. |
| Calendar 20 | Open End time | Open End time | Both miss the visible Start-time correction; B wrongly wants 13:30. |

All nine proposed tap targets exist with visible affordances. No generated action reached
production resolution, grants, confirmation, execution or verification; those outcomes are
unknown. No invalid history-handle action IDs were proposed. The single Done proposal is
not a measured false-Done acceptance, and this experiment is not a safety pass.

| Arm | Requests / attempts | Prompt tokens | Output tokens | Summed HTTP latency |
|---|---:|---:|---:|---:|
| A | 6 / 6 | 16,919 | 347 | 16.506 s |
| B | 6 / 6 | 17,516 | 231 | 14.924 s |

B adds 597 prompt tokens total, or 99.5 per selected case. One pass with different output
lengths does not establish a speed improvement. Total elapsed time was 56.767 s including
25.274 s reported pacing; minimum start gap was independently checked at 5.000019407 s.
All prompt/output counters are known; cache/reasoning counters and monetary cost are unknown.
The response identifies only the mutable model alias, not a resolved model version.

The parent verified all 785 files indexed by `EXPERIMENT_SHA256SUMS` and independently read
all twelve raw answers. Full report, source/build provenance, frozen analysis, raw replies
and integrity index are in ignored `ballistic_runs/history_decision_5ff7d_20261107_d96f0cee/`.
Baseline saved-binary provenance rests on the original build report/logs, exact hashes and
prompt reproduction; no fresh baseline build or independent historical clean-tree capture
is claimed. Manual review shuffled and hid arm labels from the same reviewer, not an
independent adjudicator. Six familiar correlated cases with one sample per arm cannot
establish a general improvement. Remote runner absence, credential-safe transfer and
preservation of dirty main were checked; no further calls belong to this experiment.

## Subsequent decision — bounded camera hypothesis test

The test agent recommended retaining the candidate offline without a closed-loop trial.
The parent agrees that these results do not justify merging it or rerunning the full suite.
However, Camera 5's repeat-to-Done change supplies a specific, unresolved hypothesis:
preserving target continuity might prevent extra capture attempts, or merely replace them
with unsupported completion. A narrowly bounded execution test can distinguish those paths.
This is a parent prioritization decision after reviewing the result, not a positive finding
from the twelve-call comparison or a change to its frozen scoring.

Predeclare a separate two-run experiment: **one fresh `CameraTakePhoto` task per arm,
fixed A then B order, seed 0**, with the unchanged normal ten-step task budget, role token
caps, production retry policy, five-second model pacing and completion checker. Build and
test both exact commits before freezing their binaries/configuration and taking the exclusive
emulator lock. Use the original key and existing Gemini model; stop on first 429/400 or actual
environment/transport outage. No outcome-driven reruns, extra probes, policy relaxation,
task-specific oracle, or production merge. Record agent Done, independent state and combined
outcome separately, plus acknowledged Shutter-targeted inputs, verifier evidence, calls,
tokens and timings. Acknowledged taps do not establish exact photo count. Preserve the dirty
remote main and original visible emulator; release and independently verify ownership at end.
This small familiar-task experiment cannot establish a full-suite gain or >=13/20 result.

## Camera execution result — 7 November 2026

Exactly A then B ran once, seed 0, at the pinned commits above. Both finished with no
Done proposal, no verifier call, independent state failed and combined FAIL. A gave up at
step 2 because its raw Camera observation lacked a Shutter node. B saw Shutter, dispatched
two acknowledged taps, waited four times and gave up at step 8. At its second tap the current
Shutter explicitly carried the same history handle as the first acknowledged tap. A new
last-photo thumbnail was visible, but B did not inspect it. The earlier recorded-state
repeat-to-Done change did not recur in this fresh B trajectory.

The unchanged checker tests for exactly one new photo-directory filename. Neither run
retained the private before/after filename sets, so actual photo counts remain unknown.
Two acknowledged inputs are not proof of two photos. A never reached the post-Shutter
comparison state; unequal starting observations, missing Camera snapshots in normal AW
setup/teardown and one fixed-order sample prevent causal attribution.

A used 2 calls, 5,191 prompt/89 output tokens, 14.8 s agent and 0.048 s checker. B used
8 calls, 21,284 prompt/236 output tokens, 45.3 s agent and 0.027 s checker. All ten attempts
returned HTTP 200, no retries/errors/unknown prompt-output usage. The configured five-second
pacing contributed 0.967 s inside agent time; per-attempt start timestamps were unavailable
for an independent measured minimum-gap claim. Early give-up prevents speed comparisons.

Fresh remote gates passed: A 285 Rust/95 Python, B 303 Rust/102 Python, zero failures/skips.
An ignored wrapper called unchanged `_run_core` and added existing AW teardown in finally
for both runs; source/core/checker/policy stayed unchanged. A fresh binary SHA-256 was
`590274001a37f8cfb184f259246beccdfc352c988595f822928e5b0dc1deac75`; B was
`e3f5b6e12821c17e299be69c2aa3b3b81ca49be1c265ab65d0849c12e3cff81a`.
Manifest SHA-256: `c4cdf0399197069f699060b0b7d904fb30edd1619a4db219db715adff6d9afce`.

Parent verified all 60 indexed files and independently checked raw outcomes, action sequences,
usage and B's second-tap prompt. Evidence: ignored
`ballistic_runs/history_camera_pair_20261107_b90500a5/`; report SHA-256
`8c4cb3496c8b810bbb1c6fdfa55031ce882fa047a86433d4a6913eeef33ce06f`.
Token-owned release at 11:00:03 UTC and independent checks confirm no lock/drivers and
preservation of the original visible emulator, dirty main, AW, source and binaries.

Decision: park the unmerged history candidate. Neither the decision comparison nor this
execution round supplies evidence to deploy it. No full-suite rerun is justified by this
feature. Continue separately with offline investigation of observation readiness and an
owner-derived numeric-constraint prototype, as proposed in the planning audit; neither is
yet a verified fix. Last complete suite remains **8/20 combined, 11/20 state**.
