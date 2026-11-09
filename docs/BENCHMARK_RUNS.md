# Benchmark runs: Ballistic core vs the stock T3A agent on 20 AndroidWorld tasks

Measured facts only. Same 20 tasks (`harness/run_suite.sh`), same emulator (AndroidWorldAvd, API
33, ThinkPad), same model `gemini-flash-lite-latest` (Gemini resolves it to `gemini-3.5-flash-lite`)
except the Haiku and Opus rows, which use `claude-haiku-5-5` or `claude-opus-5-5` through the Anthropic API, core seed 0; the T3A baseline was unseeded. Goals therefore differ. Run artefacts: `ballistic_runs/suite_core_<stamp>/` on the Latitude (git-ignored) and
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
| Haiku 1 (no thinking headroom) | 8 Nov 2026 19:22–19:47 | core, `claude-haiku-5-5` | harness `c75fa07`, core of `a29b611` | 2 / 8 / 10 | 990.7 s | 40.75 s | 262 | 3,360 |
| **Haiku 2** | **8 Nov 2026 19:48–20:17** | **core, `claude-haiku-5-5`** | **harness `b8d1030`, core of `a29b611`** | **6 / 14 / 0** | **1,261.3 s** | **49.55 s** | **341** | **3,358** |
| **Haiku 3 (after fixes)** | **9 Nov 2026 00:03–00:30** | **core, `claude-haiku-5-5`** | **core and harness `b22da1f`** | **9 / 11 / 0** | **977.7 s** | **43.95 s** | **263** | **3,045** |
| **Opus 5.5 (same code as Haiku 3)** | **9 Nov 2026 00:32–01:00** | **core, `claude-opus-5-5`** | **core and harness `b22da1f`** | **12 / 8 / 0** | **1,046.1 s** | **45.0 s** | **256** | **3,117** |

Core 3 was run on a second API key after the first key's daily quota was used up.
This records the historical run; daily-quota exhaustion must stop a run, without automatic key rotation.

Timing boundaries differ: baseline `total_seconds` includes its checker; core times exclude
setup and checker. Core 3 adds 15.93 s checker time; Core 4 adds 17.40 s. Suite wall time includes
setup and gaps and is not the sum in this table. Errors and early failed stops reduce recorded
time. These runs do **not** establish a speedup or a controlled latency comparison.
Prompt means include only usage-bearing calls; failed-call usage is unknown.

## Loop 2 — 9 November 2026 (code changed; live runs not made)

**No live run was made in this loop.** The ThinkPad (`ubuntu-thinkpad-e470`, 100.114.231.86)
answered one SSH command at the start of the loop and then stopped answering: every later SSH
attempt timed out, and `tailscale status` reported it offline ("last seen 2h ago") when this
section was written. No emulator lock was taken, nothing was built on the ThinkPad, and no API
call was made: loop 2 API cost **$0**. The practice-20 + rotation-10 table (PASS / state passed /
false done / cost / tokens, Haiku and Opus) therefore does not exist yet; nothing below is a live
result. Supervisor prepared for the runs (not run): practice 20 + rotation 10 with `TASKS`,
`--provider anthropic --effort low`, seed 0, judge off, `THINKING_HEADROOM` 2048, emulator-5554
only, lock created and removed by the script, `WINDOW=1` when it has to start the emulator.

### What changed (branch `worktree-agent-ae62eb1fed4d009a4`, from master `3670ae2`)

