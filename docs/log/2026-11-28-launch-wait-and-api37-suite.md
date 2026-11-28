# Launch wait, warm fixture and the API-37 suite port, 28 November 2026

Environment: ThinkPad, `BallisticControlApi37`, `emulator-5554`, API 37, build `2026112300`
(userdebug). Every device command used `-s emulator-5554`; the Pixel was not addressed and
`AndroidWorldAvd` was not started. These are emulator results, not Pixel results.

## Run 3 of 27 November: root cause

The 27 November record suspected an empty first observation after `open_app`. The agent record of
run 3 confirms it by timing, and an offline core test reproduces the rejection:

- Step 2's "Reading available screen information" took 1,049 ms (5,566 → 6,615 ms). That is the
  Screen port's full empty-tree retry (three 300 ms retries plus reads), so the observation after
  the launch came back empty.
- With no application window on the session display, `ComputerControlScreen` reports
  `package = ""` and no nodes. The planner, seeing nothing, asked for `scroll right until
  Stopwatch`. Core's pre-dispatch check scopes a non-launch operation to `screen.package`
  (`capabilities::app_scope`), and `""` is not in the session's app grants (`com.android.deskclock`),
  so it refused with `host capability or app grant denied` (category `permission`).
- `launchApplication` returns before the app draws; the adapter did not wait for the launched app.
  Runs 1 and 2 recovered only because the planner happened to open Clock a second time.

The new core test `step_record_shows_a_blank_observation_behind_a_grant_denial` feeds that blank
screen and a scroll: the scroll is refused before dispatch and the step record shows
`observed: {package: "", nodes: 0, package_granted: false}`. The grant check was correct; the
observation was premature.

## Fix (implemented, unit-tested)

- **Hands, generic:** after `launchApplication` returns, `ComputerControlHands` polls the session
  (200 ms, at most 10 s) until the launched package is the front application window and has
  nodes. If it never shows, the result is `Error` ("launch was sent … effect unknown"), so core
  stops with execution status unknown instead of planning on a blank screen. A session that ends
  or pauses meanwhile stops the wait; the next observation reports that state. The grant check
  is unchanged and still runs against the host's front package.
- **Record:** every core step event now carries `observed: {package, activity, nodes,
  package_granted}` for the screen that step planned on: host facts, no node text. The APK
  benchmark output includes it through `StepEvent.observed`. Activity stays empty on Computer
  Control, as the API reports none.
- **Fixture:** when Clock reopens on an already selected Stopwatch tab (a non-clickable
  container), the adapter fixture taps the sibling Clock tab first, then the Stopwatch tab, so
  warm runs exercise tab tap/readback, non-target rejection and closed/retained-session rejection.
  The extra check is recorded as `moved_off_selected_tab`.

Tests: `cargo test -j 4` 467 passed on the Latitude. Kotlin `:core-api:test` on the ThinkPad:
196 tests, 0 failures, 3 skipped (first build), then 200, 0 failures, 3 skipped with the settling
change below. Four adapter tests cover the launch wait (tree appears after empty reads, bounded
failure, session end during the wait, front-package condition).

## Build and install

Built in `~/ballistic_cc_20261128` on the ThinkPad: native core through
`apps/assistant/scripts/build_core.sh` with `CARGO_TARGET_DIR=~/ballistic_cc_20261128/target`,
then `nice -n 10 ./gradlew --no-daemon --max-workers 2 :core-api:test :app:assembleDebug
:app:assembleDebugAndroidTest`. The app and test APKs have the same signer as
`~/ballistic_cc_20261122` (certificate SHA-256 `4cc422a4…9325a4`), so `adb -s emulator-5554
install -r` kept data and setup; nothing was uninstalled or cleared. This APK also carries every
core change merged since 22 November (loop 3), so pilot results are not a controlled comparison
with 27 November. APK SHA-256 in `apk-sha256.txt` (launch wait) and `apk-sha256-settle.txt`
(launch wait plus settling).

## Model-free adapter fixture (tested, adapter only)

Cold = after `pm clear com.android.deskclock`; warm = Clock data kept from the previous run. Zero
model calls in every run; no `registerDisplayProxy`, `NullPointerException` or `FATAL` in any
logcat.

