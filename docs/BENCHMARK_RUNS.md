# Benchmark runs: Ballistic core vs the stock T3A agent on 20 AndroidWorld tasks

Measured facts only. Same 20 tasks (`harness/run_suite.sh`), same emulator (AndroidWorldAvd, API
33, ThinkPad), same model `gemini-flash-lite-latest` (Gemini resolves it to `gemini-3.5-flash-lite`),
core seed 0; the T3A baseline was unseeded. Goals therefore differ. Run artefacts: `ballistic_runs/suite_core_<stamp>/` on the Latitude (git-ignored) and
`~/ballistic_runs/` on the ThinkPad.

## Runs

| Run | Date (UTC) | Agent | Code | PASS / FAIL / ERROR | Recorded task time | Median task | Model calls | Prompt tokens per call |
|---|---|---|---|---|---|---|---|---|
| Baseline | 6 Nov 2026 18:14–18:47 | T3A (stock) | step 1 | **13** / 7 / 0 | 1,496 s | 66 s | 281 | 10.7k |
| Core 1 | 6 Nov 2026 23:49 | core | `a13f59e` | 3 / 9 / 8 | 951 s (19 JSONs) | 38 s | — | — |
| Core 2 | 7 Nov 2026 00:54 and 01:05 | core | `deeb3b0` | aborted: ThinkPad network outage, then Gemini daily quota (500/day free tier) | | | | |
| Core 3 | 7 Nov 2026 01:41–01:59 | core | `1331cde` (core code of `deeb3b0`) | 3 / 13 / 4 | 558.9 s | 19.5 s | 113 | 2,345 |
| **Core 4** | **7 Nov 2026 02:32–03:02** | **core** | **`8098265`** | **4 / 9 / 7** | **1,039.5 s** | **53.9 s** | **197** | **2,158** |

Core 3 was run on a second API key after the first key's daily quota was used up.
This records the historical run; daily-quota exhaustion must stop a run, without automatic key rotation.

Timing boundaries differ: baseline `total_seconds` includes its checker; core times exclude
setup and checker. Core 3 adds 15.93 s checker time; Core 4 adds 17.40 s. Suite wall time includes
setup and gaps and is not the sum in this table. Errors and early failed stops reduce recorded
time. These runs do **not** establish a speedup or a controlled latency comparison.
Prompt means include only usage-bearing calls; failed-call usage is unknown.

## Core 3, per task, against the baseline

| Task | T3A | T3A s | Core 3 | Steps | s | Calls | Category | Cause (from the run JSON) |
|---|---|---|---|---|---|---|---|---|
| ContactsAddContact | PASS | 57 | **PASS** | 7 | 35 | 8 | | |
| ClockTimerEntry | FAIL | 64 | FAIL | 7 | 27 | 7 | verification | stopped after a guarded input: "effect unverified; reliable readback or idempotency unavailable" |
| ClockStopWatchRunning | PASS | 21 | ERROR | 2 | 12 | 3 | environment | Gemini HTTP 503 |
| OpenAppTaskEval | PASS | 10 | **PASS** | 2 | 10 | 3 | | |
| SystemWifiTurnOff | PASS | 74 | FAIL | 3 | 25 | 3 | planning | |
| SystemBluetoothTurnOn | FAIL | 102 | FAIL | 10 | 52 | 14 | planning | |
| SystemBrightnessMax | FAIL | 70 | FAIL | 5 | 21 | 6 | planning | |
| TurnOnWifiAndOpenApp | PASS | 69 | FAIL | 2 | 17 | 4 | target_resolution | "e3 has no identified control or matched item" (anonymous Quick Settings tile) |
| SimpleSmsSend | PASS | 75 | FAIL | 3 | 13 | 3 | verification | stopped after a guarded input (Send) with the effect unverified |
| SimpleSmsReplyMostRecent | PASS | 47 | FAIL | 1 | 12 | 3 | target_resolution | "e8 has no identified control or matched item" (conversation row is content, not matched by the request) |
| SimpleCalendarAddOneEvent | FAIL | 90 | ERROR | 0 | 6 | 0 | environment | ObservationError: foreground_not_settled |
| SimpleCalendarEventsOnDate | FAIL | 172 | FAIL | 6 | 41 | 7 | planning | |
| MarkorCreateNote | FAIL | 48 | FAIL | 5 | 34 | 8 | target_resolution | "e8 has no identified control or matched item" |
| MarkorDeleteNote | PASS | 191 | ERROR | 1 | 12 | 2 | environment | Gemini HTTP 503 |
| MarkorCreateFolder | PASS | 29 | FAIL* | 5 | 33 | 5 | verification | *the checker says the folder exists; the agent stopped after the guarded input without saying done |
| ExpenseAddSingle | FAIL | 82 | FAIL | 1 | 12 | 3 | target_resolution | "e19 has no identified control or matched item" |
| ExpenseDeleteSingle | PASS | 36 | FAIL | 3 | 18 | 4 | planning | |
| RecipeAddSingleRecipe | PASS | 171 | **PASS** | 16 | 100 | 19 | | |
| FilesDeleteFile | PASS | 64 | ERROR | 0 | 6 | 0 | environment | ObservationError: foreground_not_settled |
| CameraTakePhoto | PASS | 25 | FAIL | 10 | 74 | 11 | planning | |