| Commit | Change | Recorded case it addresses |
|---|---|---|
| `e6456ba` | Harness: a sourced `answer.text` (or available owner excerpts joined with `, `) goes to `env.interaction_cache` through AndroidWorld's ANSWER action; PASS counts it only for `InformationRetrieval` tasks; wrong answer = FAIL (`failure_category: answer`) | all 25 answer tasks scored 0 by construction (ANDROIDWORLD_TASKS.md §1) |
| `db74598` | Harness: `dream.py`, offline failure clustering (no model call by default) | proposal C, OPENCLAW_DREAMING.md |
| `0e07397` | Harness: after an acknowledged input, an app tree identical to the previous one is acquired again up to twice, 0.8 s apart | in Haiku 3, 24 of 136 inputs (12 runs) and in Opus 5.5, 21 of 131 inputs (10 runs) were followed by an identical foreground app tree (this count includes inputs that really changed nothing); traces show wasted `wait` steps and the calendar's second "Start time" tap landing on the picker |
| `184d876` | Core: a repeated cycle of three inputs on unchanged screens is warned, then stopped | Opus SimpleCalendarAddOneEvent: Start time, Start time, OK × 11 |
| `88b86e1` | Core: typed postconditions RowsDeleted (named rows gone from the same page) and NoteSaved (Markor editor saved, then the file list shows the exact name) | Opus ExpenseDeleteSingle and MarkorCreateNote (state passed, not credited) |
| `dfc1c68` | Core: Internet page proven while Wi-Fi scans; DocumentsUI roots drawer labels; tap on a checkable chip the request names; long press on a clickable owner-named row; planner rule to `scroll … until` an owner-named item | Haiku SystemWifiTurnOff (opaque switch), Files storage root, Opus/Haiku ExpenseAddSingle (matched chip not tappable), Markor/Files near-name reader picks |
| `a080f1d` | Core: question requests end with exact owner excerpts (question form of the summary channel); calendar list section headers carry a typed day/weekday | SimpleCalendarEventsOnDate (titles are content; date headers hidden) |

Offline verification: core tests 409 → 424 passing (`cargo test`, 0 failed); harness tests 154 → 171
run, OK (7 skipped). The SMS reply row slack committed after b22da1f was checked on the recorded
Opus SimpleSmsReplyMostRecent run with `harness/replay_core.py`: the current binary diverges from
the recording at step 5 by completing (`core checked the result: a new outgoing row …`), where the
recorded run had continued for seven more steps. The new deletion and saved-note checks change the
ask sequence from step 1 (one extra observation after each input), so their recorded runs cannot
be replayed; they are tested on screens extracted from those runs (`tests/rows_and_notes.rs`).

### Dream pass on the existing runs (b22da1f)

`harness/dream.py` on `suite_haiku55_b22da1f_20261109-000304` and `suite_opus55_b22da1f_20261109-003201`
reproduces the totals above (Haiku 3: 9 PASS, state 10, 0 false done, 263 calls, $0.1147;
Opus 5.5: 12 PASS, state 15, 0 false done, 256 calls, $3.7466). Every cluster (failure category ×
final package × activity × last action × readback × outcome kind) holds one task in each suite, so
20 runs show no shared failure signature; by category Haiku 3 has 10 planning and 1 verification
failures, Opus 6 planning and 2 verification. Done but not credited: Haiku 3 SystemBrightnessMax;
Opus ExpenseDeleteSingle, MarkorCreateNote, SimpleSmsReplyMostRecent. False done: none.

### Remaining failures and proposed next fixes (proposals, not measured)

- **Run the loop:** the 30-task Haiku run, at most one Haiku rerun after a dream pass, then one
  Opus run, as specified; nothing above predicts their scores.
- **Markor create** (EDIT): `type` appends, and the new-file extension field held `txttxttxt.md`
  from earlier runs. Proposal: a replace-text Hands operation (`ACTION_SET_TEXT`) with the same
  taint rule; this is an action-schema change and needs the Kotlin mirror.
- **Calendar add** (DATE): the time picker itself is unchanged; the cycle stop and the harness
  re-observation only shorten failing runs. Proposal: typed picker values as done for list headers.
- **Files delete**: the drawer now names "Device storage", but whether a long press selects a
  DocumentsUI row through the harness has not been observed.
- **Rotation EDIT/LP tasks** (MarkorEditNote, MarkorCreateNoteFromClipboard, MarkorMoveNote):
  no new capability; expected to stay failing (judgement).
- **Reader near-name picks**: in both recorded MarkorDeleteNote runs the reader picked
  `2023_03_14_qFzW_polite_wolf.md` for `qFzW_polite_wolf`. Proposal: when the request names the
  item, refuse element reads whose candidates contain no exact match and point to `scroll … until`.

## Haiku 3 (after fixes) and Opus 5.5 (same code as Haiku 3) — 9 November 2026