| Run | Build | Condition | Result | Checks | Wall s |
|---|---|---|---|---|---|
| 01 | launch wait | cold | error `session_start:NotVisible` (first start after install; Ballistic not yet focused) | 0 | 26.131 |
| 02 | launch wait | warm, Clock tab (01 never reached Stopwatch) | passed | 6/6 | 8.593 |
| 03 | launch wait | cold | passed | 6/6 | 8.676 |
| 04 | launch wait | warm, Stopwatch already selected | passed | 7/7 incl. `moved_off_selected_tab` | 11.963 |
| 05 | launch wait | cold | passed | 6/6 | 11.188 |
| 06 | launch wait | cold | passed | 6/6 | 13.159 |
| 07 | launch wait | warm, Stopwatch already selected | passed | 7/7 | 13.390 |
| 08 | settling | cold | passed | 6/6 | 10.416 |
| 09 | settling | warm, Stopwatch already selected | passed | 7/7 | 11.314 |
| 10 | settling | cold | passed | 6/6 | 9.425 |

Warm runs from an already selected Stopwatch tab now run all checks (04, 07, 09). These test the
adapter only, not Stop, revocation or approvals.

## Flash Lite "Run the stopwatch." pilot (launch-wait build)

`python3 -m harness.apk_clock_pilot`, five runs (the authorized maximum), all roles
`gemini-flash-lite-latest`. Six session targets were configured by then (see the suite setup).

| Run | Agent done | State | Combined | Agent s | Steps | End |
|---|---|---|---|---|---|---|
| 1 | no | fail (`reset`) | no | 21.284 | 4 | `target_resolution`: planner kept naming `e5`, not on the screen |
| 2 | no | not reached | no | 12.204 | 2 | model HTTP 429 (rate limit; runs were back to back) |
| 3 | yes | pass (`running`) | yes | 29.738 | 6 | core's typed check: Pause shown, no Start |
| 4 | no | fail (`reset`) | no | 20.065 | 4 | as run 1 |
| 5 | no | fail (`reset`) | no | 18.467 | 4 | as run 1 |

**The launch fix held.** In every run the first step's observation is blank, as expected before
anything is launched (`package ""`, 0 nodes), and the observation after `open_app` is Clock with
77 nodes and `package_granted: true`. No `open_app` was repeated and no permission denial
occurred. Runs 2–5 were spaced 90 s apart after the 429.

**New failure, not yet explained.** Runs 1, 4 and 5 followed the same path: tap `e5` (stated
reason: open the Stopwatch tab), tap `e7` ("start the stopwatch"), tap `e6`, after which the
screen had 20–51 nodes and logcat shows Clock starting its `ScreensaverActivity`. The planner
then referred to `e5`, which was not on that screen, until core stopped it
(`target_resolution`). The record has node counts but no screens, so which controls `e7` and `e6`
were is inferred, not shown. A plausible contributor is a tree read before the tab switch had
redrawn: the adapter returned its first non-empty read, whereas the harness waits for two
consistent reads and re-reads an unchanged tree after input. That settling was then ported to the
session Screen port (below). It has passed the model-free fixture but has **not** been run with the
model on this task; no sixth pilot run was made.

## Settling (implemented, unit-tested, fixture-tested)

`ComputerControlScreen` now returns a tree after two consecutive reads with the same skeleton
(front package, classes, ids, bounds, flags; text ignored so a running stopwatch still settles),
bounded at five extra reads, 250 ms doubling to 1 s. After an input that Hands reports as
possibly sent, a tree identical to the previous observation is read again up to three times,
800 ms apart. A screen that stays unsettled or unchanged is returned as read. Hands and Screen
share a `CcInputMarker`; nothing in it reaches core.

## Incidents during this work

- **ThinkPad out of memory.** With the emulator running and about 2 GiB available, the native
  core build followed by Gradle (`--max-workers 2`) pushed the host over; `systemd-oomd` killed the
  owner's GNOME session at 21:40:39 UTC, and the emulator with it. User data survived (role,
  switches, policy, consent store, installed apps were intact after reboot of the AVD).
- **No user display, no KVM.** logind then moved the `/dev/kvm` ACL to the greeter. The test
  operator restored the same per-user ACL (`setfacl -m u:ubuntu:rw /dev/kvm`, lost at the next
  login or reboot) and restarted the AVD **without a host window** (`-no-window`,
  `-gpu swiftshader_indirect`), since no user X session exists. The image, AVD and userdata are
  unchanged; rendering is software, so timings are slower than earlier runs and not comparable.
  SystemUI showed one "isn't responding" dialog during the first unlock.
