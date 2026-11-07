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
| Core 5 (incomplete) | 7 Nov 2026 05:23–05:37 | core | `7a9238e` | **4 / 5 / 2; 9 NOT RUN** | 553.8 s (11 attempts) | — | 113 | 2,427 (112 known-usage attempts) |
| **Core 6** | **7 Nov 2026 08:35–09:04** | **core** | **`05ab6eb`** | **8 / 10 / 2** | **1,258.3 s** | **52.75 s** | **223** | **2,651** |

Core 3 was run on a second API key after the first key's daily quota was used up.
This records the historical run; daily-quota exhaustion must stop a run, without automatic key rotation.

Timing boundaries differ: baseline `total_seconds` includes its checker; core times exclude
setup and checker. Core 3 adds 15.93 s checker time; Core 4 adds 17.40 s. Suite wall time includes
setup and gaps and is not the sum in this table. Errors and early failed stops reduce recorded
time. These runs do **not** establish a speedup or a controlled latency comparison.
Prompt means include only usage-bearing calls; failed-call usage is unknown.

## Core 6 complete — 7 November 2026

**8 PASS / 10 FAIL / 2 ERROR, all 20 attempted. Independent state: 11/20 passed.**
Eight accepted done reports, all supported by the benchmark; **zero false-done acceptance**.
This improves on the previous complete Core 4 result of 4 combined / 9 state, but still falls
short of the original T3A 13/20 and the current minimum target. Core 5 remains an incomplete
round and must not be treated as a full-suite denominator.

Exact pin `05ab6eb57408fbf3951ed6b2906eaab21c010534`, tree
`4a21951b720cae8d53f9783672274d51a83e9eed`, release binary SHA-256
`93085276fa070dae414e441ce0eb3f72a60820c158e14050aa82ec61e2e6a5ae`.
Remote build gates reproduced **270 Rust/UniFFI and 94 Python passes, zero skips**.
Original task order, seed 0, complexity-based step budgets, same Gemini model, temperature 0,
role caps 800/64/400 and five-second pacing were frozen throughout. Confirmations were approved
by the benchmark harness. The original key was used after a separately recorded availability
check; no key rotation, extra probes, outcome reruns, source patches or billing changes.

The optional `core_disable_native_tools=true` request was enabled and recorded. All **223 HTTP
attempts returned 200**; the endpoint accepted requests with `tool_choice: none` and no tools
declaration in this round. That establishes request acceptance here, not causal proof of a score
gain or universal endpoint compatibility. Two terminal non-text replies still failed:
`missing_content`, `finish_reason=other`, no reported tool/function call, zero completion tokens.
The original unrecognized provider reasons were not retained; do not invent their cause or
classify them as retry-eligible. Both errors continued to later tasks as configured.

| Task | Combined | State | Agent s | Checker s | Calls | Finding |
|---|---|---|---:|---:|---:|---|
| ContactsAddContact | PASS | passed | 40.6 | 1.116 | 8 | Agent completion and independent state agree. |
| ClockTimerEntry | FAIL | failed | 47.4 | 0.142 | 10 | Wrong digit/backspace sequence exhausted ten steps; timer value incorrect. |
| ClockStopWatchRunning | PASS | passed | 33.9 | 0.114 | 7 | Agent completion and independent state agree. |
| OpenAppTaskEval | PASS | passed | 12.2 | 0.035 | 3 | Agent completion and independent state agree. |
| SystemWifiTurnOff | PASS | passed | 52.2 | 0.027 | 11 | Agent completion and independent state agree. |
| SystemBluetoothTurnOn | PASS | passed | 56.3 | 0.028 | 11 | Agent completion and independent state agree. |
| SystemBrightnessMax | FAIL | failed | 53.3 | 0.037 | 7 | Repeated reader queries on homepage; stopped before Display/slider. |
| TurnOnWifiAndOpenApp | PASS | passed | 101.8 | 0.106 | 14 | Agent completion and independent state agree. |
| SimpleSmsSend | FAIL | passed | 62.6 | 6.304 | 13 | Sent state passed; three verifier rejections confused disabled Send/composer with visible message body. |
| SimpleSmsReplyMostRecent | FAIL | passed | 80.5 | 6.253 | 16 | Sent state passed; further keyboard/Send attempts hit guarded-repeat stop. |
| SimpleCalendarAddOneEvent | FAIL | failed | 189.1 | 0.184 | 34 | Both start/end remained 13:15; requested 13:00–13:15. No Save before budget exhaustion. |
| SimpleCalendarEventsOnDate | FAIL | failed | 38.5 | 0.0 | 8 | Date selection failed; requested reader summary unsupported for this owner query; no answer. |
| MarkorCreateNote | FAIL | failed | 71.5 | 0.041 | 14 | Created document, then rejected done and malformed back target:null. Exact persisted-file mismatch unproven. |
| MarkorDeleteNote | FAIL | failed | 52.1 | 0.038 | 10 | Reader picked requested row, but planner kept reading; no-progress stop before Delete. |
| MarkorCreateFolder | PASS | passed | 72.2 | 0.034 | 6 | Agent completion and independent state agree. |
| ExpenseAddSingle | ERROR | failed | 68.0 | 0.085 | 10 | HTTP 200 missing_content/finish_reason=other after unsuccessful category reads; provider cause unknown. |
| ExpenseDeleteSingle | ERROR | passed | 42.0 | 0.089 | 6 | Deletion state passed, then HTTP 200 missing_content/finish_reason=other; no accepted done. |
| RecipeAddSingleRecipe | PASS | passed | 108.5 | 0.097 | 20 | Agent completion and independent state agree. |
| FilesDeleteFile | FAIL | failed | 23.7 | 0.032 | 5 | Repeated storage-row reader query; no-progress stop before deletion. |
| CameraTakePhoto | FAIL | failed | 51.9 | 0.051 | 10 | Six repeated Shutter taps with unchanged-screen feedback; checker failed; exact photo count unknown. |