Both runs used the same binary, built on the ThinkPad from branch `worktree-agent-aa5bd64a2bd8e7a06`
at `b22da1f` (core fixes 1–5: typed postconditions checked after every effect, repeated-input stop,
recorded labels and the time picker note, keypad entry, scroll until, compact schema, overlong
replies invalid, planner budget before history, forgiving parsing with two corrections) with
`rustup run stable cargo build --release --target-dir ~/ballistic_haiku/target`, SHA-256
`ffc45443d29681db32fd22f010b89fe87a13cb95122382d21cfdfa39cc0dc85a`. Harness unchanged from master.
Settings exactly as Haiku 2: `run_suite.sh --agent core --provider anthropic --effort low`, seed 0,
judge off, confirmations approved by the harness, `THINKING_HEADROOM` 2048 (default), core caps 800
planner / 64 reader / 400 verifier; only the model differs between the two. Opus smoke call through
the client before its run: HTTP 200, text `ok`, 24 prompt + 4 output tokens, no thinking block,
`end_turn` (excluded below); its first task's eight calls all ended `end_turn` with no thinking, so
the headroom stayed 2048. Cost on recorded usage with thinking billed as output: Haiku 5.5 $0.10 / M
input, $0.50 / M output; Opus 5.5 $4 / M input, $20 / M output. No prompt caching (cache read and
creation 0); no call over 100K prompt tokens. Fixes committed after `b22da1f` (post-effect feedback for
brightness, the Expense sheet close label, opposite-scroll detection with `until`, the reply row
slack) are **not** in either run.

**Haiku 3: 9 PASS / 11 FAIL / 0 ERROR; independent state 10/20; zero false done** (Haiku 2: 6 / 14 /
0, state 10). **Opus 5.5: 12 PASS / 8 FAIL / 0 ERROR; state 15/20; zero false done.** T3A baseline
13/20. Single seed-0 trials: no model ranking or causal claim follows, and the state passes do not
establish absence of duplicate or unrelated side effects.

| Totals | Haiku 2 | Haiku 3 | Opus 5.5 |
|---|---:|---:|---:|
| Logical calls / HTTP attempts / retries | 341 / 341 / 0 | 263 / 263 / 0 | 256 / 256 / 0 |
| HTTP status | 341 × 200 | 263 × 200 | 256 × 200 |
| Planner / reader / verifier calls | 267 / 46 / 28 | 210 / 45 / 8 | 210 / 31 / 15 |
| Calls stopped at `max_tokens`; text over core cap | 4; 5 | 0; 0 | 0; 0 |
| Prompt tokens (per call) | 1,144,988 (3,358) | 800,832 (3,045) | 797,942 (3,117) |
| Output tokens, of which thinking | 117,860, 76,196 | 69,296, 52,813 | 27,743, 14,503 |
| Cost, input + output | $0.1145 + $0.0589 = **$0.1734** | $0.0801 + $0.0346 = **$0.1147** | $3.1918 + $0.5549 = **$3.7466** |
| Agent seconds (median) | 1,261.3 (49.55) | 977.7 (43.95) | 1,046.1 (45.0) |
| Checker seconds | 15.052 | 15.043 | 15.031 |
| Suite wall (UTC) | 19:48:04–20:17:01 | 00:03:03–00:30:20 | 00:32:01–01:00:32 |

Combined / state per task; steps, agent seconds and model calls are given for the two new runs.

