# Benchmark runs: Ballistic core vs the stock T3A agent on 20 AndroidWorld tasks

Measured facts only. Same 20 tasks (`harness/run_suite.sh`), same emulator (AndroidWorldAvd, API
33, ThinkPad), same model `gemini-flash-lite-latest` (Gemini resolves it to `gemini-3.5-flash-lite`),
seed 0. Run artefacts: `ballistic_runs/suite_core_<stamp>/` on the Latitude (git-ignored) and
`~/ballistic_runs/` on the ThinkPad.

## Runs

| Run | Date (UTC) | Agent | Code | PASS / FAIL / ERROR | Agent time | Median task | Model calls | Prompt tokens per call |
|---|---|---|---|---|---|---|---|---|
| Baseline | 6 Nov 2026 18:14–18:47 | T3A (stock) | step 1 | **13** / 7 / 0 | 1,496 s | 66 s | 281 | 10.7k |
| Core 1 | 6 Nov 2026 23:49 | core | `a13f59e` | 3 / 9 / 8 | 951 s (19 JSONs) | 38 s | — | — |
| Core 2 | 7 Nov 2026 00:54 and 01:05 | core | `deeb3b0` | aborted: ThinkPad network outage, then Gemini daily quota (500/day free tier) | | | | |
| **Core 3** | **7 Nov 2026 01:41–01:59** | **core** | **`1331cde`** (core code of `deeb3b0`) | **3 / 13 / 4** | **559 s** | **19.5 s** | **113** | **2.3k** |

Core 3 was run on a second API key after the first key's daily quota was used up.

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

## What the numbers say

- **Cost per step is where the design wanted it.** 2.3k prompt tokens per call against 10.7k, one
  call per step, 559 s for the suite against 1,496 s. The filtered tree works.
- **Completion is not.** 3/20 against 13/20. The failures are not quota or emulator: 13 of 20 are
  the agent's own.
  1. **Guarded-input stop (3 tasks: Timer, SmsSend, MarkorCreateFolder).** After a Send / keyboard
     action / type+submit, the loop stops with "effect unverified" instead of observing and
     continuing. The safety patch of 7 Nov (`docs/CONTEXT.md` §2) made this deliberate to stop
     blind repeats; it now costs tasks the checker would have passed (MarkorCreateFolder). Needs a
     post-condition readback (observe, compare, then decide) instead of a hard stop.
  2. **Target resolution refused (4 tasks).** "no identified control or matched item": list rows and
     tiles whose only text is content the planner is not allowed to see, with generic resource ids.
     The match-only rule hides the row ("most recent conversation" matches nothing in the title) and
     core then refuses the tap. This is the open question from the design review: the reader's
     `element` shape ("which row is the newest thread?") is not implemented yet, and that is the
     designed way out.
  3. **Planning (6 tasks).** Wrong actions on Settings toggles, brightness, calendar date, expense
     delete, camera shutter. Partly the same anonymous-control problem (Quick Settings tiles,
     shutter button with no label), partly prompt quality.
  4. **Environment (4 tasks).** Two Gemini 503s (transient), two `foreground_not_settled` from the
     new observation-consistency check (it rejects a tree whose activity changed between samples;
     here it rejected a legitimately settling screen for 15 s and gave up).
- **Zero false "done"** is the one safety number that moved the right way.

## Next, in order

1. Post-condition readback after guarded inputs (fixes category 1, likely +2–3 tasks).
2. Reader `element` shape so the planner can ask "which row" without seeing content (category 2).
3. Code-owned labels for known anonymous controls (Quick Settings tiles, camera shutter), per the
   7 Nov safety patch's mechanism.
4. Retry 503 once in the Models port; relax `foreground_not_settled` to a longer soft deadline.
Then rerun: one key gives ~500 calls a day; a core suite costs ~115.