Totals: **223 logical calls / 223 HTTP attempts / zero retries**; 189 planner, 19 reader,
15 verifier, no judge. **591,176 prompt + 8,658 completion tokens**, no unknown usage.
Agent **1,258.3 s**, checker **14.813 s**, task-process wall sum **1,740.560 s**;
**341.924 s** pacing is included in agent time. Median agent task time **52.75 s**.
The pre-run original-key probe (one call, 5 prompt + 2 completion tokens, 0.977 s) is excluded.
Baseline's 1,495.7 s includes checkers, while core timing excludes setup/checker; differing
failure rates, pacing and task generation prevent a controlled speedup claim. Even seed 0 does
not fix MarkorCreateFolder's wall-clock-generated folder name.

Parent independently verified all **70 original and 12 derived artifact hashes**, then
recomputed scores, state results, false-done count, calls, HTTP statuses, times and tokens from
all 20 raw records in ignored `ballistic_runs/core6_05ab6eb/`. Frozen manifest SHA-256:
`8bf55082d414576d4f8410df42010e9c8529136e1e5dd41c12ed56984a2ce82d`.
Supervisor 689783 released its lock at **09:04:55.844067 UTC**; independent release evidence at
**09:07:08.491053 UTC** confirms no supervisor/driver/lock, original visible emulator 305875,
clean validation tree, unchanged binary, and preserved dirty main/AndroidWorld inventories.
Credential scans passed before download. No APK build/install or physical phone access.

Zero false-done acceptance and the SMS/deletion state passes do not establish absence of
duplicate actions, unrelated side effects or prompt-injection resistance. Raw logs retain
those limits: SMS reply retried guarded inputs, and Camera issued six Shutter taps.

Next isolated offline work targets repeated-reader correction within the existing two-planner-
attempt allowance, verifier confusion between readonly items and editors, and whether a safe
structured observation can expose the Markor extension value without exposing arbitrary text.
Timer/calendar arithmetic, content-answer support, camera effect receipts and unrecognized
empty model replies remain open. No further live rerun has begun at this entry.

## Core 5 stopped on daily quota — 7 November 2026

**This is an incomplete round: 11 attempted, 4 PASS / 5 FAIL / 2 ERROR, 9 NOT RUN.**
Independent state: **6 passed / 5 failed**, no checker errors. Zero final false-done reports.
The last completed 20-task result remains Core 4 (4 combined, 9 state); the original target of
at least 13/20 is not achieved. Unrun tasks have no measured result or time.

Frozen commit `7a9238eba3880036dc507238ff2ff33b45c27e16`, binary SHA-256
`c2c73741bcc4b43cfa1134a2f699c3ea30b81f94e674c4956959c0a46bf92fb5`.
Original suite order, seed 0, unchanged complexity-based step budgets, same Gemini model,
planner/reader/verifier caps 800/64/400, five-second shared model pacing. No task reruns.
Remote preflight reproduced **188 Rust/UniFFI and 86 Python passes, zero skips**.
Owner excerpts, optional text-only request control and reader-navigation feedback were added
later and are excluded from this run.