| Task | T3A | Haiku 2 | Haiku 3 | H3 steps / s / calls | Opus 5.5 | Opus steps / s / calls | Cause (Haiku 3; Opus where it differs) |
|---|---|---|---|---|---|---|---|
| ContactsAddContact | PASS | PASS | **PASS** / passed | 8 / 48.6 / 10 | **PASS** / passed | 7 / 38.2 / 8 | Verifier verified |
| ClockTimerEntry | FAIL | FAIL | **PASS** / passed | 5 / 26.4 / 7 | **PASS** / passed | 4 / 21.4 / 5 | Keypad entry: one `type` of the digits (confirmed as planner-made), then verified |
| ClockStopWatchRunning | PASS | PASS | **PASS** / passed | 4 / 22.4 / 5 | **PASS** / passed | 4 / 19.9 / 5 | Verifier verified |
| OpenAppTaskEval | PASS | FAIL / passed | **PASS** / passed | 1 / 7.1 / 1 | **PASS** / passed | 1 / 8.7 / 1 | Typed launch-with-pop-up check after `open_app`: one planner call |
| SystemWifiTurnOff | PASS | PASS | FAIL / failed | 10 / 46.0 / 14 | **PASS** / passed | 7 / 31.0 / 9 | Haiku: the Internet page's Wi-Fi switch is unlabeled while on (page signature not proven, as in Haiku 2); the reader was asked a navigation question and the planner toggled "Turn on Wi-Fi automatically" instead |
| SystemBluetoothTurnOn | FAIL | PASS | FAIL / failed | 10 / 45.9 / 14 | **PASS** / passed | 7 / 35.2 / 8 | Haiku: reached the Bluetooth page but read its labels three times and went back; budget exhausted |
| SystemBrightnessMax | FAIL | FAIL / passed | FAIL / passed | 10 / 42.0 / 12 | **PASS** / passed | 10 / 46.4 / 12 | Haiku: homepage label and `scroll until "Display"` reached the slider at step 5, then four more swipes and a reopened Settings home; never back on Display. Opus went back: typed Brightness level 100% check passed |
| TurnOnWifiAndOpenApp | PASS | PASS | **PASS** / passed | 7 / 32.5 / 9 | **PASS** / passed | 7 / 33.9 / 9 | Verifier verified |
| SimpleSmsSend | PASS | FAIL / passed | **PASS** / passed | 7 / 30.8 / 7 | **PASS** / passed | 6 / 28.7 / 6 | Typed sent-row check on the Send readback |
| SimpleSmsReplyMostRecent | PASS | FAIL / passed | **PASS** / passed | 9 / 37.3 / 13 | FAIL / passed | 12 / 68.5 / 17 | Typed sent-row check passed (Haiku). Opus: same path, but the top row sat at y 299 under a list at 296 and the check required equality; four done claims rejected (slack added after the run, not in this binary) |
| SimpleCalendarAddOneEvent | FAIL | FAIL | FAIL / failed | 22 / 89.2 / 23 | FAIL / failed | 34 / 142.2 / 36 | Haiku: the end-time picker alternation was warned once, then it gave up at step 22 (was 34 steps). Opus: a three-input cycle (Start time, Start time, OK) that the two-input check does not catch; budget exhausted |
| SimpleCalendarEventsOnDate | FAIL | FAIL | FAIL / failed | 10 / 60.0 / 17 | FAIL / failed | 10 / 55.6 / 16 | Date headers are content; reads and scrolls used the 10 steps (Opus found the section at step 9) |
| MarkorCreateNote | FAIL | FAIL | FAIL / failed | 11 / 56.7 / 15 | FAIL / passed | 16 / 92.7 / 22 | `type` appends: the extension field already held text from earlier runs (`txttxt…`); Haiku gave up. Opus chose the type from the spinner, saved and the state passed; the verifier would not accept a typed editor as saved |
| MarkorDeleteNote | PASS | FAIL | FAIL / failed | 10 / 47.5 / 13 | FAIL / failed | 10 / 58.7 / 15 | Deleting needs a long press on a reader-picked row, which stays tap-only by policy; both opened the note and searched for a delete action |
| MarkorCreateFolder | PASS | PASS | **PASS** / passed | 7 / 38.4 / 10 | **PASS** / passed | 6 / 43.6 / 8 | Verifier verified |
| ExpenseAddSingle | FAIL | FAIL | FAIL / failed | 12 / 59.8 / 19 | FAIL / failed | 12 / 67.5 / 18 | `scroll until "Transportation"` showed the chip as `(matches request)`, but a matched chip is checkable, so it is not tappable without a reader pick, and the reader was asked about stale cN; budget exhausted |
| ExpenseDeleteSingle | PASS | FAIL | FAIL / failed | 10 / 41.9 / 12 | FAIL / passed | 9 / 61.7 / 16 | Haiku tapped the sheet's close control, labeled "Close delete confirmation" (guarded by the word "delete"), not CONFIRM (relabeled after the run). Opus confirmed (state passed) but found no evidence the row was gone and gave up |
| RecipeAddSingleRecipe | PASS | FAIL | FAIL / failed | 24 / 129.9 / 28 | **PASS** / passed | 11 / 62.4 / 12 | Haiku: all fields typed, then up/down scrolls with a different `until` each time, which the repetition check treated as different inputs (fixed after the run) |
| FilesDeleteFile | PASS | FAIL | FAIL / failed | 22 / 97.4 / 32 | FAIL / failed | 22 / 113.0 / 31 | The storage root is `sdk_gphone64_x86_64`, not the requested `sdk_gphone_x86_64`, so it never matches; drawer roots stay content and reads did not find it. Opus searched and hit a guarded control, budget exhausted |
| CameraTakePhoto | PASS | FAIL | **PASS** / passed | 2 / 17.9 / 2 | **PASS** / passed | 2 / 16.8 / 2 | Typed new-thumbnail check after one shutter press: no second photo |

