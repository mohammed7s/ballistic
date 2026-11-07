# History decision comparison — predeclared 7 November 2026

Status: **prepared offline, not run**. The history revision is still under development and
review. This document records the selected cases before any candidate model decisions are seen.
The comparison will make no device calls and execute no generated actions.

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