- After the restart the device required its PIN (credential storage); the operator unlocked it.
- The first Gradle run failed one pre-existing test: `FixtureTest` assumed every recorded screen
  has a top-level `screen`, but the loop-3 step traces do not. The test now round-trips their step
  screens and final `after` screen as well.

## API-37 suite port (Phase 2)

Eligibility is frozen in `harness/suites/computer_control_30_api37.json` and summarized in the
[evaluation guide](../COMPUTER_CONTROL_EVALUATION.md#api-37-port-of-the-frozen-30-28-november-2026).
Key measured facts:

- AndroidWorld's APKs were fetched from its public bucket into `~/ballistic_cc_20261128/aw_apks`
  (none were cached on the ThinkPad). Markor, Pro Expense, Broccoli and Simple Calendar Pro
  installed (target SDK 33, 30, 34, 33). `clipper.apk` was refused:
  `INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 24`.
- AndroidWorld moves the device date to 15 October 2023. On this setup the model calls leave from
  the device over TLS, so that date would invalidate them; the driver leaves the date real and
  still sets UTC and 24-hour format. Tasks whose checker depends on that date are classified.
- Settings and SystemUI are protected Computer Control targets on this image (OS review
  follow-up). The five Settings tasks, including seed-0 `OpenAppTaskEval` ("Open the settings
  app"), are `policy_blocked`.
- Send and delete tasks need a hard confirmation that only the owner answers in the APK; they are
  `approval_gated` until an approval protocol exists. No approval bypass was added.
- The eligible apps fill one session's six-target maximum (Clock, Contacts, Simple Calendar Pro,
  Markor, Pro Expense, Broccoli); Joplin and Retro Music tasks are deferred.

Owner-style setup for the new targets, all through ordinary UI by the test operator: Markor
onboarding and All files access, Pro Expense onboarding, first launches of Broccoli, Simple
Calendar Pro and Contacts; Settings > Special app access > Screen automation switches for the
five new targets (dumpsys shows six user-approved automatable apps); Ballistic app rules saved
with the simulated fingerprint. AndroidWorld's `pm grant` of calendar permissions was not used.
Then the operator started screen control from Ballistic's "What Ballistic can do here" page; the
system consent dialog appeared once per new target and the operator chose Always allow for
Contacts, Pro Expense, Broccoli, Calendar and Markor. The session became active and was ended
before instrumentation. Host preflight passed all six checks.

## Eligible subset with Flash Lite (settling build)

`~/android_world/.venv/bin/python -m harness.apk_suite --serial emulator-5554 --pause 90`, group A,
one agent run per task intended, 15-step APK budget, 300 s timeout, all roles
`gemini-flash-lite-latest`. The agent received only AndroidWorld's seed-0 goal string.

**Pass 1 found three driver/environment faults** that invalidated nine of eleven attempts; none of
them is an agent result:

- AndroidWorld names Google's Clock and Contacts packages; this image has AOSP
  `com.android.deskclock` and `com.android.contacts`. Clock setup (`pm clear
  com.google.android.deskclock`) failed, so both stopwatch tasks stopped at setup and the agent was
  never started. The driver now maps only those two app names to the AOSP components (recorded
  as a deviation).
- `MarkorCreateNote`'s setup moved the device date to 15 Oct 2023: Markor tasks initialize inner
  validator tasks, which the per-instance date override did not reach. Every later model call
  failed TLS (`SSLHandshakeException` within about 1 s, zero steps) for six tasks. The override is
  now class-wide, the driver refuses to start the agent when the device clock differs from the
  host by more than an hour, and the operator reset the device date.
- `ContactsAddContact`: AndroidWorld's setup clears the contacts provider, which also clears
  Android's default contacts account. Tapping "Create new contact" then opened Settings' Contacts
  storage picker, a protected target, and the platform blocked and ended the session
  (`BlockedLaunch`, `blockingPackage=com.android.settings`) after two agent steps. The driver now
  runs an operator step after setup: Settings > Apps > Contacts storage > Device only (through
  the UI; it is reset by every provider clear).

Pass 2 re-ran exactly those nine tasks after the fixes. The two calendar tasks had valid pass-1
runs and were **not** re-run. ContactsAddContact therefore has two agent runs in total; its first
was ended by the protected picker, not by the agent.

| Task | Counted run | Agent done | State | Combined | Steps | Agent s | End |
|---|---|---|---|---|---:|---:|---|
| ContactsAddContact | pass 2 | yes | pass | **pass** | 8 | 39.1 | done accepted after one rejected claim |
| ClockStopWatchRunning | pass 2 | yes | pass (adapted) | **pass** | 3 | 20.8 | core's typed check (Pause shown) |
| ClockStopWatchPausedVerify | pass 2 | yes | pass (adapted) | **pass** | 2 | 13.0 | core's typed check (Start shown) |
| SimpleCalendarAddOneEvent | pass 1 | no | fail | fail | 4 | 18.2 | blank observation after opening the event editor; planner asked for `home` (not advertised) → denied |
| SimpleCalendarEventsOnDate | pass 1 | no | fail | fail | 2 | 10.8 | deny-only judge blocked the first tap (a false block) |
| MarkorCreateNote | pass 2 | no | checker error | error | 13 | 76.0 | model HTTP 429; AndroidWorld checker raised `RuntimeError` |
| MarkorCreateFolder | pass 2 | no | fail | fail | 6 | 36.8 | model HTTP 429 |
| MarkorEditNote | pass 2 | no | **pass** | fail | 9 | 38.8 | core rejected three done claims, then HTTP 429 |
| MarkorMoveNote | pass 2 | no | fail | fail | 12 | 59.3 | model HTTP 429 |
| ExpenseAddSingle | pass 2 | no | fail | fail | 1 | 9.2 | platform closed the session about 2 s after Pro Expense launched (`Closed(reason=2)`), not yet diagnosed |
| RecipeAddSingleRecipe | pass 2 | no | fail | fail | 13 | 61.5 | core stopped a repeated `scroll … until "Directions"` loop |

**Score on the eligible subset: 3 of 11 combined pass, 4 of 11 independent state, 0 false done.**
Of the eight that did not pass, four ended on the model provider's rate limit, one on an
undiagnosed platform session close, one on a checker error; the calendar pair and the recipe run
are agent-side failures (one judge false block, one blank observation after a tap, one planning
loop). The stopwatch checks are adapted preference checks, not AndroidWorld's UI checker. The
calendar tasks ran with the real 2026 date. This is not a score for the 30: 19 tasks were not
eligible, and it is not comparable with earlier harness suites (different observation path,
15-step budget, mutable model alias, software rendering). `side_effects` remain not audited.

The independent state checks ran on the host after each agent run ended, with adbd rooted only
for setup and checking; the APK never received checker state. Checks for MarkorCreateNote and
ExpenseAddSingle were re-run by hand afterwards on a freshly set-up state and returned 0.0 (no
exception), so the checker itself works on this image; the pass-2 exception is unexplained.

Rate limits: runs were spaced 90 s apart, but the APK has no model-call pacing, and long tasks
exceeded the free-tier request rate within a single run.

## Operator and host state left behind

- Emulator still running headless; userdata intact; six targets with rules and Always-allow
  consent; Contacts storage Device only; device timezone UTC (AndroidWorld setup), 24-hour
  format, `auto_time` set back to 1 by the operator.
- The temporary sleep inhibitor was removed. The `/dev/kvm` ACL is logind's again since the owner
  logged back in.

## Artifacts

ThinkPad `~/ballistic_runs/cc_20261128/` and Latitude `ballistic_runs/cc_20261128/` (git-ignored):
`fixture/` (10 runs, JSON, stdout, logcat), `pilot/` (5 runs), `suite_pass1/` and `suite/` (task
records, agent records, summaries), `apk-sha256*.txt`, `aw-apk-sha256.txt`, `aw_tasks.json`
(seed-0 goals), `preflight.txt`, `ops-notes.txt` (host interventions) and `ops/` (operator
scripts; test-operator UI only). A scan found no API key in any artifact.

## Next

- Blank observation after an in-app transition (Calendar's event editor): extend the empty-tree
  wait after an input the way `open_app` now waits, then rerun the calendar pair.
- Pace model calls in the APK (or authorize a paid quota) before rerunning the four rate-limited
  Markor tasks; diagnose Pro Expense's `Closed(reason=2)` and the MarkorCreateNote checker error.
- Re-check the stopwatch screensaver path with the settling build under the pilot (not run).
- An approval protocol for the eight approval-gated tasks; display-aware UI checkers; a second
  six-target group for Joplin and Retro Music.
