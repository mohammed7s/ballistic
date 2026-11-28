# Claude Haiku 5.5 on the API-37 eligible subset, 28 November 2026

The owner chose Claude Haiku 5.5 (`claude-haiku-5-5`) after the free Gemini tier rate-limited the
Flash Lite subset run. All four model roles in the disposable emulator app were pointed at
Anthropic's OpenAI-compatible endpoint, `https://api.anthropic.com/v1/`, through the debug
`files/models_import.json` path. The key came from `~/.ballistic_env` with the README's `run-as … tee`
method and never appeared on a command line or in output. The previous Gemini `models.json` is kept
as `models.before.json` in the artifact directory.

Environment: `BallisticControlApi37`, build `2026112300`, the same 11 eligible tasks, seed 0, 15-step
APK budget, 300 s timeout, `harness/apk_suite.py`. The agent received only the goal string.

## Run 1: thinking on (default) — 1 of 11, not a planning result

Haiku 5.5 thinks by default. Its hidden reasoning is billed as completion tokens and counts against
`max_tokens`. Core's judge budget is 100 tokens (`judge::JUDGE_TOKENS`), and reader calls are small
too. Eight of the 11 runs ended with `blocked by the safety check: the safety check's reply was
unreadable`. Readers' `facts` replies were also rejected as off-shape. Core treated each unreadable
verdict as a block, which is the intended fail-closed behaviour.

Host reproduction with the judge prompt and four realistic action descriptions, five samples each
at `max_tokens: 100`:

| Request option | Clean `{"verdict":…}` | Empty or cut off (`finish_reason: length`) |
|---|---:|---:|
| none (thinking on) | 13/20 | 7/20 (`''`, `'{"verd'`) |
| `reasoning_effort: "low"` | 14/20 | 6/20 |
| `thinking: {"type":"disabled"}` | 10/10 | 0/10, 13 completion tokens each |

The only pass in run 1 was ClockStopWatchRunning.

## Change: opt-in `disable_thinking` endpoint option

`Endpoint` in `ModelsHttp.kt` gained `"disable_thinking": true`. When it is set, the request adds
only `"thinking":{"type":"disabled"}`. It defaults to false and follows the same role-fallback rules
as `disable_native_tools`. Core budgets, prompts, parsing and the fail-closed judge are unchanged.
New unit test `disableThinkingAddsOnlyTheThinkingFieldAndFollowsFallback`: `:core-api:test` ran
201 tests, 0 failed, 3 skipped. The ThinkPad build ran with the emulator stopped (`free -g` checked
first), APK SHA-256 `a65761e2…833e`, installed with `install -r`. The assistant role and
`ACCESS_COMPUTER_CONTROL` held.

## Run 3: thinking off — 3 of 11 combined, 0 false done

Counted runs: the suite3 run for six tasks; the suite3c rerun for four tasks interrupted by an
emulator crash; the suite3d run for Expense.

| Task | Done | State | Combined | Steps | Agent s | End |
|---|---|---|---|---:|---:|---|
| ContactsAddContact | yes | pass | **pass** | 8 | 35.6 | done |
| ClockStopWatchRunning | yes | pass (adapted) | **pass** | 3 | 15.4 | core's typed check |
| ClockStopWatchPausedVerify | yes | pass (adapted) | **pass** | 2 | 15.7 | core's typed check |
| SimpleCalendarAddOneEvent | no | fail | fail | 15 | 57.4 | step budget; date-picker navigation |
| SimpleCalendarEventsOnDate | no | fail | fail | 3 | 13.7 | planner used a terminal `summary` read on the month header |
| MarkorCreateNote | no | fail | fail | 14 | 66.7 | core stopped a repeated tap after a warning |
| MarkorCreateFolder | no | fail | fail | 15 | 64.6 | step budget |
| MarkorEditNote | no | fail | fail | 15 | 68.6 | step budget; repeated facts reads |
| MarkorMoveNote | no | fail | fail | 9 | 58.5 | repeated `wait`, then gave up |
| RecipeAddSingleRecipe | no | fail | fail | 0 | 10.8 | "Stop requested" at the first dispatch; not from the test |
| ExpenseAddSingle | no | fail | fail | 1 | 8.6 | `Computer Control session ended` about 2 s after launch (again) |

There were no unreadable-verdict blocks and no rate limits. Seven failures are agent-side; two are
platform session ends that are not yet diagnosed. The calendar question shows a design gap: by
design the planner never sees the month that a `facts` read records, so it has no safe way to
navigate dates by itself. It then reached for a `summary` read, which ends the run. The stopwatch
checks are adapted preference checks, not AndroidWorld's UI checker. `side_effects` were not
audited. This score is not comparable with harness suites or the frozen 30.

Model use over all Haiku runs: 549,732 prompt and 24,488 completion tokens, about $0.07 at
$0.10/$0.50 per million.

## Operator incidents (test setup only, nothing on the agent's path)

- After each cold boot, user 0 was `RUNNING_LOCKED`. The app's files are credential-encrypted, so
  the operator entered the disposable test PIN on the bouncer. Run 2 was void: the screen was
  asleep, and the owner gate correctly refused every task with `owner_unlock_required`.
- The `-gpu host` emulator crashed mid-run (`Failed to make display surface context current`)
  after the host desktop had been idle for about 48 minutes. It was restarted visible with
  `-gpu swiftshader_indirect` (`~/ballistic_runs/start-emulator-swgpu.sh`).
- The emulator reported `AC powered: false`, so stay-on-while-plugged did not apply and the
  device slept on its timeout. The fix was `adb emu power ac on`.
- The crash lost Pro Expense's database, and AndroidWorld's setup then needed its UI onboarding,
  which this driver refuses on the session display. The operator tapped NEXT and CONTINUE with
  uiautomator.

## Next

- Diagnose the platform session ends (Pro Expense, Broccoli).
- Planner guidance or a typed operation for date navigation, which the planner cannot do without
  seeing app content.
- Markor menu and loop behaviour, and the step budget on multi-field forms.
- Approval protocol for the 8 approval-gated tasks.

## Artifacts

ThinkPad `~/ballistic_runs/cc_haiku_20261128/`:
- `suite`, `suite2` (void), `suite3`, `suite3b-*` (aborted), `suite3c`, `suite3d`;
- the judge probes;
- `crash/` (emulator log and crash database);
- `apk-sha256-nothink.txt`.