Benchmark checker view (independent of what the agent claimed): 4 passed (the 3 PASS plus
MarkorCreateFolder), 16 failed. False "done": **0** (the baseline had 3).

## Core 4 completed, compared with Core 3 and T3A

Source: remote `~/ballistic_runs/suite_core_20261107-023245.tsv`, commit `8098265`.
All **20 JSONs and the TSV** are collected in ignored local
`ballistic_runs/core4_20261107/`. The final task record is stamped 03:02:44 UTC.
Classification uses process/report errors first, then requires both agent done and independent
AndroidWorld success for PASS. A state pass alone is insufficient.

| Task | T3A result / s | Core 3 result / s | Core 4 result / s | Core 4 state | Core 4 cause |
|---|---|---|---|---|---|
| ContactsAddContact | PASS / 57.2 | PASS / 35.1 | PASS / 66.9 | passed | Contact created; checker and agent agree |
| ClockTimerEntry | FAIL / 64.3 | FAIL / 26.5 | FAIL / 54.2 | failed | Planning: invalid `tap` argument `submit` |
| ClockStopWatchRunning | PASS / 21.0 | ERROR / 12.3 | PASS / 33.8 | passed | Stopwatch running; checker and agent agree |
| OpenAppTaskEval | PASS / 10.1 | PASS / 9.5 | PASS / 16.6 | passed | Settings opened |
| SystemWifiTurnOff | PASS / 73.6 | FAIL / 24.5 | FAIL / 94.7 | failed | False done: unrelated off switch/summaries accepted as Wi-Fi evidence after seven reader-none steps |
| SystemBluetoothTurnOn | FAIL / 101.9 | FAIL / 52.0 | FAIL / 76.3 | passed | Repeated reader-none calls; step budget exhausted, no done |
| SystemBrightnessMax | FAIL / 70.2 | FAIL / 21.4 | FAIL / 69.2 | passed | Slider swipes reached goal; step budget exhausted, no done |
| TurnOnWifiAndOpenApp | PASS / 68.5 | FAIL / 17.1 | ERROR / 18.9 | failed | Host `KeyError: 'content'` on HTTP 200 model response |
| SimpleSmsSend | PASS / 75.3 | FAIL / 13.3 | ERROR / 97.5 | failed | HTTP 429 after repeated reader-none calls; limit type unrecorded |
| SimpleSmsReplyMostRecent | PASS / 47.3 | FAIL / 12.0 | FAIL / 40.5 | passed | Sent; readback saw emptied field; planner gave up without delivery proof |
| SimpleCalendarAddOneEvent | FAIL / 90.2 | ERROR / 5.7 | FAIL / 71.1 | failed | False done: model accepted event creation; exact checker mismatch unresolved |
| SimpleCalendarEventsOnDate | FAIL / 171.7 | FAIL / 41.3 | ERROR / 11.4 | failed | Host `KeyError: 'content'` on HTTP 200 model response |
| MarkorCreateNote | FAIL / 48.0 | FAIL / 33.7 | FAIL / 76.5 | failed | False done: typed basename without requested `.txt`; exact saved path/content mismatch not independently established |
| MarkorDeleteNote | PASS / 190.9 | ERROR / 12.1 | ERROR / 13.1 | failed | Host `KeyError: 'content'` on HTTP 200 model response |
| MarkorCreateFolder | PASS / 28.6 | FAIL / 33.0 | FAIL / 75.2 | passed | Folder exists; repeated done claims rejected by verifier; budget exhausted |
| ExpenseAddSingle | FAIL / 81.8 | FAIL / 11.6 | ERROR / 18.5 | failed | Host `KeyError: 'content'` on HTTP 200 model response |
| ExpenseDeleteSingle | PASS / 35.5 | FAIL / 17.6 | ERROR / 37.3 | passed | Deletion passed checker, then host `KeyError: 'content'` |
| RecipeAddSingleRecipe | PASS / 171.1 | PASS / 99.9 | PASS / 94.7 | passed | Recipe created; checker and agent agree |
| FilesDeleteFile | PASS / 63.9 | ERROR / 5.9 | ERROR / 19.5 | failed | Host `KeyError: 'content'` on HTTP 200 model response |
| CameraTakePhoto | PASS / 24.6 | FAIL / 74.4 | FAIL / 53.6 | failed | Taps did not establish capture; verifier rejected completion; planner gave up |