Against Haiku 2, Haiku 3 gains ClockTimerEntry, OpenAppTaskEval, SimpleSmsSend,
SimpleSmsReplyMostRecent and CameraTakePhoto and loses SystemWifiTurnOff and SystemBluetoothTurnOn
(both opaque-page navigation, not new rejections). Of its 11 failures one has state passed
(SystemBrightnessMax); the others are navigation, entry or policy limits listed above. Opus on the
same code passes 12: 9 of T3A's 13 (it misses SimpleSmsReplyMostRecent, MarkorDeleteNote,
ExpenseDeleteSingle and FilesDeleteFile) plus ClockTimerEntry, SystemBluetoothTurnOn and
SystemBrightnessMax, which T3A failed; three Opus failures have state passed (SMS reply, Markor
create, Expense delete). Typed checks completed 4 Haiku 3 runs (launch, both SMS, camera) and 4 Opus
runs (launch, brightness, SMS send, camera) with `code_validated_*` evidence and no planner `done`
needed; no run reported done with state failed.

Evidence (git-ignored, Latitude): `ballistic_runs/suite_haiku55_b22da1f_20261109-000304/` and
`ballistic_runs/suite_opus55_b22da1f_20261109-003201/` (TSV, 20 logs, 20 JSONs, supervisor script and
status, suite output); originals in `~/ballistic_runs/` on the ThinkPad. Both supervisors released the
emulator lock (00:30:20 and 01:00:32 UTC). The copied files contain no API key.

## Core with Claude Haiku 5.5 — 8 November 2026