| Task | Result | State | Agent s | Checker s | Calls | Cause / evidence |
|---|---|---|---:|---:|---:|---|
| ContactsAddContact | PASS | passed | 54.7 | 1.096 | 10 | Contact created; agent and checker agree. |
| ClockTimerEntry | FAIL | failed | 48.5 | 0.184 | 10 | Wrong digit/backspace sequence exhausted ten steps; requested timer value not reached. |
| ClockStopWatchRunning | PASS | passed | 23.5 | 0.127 | 5 | Stopwatch running; agent and checker agree. |
| OpenAppTaskEval | ERROR | passed | 22.7 | 0.037 | 5 | Settings opened; later HTTP 200 tool-call response had no text. Terminal response-format error. |
| SystemWifiTurnOff | PASS | passed | 66.5 | 0.027 | 13 | Wi-Fi disabled; agent and checker agree. |
| SystemBluetoothTurnOn | FAIL | failed | 63.3 | 0.036 | 13 | Ignored visible Bluetooth navigation row; queried unrelated hidden rows and hit no-progress guard. |
| SystemBrightnessMax | FAIL | failed | 18.7 | 0.03 | 4 | Display was offscreen; repeated homepage read without scrolling. Never reached slider. |
| TurnOnWifiAndOpenApp | PASS | passed | 48.2 | 0.058 | 10 | Both requested state conditions passed; agent and checker agree. |
| SimpleSmsSend | FAIL | failed | 48.2 | 6.236 | 10 | Unlabeled new-conversation confirmation icon could not be authorized; stopped before composer. |
| SimpleSmsReplyMostRecent | FAIL | passed | 52.8 | 6.256 | 11 | Reply body appeared, but verifier returned unknown; subsequent guarded repeats stopped. Final state passed. |
| SimpleCalendarAddOneEvent | ERROR | failed | 106.7 | 0.093 | 22 | Daily HTTP 429 on planner call 22; unsaved event and wrong start/end time beforehand. No retry. |
| SimpleCalendarEventsOnDate | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| MarkorCreateNote | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| MarkorDeleteNote | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| MarkorCreateFolder | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| ExpenseAddSingle | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| ExpenseDeleteSingle | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| RecipeAddSingleRecipe | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| FilesDeleteFile | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |
| CameraTakePhoto | NOT RUN | not_run | — | — | — | Not attempted after daily-quota stop. |

Totals: **113 logical calls / 113 HTTP attempts / zero retries**: 94 planner, 13 reader,
6 verifier. Known usage **271,786 prompt + 3,942 completion tokens**; one attempt (429) has
unknown usage. Agent time **553.8 s**, checker **14.180 s**, including **194.846 s** of model
pacing within agent time. First-task start to last-task finish was **860.866 s**, including
setup and gaps; build/tests are separate. These boundaries and the incomplete run prevent a
speedup claim against the unseeded T3A baseline.

For only these first 11 tasks, Core 4 had 3 PASS / 6 FAIL / 2 ERROR and 6 state passes;
Core 5 has 4 PASS / 5 FAIL / 2 ERROR and 6 state passes. Wi-Fi cases improved, while Bluetooth
and brightness failed earlier than before. This is a descriptive subset comparison, not a
full-suite recovery or a controlled estimate of individual patch effects.

The terminal Calendar response explicitly records `HTTPError`, HTTP 429 and
`quota_category=daily`. No quota count, limit or retry/reset guidance was supplied. All model
access stopped immediately; no retry, quota probe or key rotation. Earlier OpenApp metadata
records HTTP 200, `missing_content`, `finish_reason=tool_calls`, tool calls present, refusal and
legacy function call absent. No tool payload was executed. That explains this response only;
it cannot retrospectively classify Core 4's missing-content failures.

SMS reply's requested body was visible before the verifier rejected completion, but its
independent state check ran only after the agent ended. It is not proof of delivery timing,
absence of duplicates, unrelated side effects, or injection resistance. Calendar's unsaved
partial state does not predict what an uninterrupted attempt would have done. Both provider
errors remain ERROR; ordinary guarded/identity stops remain FAIL.

Supervisor PID 650968 released its own emulator lock at **05:37:30.497169 UTC**. Independent
process/lock check at **05:38:04.772663 UTC** found no lock or recognized live driver and the
original visible emulator still alive. Remote dirty main, validation source/binary and AndroidWorld
source inventories were preserved; final fetch only updated the remote tracking ref.

Raw records, TSV, diagnoses, frozen manifest, release and preservation evidence are in ignored
`ballistic_runs/core5_7a9238e/`. Parent independently verified **52 original artifact hashes and
9 derived analysis hashes**, plus archive SHA-256
`802a0d106ef581a94a370772994bcddc884b1afead8a0a32a47f3660d5b1ce91`.
A remote credential-value scan found no matches before transfer. No production APK was rebuilt
or installed, no physical phone was used, and no additional device/model call followed the stop.

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