Core 4: **4 PASS, 9 FAIL, 7 ERROR**; independent state **9 passed / 11 failed**.
False done: **3**, versus Core 3's **0** and baseline's **3**. These are final accepted done
reports, not intermediate rejected claims. The Wi-Fi trace ends on Network & internet with an
unchecked Airplane mode switch and unrelated “Off” summaries; it does not establish Wi-Fi off.
A state pass does not prove absence of unrelated side effects.

Errors: six response-shape failures (`KeyError: 'content'`, HTTP 200), one HTTP 429.
The old trace does not establish whether that 429 was daily quota or rate limiting. These are
not seven planning failures, and there is no evidence here of an emulator crash or setup failure.
Nine non-error failures comprise five planning/budget/give-up outcomes and four verification
outcomes (the three false dones and MarkorCreateFolder).

Model telemetry: **197 logical calls = 154 planner + 29 reader + 14 verifier**; 190 usage-bearing
successes, seven final errors. Reinspection on 7 November found one recorded HTTP 503 retry in
RecipeAddSingleRecipe, so the client made **198 recorded attempts**, with eight attempts lacking
usage counters (the retry plus seven final errors). No token usage can be inferred for those. Recorded usage is **410,050 prompt + 6,691 completion tokens**, mean
**2,158 prompt tokens** per usage-bearing call. Four harness confirmations were automatically
approved; this does not test the app's biometric gate.

Core 4 improves combined completion by one task over Core 3 (4 versus 3), while state passes
rise from 4 to 9. It uses more calls (197 versus 113) and more recorded agent time (1,039.5 versus
558.9 s). T3A remains 13/20 with 281 calls and a 1,495.7 s recorded total including checker.
Lower prompt tokens than T3A's 10,707 per call do not establish faster successful task completion:
workloads differ, the baseline was unseeded, and several core errors stopped tasks early.

The parent has since integrated typed missing-content errors, sanitized quota classification,
reader-loop guards and anonymous-switch verification guards. **This table predates those fixes;
no rerun score is claimed.** Next evidence needed is a completed rerun with response failures
separated from planning, exact event/file postconditions, and the live injection suite. Stop on
daily quota; do not automatically rotate keys.

## Caption validation, 7 November 2026 (targeted, not a full suite)

Pinned code `96d9c94`, same Gemini flash-lite, ordinary task step limits and role token caps.
The manifest froze these three cases before running. A five-second minimum interval between
model requests was enabled; recorded agent times include its waits. All 18 downloaded remote
artifacts passed a parent SHA-256 check in `ballistic_runs/captions_96d9c94/`.

| Task | Seed | Combined | Independent state | Agent seconds | Model calls | Prompt / output tokens |
|---|---:|---|---|---:|---:|---:|
| MarkorCreateFolder | 0 | FAIL | failed | 68.0 | 11 | 29,012 / 327 |
| MarkorCreateFolder | 137 | PASS | passed | 64.0 | 12 | 30,264 / 506 |
| ContactsAddContact | 137 | PASS | passed | 59.1 | 10 | 23,054 / 445 |

**2 PASS, 1 FAIL, 0 ERROR; state 2/3; zero final false-done reports.** All 33 HTTP attempts
succeeded without retry or quota error. Total agent time 191.1 s includes 25.511 s pacing;
checker time 1.196 s, complete process wall time 265.105 s. Recorded usage totals 82,330 prompt
and 1,278 output tokens. The empty-response recovery path was not exercised.

The failed seed-0 folder task repeatedly treated FOLDER as a mode selector and never typed a
name. The seed-137 task recovered, created the folder, then used a reader pick and the opened
folder title to finish. Its dated browser caption remained hidden: this success does not prove
the earlier caption projection supports optional date descriptions. The Contact task saved once;
an initial mixed opaque/readable citation was rejected, then a name-only citation was accepted.
The independent checker supplies stronger all-field evidence than that final model assessment.

Core 4's same-seed folder task had state success but no verified completion (75.2 s, 16 calls);
this run has neither. **No same-seed improvement is established.** Timestamped folder names
also differ between runs. These varied development cases cannot replace the full 20-task score,
which remains 4/20 combined and 9/20 state. This run excludes later launch-readback, planner,
file-form, calendar and Settings changes. Emulator release was independently confirmed before
the subsequent observation-only Settings capture.