Model `claude-haiku-5-5` through the official `anthropic` Python SDK 2.6.0 (AndroidWorld venv on
the ThinkPad), `--provider anthropic --effort low`, no `thinking`, `temperature`, `top_p` or
`top_k` field (adaptive thinking is the model's default). Same 20 tasks in suite order, seed 0,
judge off, confirmations approved by the harness, complexity-based step budgets, core role caps
800 planner / 64 reader / 400 verifier, no pacing. Core built from master `a29b611` with
`rustup run stable cargo build --release`; both runs used binary SHA-256
`2f174280e7c3d83373ce5904e430dcef0bfffbccb674ceb32f97b036dd9d948e`. Pre-run smoke call through
the client: HTTP 200, text `ok`, 21 prompt + 4 output tokens (excluded below). Cost uses the
published rates for prompts up to 100K tokens, **$0.10 / M input and $0.50 / M output**, on the
recorded usage; thinking is billed as output. No call exceeded 100K prompt tokens, no prompt
caching was used (cache read/creation 0).

**Haiku 2 (harness `b8d1030`, thinking headroom 2048): 6 PASS / 14 FAIL / 0 ERROR; independent
state 10/20; zero false done.** Below Core 6 (8 combined / 11 state) and T3A 13/20.

**Haiku 1 (harness `c75fa07`, run by the earlier agent's supervisor, no headroom): 2 PASS / 8 FAIL
/ 10 ERROR; state 8/20; zero false done.** All ten errors are a final call that ended at
`stop_reason: max_tokens` with only a thinking block (`missing_content`): 5 reader (cap 64),
4 verifier (cap 400), 1 planner (cap 800). 22 of its 262 calls stopped at `max_tokens`; the 11
verifier calls among them spent 304–400 of 400 tokens thinking. Four ERRORs (ContactsAddContact,
ClockStopWatchRunning, SimpleSmsSend, MarkorCreateFolder) had state passed. Commit `b8d1030` then
sent core's cap plus 2048 tokens and recorded both (`max_tokens`, `max_tokens_sent`).

| Totals | Haiku 1 | Haiku 2 |
|---|---:|---:|
| Logical calls / HTTP attempts / retries | 262 / 262 / 0 | 341 / 341 / 0 |
| HTTP status | 262 × 200 | 341 × 200 |
| Planner / reader / verifier calls | 210 / 31 / 21 | 267 / 46 / 28 |
| Calls stopped at `max_tokens` | 22 | 4 (planner, all 2,848 tokens) |
| Prompt tokens (per call) | 880,438 (3,360) | 1,144,988 (3,358) |
| Output tokens, of which thinking | 79,126, 56,715 | 117,860, 76,196 |
| Cost, input + output | $0.0880 + $0.0396 = **$0.1276** | $0.1145 + $0.0589 = **$0.1734** |
| Agent seconds (median) | 990.7 (40.75) | 1,261.3 (49.55) |
| Checker seconds | 15.800 | 15.052 |
| Suite wall (UTC) | 19:22:40–19:47:15 | 19:48:04–20:17:01 |

No 429, 5xx, refusal, billing or unknown-usage attempt in either run. In Haiku 2 four planner
replies (SimpleSmsSend, SimpleSmsReplyMostRecent, SimpleCalendarAddOneEvent, ExpenseAddSingle)
used the whole 2,848 tokens with 225–488 thinking, so 2,360–2,623 visible text tokens against
core's 800; one reader reply was 65 text tokens against 64. Likely runaway text; the
replies were not retained beyond core's handling. Core timing excludes setup and checker, as above.

| Task | T3A | Core 6 | Haiku 1 | Haiku 2 combined / state | Agent s | Calls | Haiku 2 cause (from the run JSON) |
|---|---|---|---|---|---:|---:|---|
| ContactsAddContact | PASS | PASS | ERROR | **PASS** / passed | 53.2 | 14 | Agent done and checker agree |
| ClockTimerEntry | FAIL | FAIL | FAIL | FAIL / failed | 43.6 | 11 | Nine taps on three targets (e8, e4, e15); budget exhausted, timer not set (likely digit entry) |
| ClockStopWatchRunning | PASS | PASS | ERROR | **PASS** / passed | 41.0 | 10 | Agent done and checker agree |
| OpenAppTaskEval | PASS | PASS | FAIL | FAIL / passed | 53.1 | 19 | Settings open at step 1; nine done claims rejected "citation has no readable evidence"; budget exhausted |
| SystemWifiTurnOff | PASS | PASS | PASS | **PASS** / passed | 44.2 | 14 | Agent done and checker agree |
| SystemBluetoothTurnOn | FAIL | PASS | ERROR | **PASS** / passed | 44.8 | 13 | Agent done and checker agree |
| SystemBrightnessMax | FAIL | FAIL | FAIL | FAIL / passed | 43.1 | 15 | Reached the slider through settings search; the step-10 swipe likely met the goal; budget gone before done |
| TurnOnWifiAndOpenApp | PASS | PASS | PASS | **PASS** / passed | 47.6 | 14 | Agent done and checker agree |
| SimpleSmsSend | PASS | FAIL | ERROR | FAIL / passed | 76.4 | 19 | Sent at step 6; five done claims rejected "current screen does not establish every requested postcondition" |
| SimpleSmsReplyMostRecent | PASS | FAIL | ERROR | FAIL / passed | 79.4 | 24 | Five reads to find the newest thread, replied at step 9; two done claims rejected as above; budget exhausted |
| SimpleCalendarAddOneEvent | FAIL | FAIL | FAIL | FAIL / failed | 168.8 | 36 | Title and description typed; from step 16, 18 taps alternating e29/e10 (likely the time picker); never saved, 34 steps used |
| SimpleCalendarEventsOnDate | FAIL | FAIL | ERROR | FAIL / failed | 42.4 | 16 | Six reader date queries and scrolls; never reached 27 Oct 2023 or answered |
| MarkorCreateNote | FAIL | FAIL | ERROR | FAIL / failed | 68.6 | 19 | Name and `txt` typed, five reads of the name/extension fields (three deduplicated), body typed, then back; no done |
| MarkorDeleteNote | PASS | FAIL | ERROR | FAIL / failed | 37.1 | 14 | Found and opened the note, read its title, backed out; no delete before budget |
| MarkorCreateFolder | PASS | PASS | ERROR | **PASS** / passed | 46.9 | 11 | Agent done and checker agree |
| ExpenseAddSingle | FAIL | ERROR | FAIL | FAIL / failed | 51.5 | 11 | Reader found no Transportation among five category rows; scrolls; planner gave up |
| ExpenseDeleteSingle | PASS | ERROR | FAIL | FAIL / failed | 31.7 | 10 | Delete dialog opened at step 4; reader could not name the item; planner gave up without confirming |
| RecipeAddSingleRecipe | PASS | PASS | FAIL | FAIL / failed | 133.6 | 27 | Fields typed, then 14 scrolls, mostly alternating down/up; reader found no Servings/Directions field; budget exhausted |
| FilesDeleteFile | PASS | FAIL | ERROR | FAIL / failed | 95.3 | 32 | Never reached the Music folder: scrolls and reader "none"; budget exhausted |
| CameraTakePhoto | PASS | FAIL | FAIL | FAIL / failed | 59.0 | 12 | Shutter tapped at step 3, done rejected, more taps; checker failed (photo count unknown) |

Haiku 2 passes 5 of T3A's 13 passes plus SystemBluetoothTurnOn, which T3A failed. Of the 14
failures, 4 have state passed but no accepted done (OpenAppTaskEval, SystemBrightnessMax and the two
SMS tasks): done rejected by core's evidence checks or budget exhausted after the effect. The other 10
are navigation, entry or effect failures that exhausted the step budget or gave up. Compared with
Core 6, Haiku 2 passes a subset (6 of Core 6's 8) and misses OpenAppTaskEval and
RecipeAddSingleRecipe; both runs are single
seed-0 trials, so no model ranking or causal claim follows. The state passes do not establish
absence of duplicate or unrelated side effects.

Evidence (git-ignored, Latitude): `ballistic_runs/suite_haiku55_20261108-194804/` (Haiku 2: TSV,
20 logs, 20 JSONs, supervisor script/status, suite output) and
`ballistic_runs/suite_haiku55_c75fa07_20261108-192240/` (Haiku 1); originals in `~/ballistic_runs/`
on the ThinkPad. Both supervisors released the emulator lock (19:47:15 and 20:17:01 UTC). The
copied files contain no API key.

## Core 8 targeted — 7 November 2026 (three tasks, not a full suite)

Exact pin `77bc36b15506b0e0b73cc6e59097c6e684d05073`, binary SHA-256
`36d92d230e82b5b3604100519d9ddbcdb5b11119d5f772b883ebc6b2e2e3141d`.
Fresh remote gates296Rust/UniFFI +118Python, zero failures/skips. Fixed order below,
seed0, once each, unchanged ten-step budgets, model/role caps/retries/confirmation,
five-second pacing and original key. **1 PASS / 1 FAIL / 1 ERROR; state1/3**.
Same Core6 cases had2PASS/1FAIL,state2/3; unequal observations/order and missing normal
app snapshots prevent causal attribution. Last full score stays8/20combined,11/20state.

| Task | Combined / state | Agent s | Checker s | Calls | Trace-backed finding |
|---|---|---:|---:|---:|---|
| MarkorDeleteNote | FAIL / failed | 93.4 | 0.058 | 19 | ReaderTapOnly correction followed by permitted tap; opened note, then More options. Reader returned none on menu; final guessed obsolete e7 rejected. No deletion or Done. |
| ClockStopWatchRunning | PASS / passed | 39.2 | 0.107 | 7 | Started, paused unnecessarily, restarted; Done verified against Pause control and state checker passed. Seven successful host observations. |
| SystemWifiTurnOff | ERROR / failed | 10.7 | 0.031 | 0 | Initial observation failed foreground_not_settled before planning or settings navigation. Rejected acquisition samples were not recorded, so cause remains unresolved. |

The Markor correction was exposed once and immediately followed by tap e13. This is an
observed legal choice after feedback, not a paired counterfactual or task-success gain.
Exact intended-note identification remains unestablished. Final menu e7 was an opaque
SystemUI image, not the earlier labeled More options. No long-press/delete dispatched.
Wi-Fi may expose a compatibility cost of narrower settling, but retained data cannot
separate that from other tree/acquisition problems. No rollback or causal claim from this
single trace. Stopwatch compatibility in this trace does not establish broad compatibility.

Totals26logical/26HTTP200 attempts, zero retries/errors/unknown token usage;58,806prompt
+922output tokens.19planner/6reader/1verifier calls;143.3sagent+0.196schecker;64.883spacing
already inside agent time. One accepted Done, zero final false Done; no side-effect/safety
claim. Parent verified all73indexed files and recomputed raw outcomes/counts/tokens.
Evidence: ignored `ballistic_runs/core8_targeted_77bc36b/`, frozen manifest SHA
`7ffc4489a014ae928662fe1f8cbccde4b27d1dc9a393c6675da507c23b3d2f21`.
Release11:45:55UTC, independently verified11:46:28 with lock/drivers absent and original
visible emulator, dirty main, source inventories and binaries preserved. No further calls.

Next bounded diagnosis: collect acquisition sequences with no model calls, then replay old
and new settling rules on the same sequences. This investigates compatibility, not a new
task score. Do not replace missing evidence with a special-case delay or expected control.

## Core 7 targeted — 7 November 2026 (six tasks, not a full suite)

Exact pin `4de7adebb7bf53e5ea28de5dad0d01bf18ef780e`, tree
`bf78db44331a437bcce239866764664bc21e0005`, release binary SHA-256
`590274001a37f8cfb184f259246beccdfc352c988595f822928e5b0dc1deac75`.
The six-task manifest was frozen before live work; no reruns or midrun edits. Fresh build gates:
**285 Rust/UniFFI and 95 Python passed, zero skips**. Same original key, Gemini model, seed 0,
role/step/correction limits, confirmation policy, five-second pacing and text-only request flag
as Core6. Targeted order differs from the full suite. Parent independently verified all
**51 original and 23 derived artifact hashes** and recomputed scores, usage and timings.

**0 PASS / 5 FAIL / 1 ERROR; state 2/6; no accepted Done.** The corresponding Core6 cases had
1 PASS / 5 FAIL, state 3/6. This round establishes no recovery. It is not a new 20-task score
and does not replace the last complete result of 8 combined / 11 state. Same goals and budgets
are not a controlled causal comparison: initial observations differ and identical reader inputs
can produce different selections.

| Task | Core6 combined / state | Core7 combined / state | Agent s, old → new | Calls, old → new | Trace-backed finding |
|---|---|---|---:|---:|---|
| OpenAppTaskEval | PASS / passed | FAIL / passed | 12.2 → 82.4 | 3 → 16 | Opened Settings, pursued permissions, never proposed Done. Neither changed verifier nor reader-correction path ran. |
| SystemBrightnessMax | FAIL / failed | ERROR / failed | 53.3 → 50.9 | 7 → 9 | New reader correction exercised once; planner HTTP503 retried, then HTTP200 missing-content error. Never reached Display. |
| FilesDeleteFile | FAIL / failed | FAIL / failed | 23.7 → 129.2 | 5 → 24 | More scrolling, reopening and reads, then gave up. No new reader correction; one recovered reader503. |
| SimpleSmsSend | FAIL / passed | FAIL / passed | 62.6 → 86.9 | 13 → 14 | Three verifier calls received the revised prompt yet still inferred unsent from disabled Send. Missing status can independently justify unknown. |
| SimpleSmsReplyMostRecent | FAIL / passed | FAIL / failed | 80.5 → 48.1 | 16 → 10 | Identical reader messages selected e8 instead of previous e7; entered the other conversation, then hit guarded-repeat stop. No verifier call. |
| MarkorDeleteNote | FAIL / failed | FAIL / failed | 52.1 → 48.1 | 10 → 10 | Long-press rejection consumed correction slot; fourth read stopped at unchanged cap. |

**83 logical calls / 85 HTTP attempts / 2 retries**: 83 HTTP200, two HTTP503, no429/400.
Planner 60 calls, reader 20, verifier 3; ten ordinary correction calls, exactly one new
reader-no-progress correction. The terminal brightness fault was a `ModelResponseError`
(`missing_content`, `finish_reason=other`), not a proven emulator/transport outage; the current
harness labels it with the broad environment category. Its unretained provider cause is unknown.
The two inherited 503 retries had unknown usage. Known totals: **194,327 prompt + 3,014 output
tokens**, two unknown-usage attempts. Agent time **445.6 s** versus selected Core6 **284.4 s**;
checker **12.677 s**; **168.980 s** pacing is already included in agent time. Sum task process
wall 618.205 s, supervisor 809.231 s including build/test gate and inter-task gaps.

The revised verifier instruction did not correct the targeted faulty inference in this trial;
this does not prove accepting Done would be justified. The reader correction produced no usable
corrected action, so its live utility remains unmeasured. Keep these results separate from offline
regression tests. No delivery, absence-of-duplicates, exact photo count or injection-safety claim.
Review substantive observation/history improvements before another full suite; do not spend
another round merely hoping for different outcomes or weaken success criteria to raise the score.

Token-owned release at **09:45:43.120536 UTC**, independent process/lock check at **09:45:57.035989**:
no supervisor or drivers, no emulator lock; original PID305875 and visible AndroidWorldAvd:5554
window intact. Dirty main, AW/wrapper inventories, pinned tree and binary preserved. Remote
credential scan passed before transfer. Evidence: ignored `ballistic_runs/core7_targeted_4de7ade/`,
including `report.txt`, `per_task.tsv`, `comparison.json`, `trace_evidence.json`,
`first_planner_prompt_diffs.txt`, `release_confirmation.json`, original JSON/logs and hash indexes.

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
