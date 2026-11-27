# Repaired emulator image: setup, adapter fixture and Flash Lite pilot, 27 November 2026

Environment: ThinkPad, visible `BallisticControlApi37`, `emulator-5554`, API 37, fingerprint
`Android/sdk_phone64_x86_64/emu64x:17/CP2A.260605.016/2026112300:userdebug/test-keys`. This image
contains OS patch 0009, the accessibility-proxy initialization-order fix described in the
[Flash Lite findings](2026-11-23-flash-lite-computer-control.md). These are emulator results, not
Pixel results. No phone was addressed; every command used `-s emulator-5554`. `AndroidWorldAvd`
was not started.

Before this work, the OS regression test passed on this image: `ComputerControlSessionTest`
50/50, plus `initialize_activeCallbackBeforeReturn_registersNonNullProxy` run on its own
(`regression.txt`). Swapping the image wiped user data, so the 23 November setup was redone.

## Setup (repeated through ordinary Android UI)

Host ADB taps (`tap_ui.py`, unique labels) were used only by a simulated test operator during
setup. They were not the agent's action route. No `cmd role add-role-holder`, `pm grant`,
appops change or approval bypass was used.

- Installed the same app and androidTest APKs from `~/ballistic_cc_20261122` (SHA-256 in
  `apk-sha256.txt`: app `ba9a71d2…`, test `9cfea500…`), without `-g`.
- Settings > Default apps > Digital assistant app: selected Ballistic and accepted the change
  warning. The role query returned `app.ballistic.assistant`; package state showed
  `ACCESS_COMPUTER_CONTROL: granted=true, flags=[ GRANTED_BY_ROLE]`.
- Settings > Apps > Special app access > Screen automation: fresh switches were all off. Turned on
  Allow screen automation for Ballistic and the Clock target. After force-stopping Settings and
  reopening the page, both still read `checked=true`; all other targets remained off.
- Set a disposable test PIN and enrolled a fingerprint through Settings, using emulator
  `finger touch 1` events (three touches). This is simulated test input, not physical biometric
  validation.
- App onboarding: chose Connected server, then allowed notifications through Android's permission
  prompt (`POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|…]`).
- Model config: all roles `gemini-flash-lite-latest` at
  `https://generativelanguage.googleapis.com/v1beta/openai/`, `timeout_seconds: 90`, imported by the
  debug `files/models_import.json` path. The key was written from `~/.ballistic_env` with the
  README's `run-as … tee` method; it never appeared on a command line, in output or on the
  Latitude. Both import files were deleted by the app on restart. A scan of the artifact directory
  found no file containing the key.
- Clock app rule saved through the app UI and approved with the simulated fingerprint. Persisted
  `policy.json`: observe `tree`, act `full`, route `default`, confirm `default_list`, screen control
  `on`. No other app has a rule.
- Start screen control showed the normal system consent dialog; the operator chose Always allow
  for Clock. The session became active, then End screen control was selected before
  instrumentation. Host preflight passed all six checks.

Final state: `setup-state.txt`. The onboarding text still says this setup cannot tap, type or
scroll in other apps, as noted on 23 November; unchanged.

## Model-free adapter fixture

The fixed `adapter-smoke` fixture was run five times, alternating cold (after
`pm clear com.android.deskclock`, the condition that failed on 23 November) and warm (Clock data
retained from the previous run). Logcat was cleared before each run and saved afterwards.

| Run | Condition | Status | Checks passed | Wall seconds |
|---|---|---|---|---|
| 01 | cold | passed | 6/6 | 6.456 |
| 02 | warm | error `session_stopwatch_target_ambiguous` | 2 (session, tree) | 4.043 |
| 03 | cold | passed | 6/6 | 5.638 |
| 04 | warm | error `session_stopwatch_target_ambiguous` | 2 (session, tree) | 4.108 |
| 05 | cold | passed | 6/6 | 5.835 |

Each run made zero model calls. Every before tree had 77 nodes from `com.android.deskclock`,
cold and warm. The empty-tree failure (`session_clock_tree_missing`) did not recur.

The warm errors are a fixture precondition, not an observation failure. The prior run left Clock
on its Stopwatch tab. That selected tab is reported as a non-clickable container
(`selected=true`). The fixture requires exactly one clickable Stopwatch node, so it found none and
stopped before the remaining four checks. Warm runs therefore tested session creation and tree
observation only. Tab tap/readback, non-target rejection and closed/retained-session rejection
were not exercised warm. To test them warm, the fixture would need to accept an already selected
tab or start from another tab. It was not changed here.

Logcat: none of the five fixture logcats (or the three pilot logcats) contains
`registerDisplayProxy`, `NullPointerException` or `FATAL`. On 23 November this NPE occurred in
the failing cold run.

These checks cover the adapter only. They are not an agent score and do not test Stop, OS
revocation or approval flows.

## Flash Lite stopwatch pilot

All cold fixture runs passed, so `python3 -m harness.apk_clock_pilot` was run (goal “Run the
stopwatch.”). It clears the emulator's Clock data, submits the goal to the real APK and reads
Clock's preferences from the host afterwards. Three runs, the authorized maximum:

| Run | Agent done | Independent state | Combined pass | Agent / wall seconds | Steps | Model replies |
|---|---|---|---|---|---|---|
| 1 | yes | pass (`running`) | yes | 27.472 / 30.049 | 7 | 17 |
| 2 | yes | pass (`running`) | yes | 18.973 / 21.683 | 5 | 12 |
| 3 | no | fail (`reset`) | no | 7.930 / 10.936 | 2 | 4 |

Runs 1 and 2: open Clock twice, tap the Stopwatch tab, tap Start, then a model-assessed `done`.
In run 1 the core rejected two `done` claims for insufficient evidence before accepting a third.
Run 3: open Clock, then the model requested `scroll right until Stopwatch`. Core's pre-dispatch
capability check rejected it (`host capability or app grant denied`, category `permission`,
not executed), ending the run. This repeats the 23 November failure pattern, now without the
proxy NPE. The `permission` label does not mean the owner denied anything.

Possible contributing factor, inferred and not proven: in runs 1 and 2 the recorded foreground
package before the second `open_app` was empty. This suggests the first observation after launch
can arrive before Clock's tree is available. Core checks app grants against the foreground
package. If run 3's scroll was planned from such an empty observation, the grant check against an
empty package would fail. Run 3's record has no per-step foreground package, so this is
unconfirmed. Unlike the fixture, the agent path has no bounded wait for a non-empty tree after
launch.

This is three runs of one task with a mutable model alias. It is not a suite score, a pinned
model comparison or a side-effect audit (`side_effects: not_audited`). The adapted preference
check is not the original AndroidWorld checker.

## Artifacts

ThinkPad `~/ballistic_runs/cc_repaired_20261127/` and Latitude
`ballistic_runs/cc_repaired_20261127/` (git-ignored): `regression.txt`, `preflight.txt`,
`setup-state.txt`, `apk-sha256.txt`, `fixture/` (per-run JSON records, stdout, logcat,
`progress.txt`), `pilot/` (`clock_state_*.json` checker records, `cc_*.json` agent records,
logcats). Pilot agent records: run 1 `cc_c53f4432bd2a466b90dd23964c4b3352.json`, run 2
`cc_cf8c2a06253244b588a6142ae3390012.json`, run 3 `cc_2821f6b8f3844ca58011bd624d80e291.json`.
Setup helper scripts (`read_state.py`, `put_key.sh`, `fixture_runs.sh`, `pilot_run.sh`) are
alongside; none contains a secret.

## Remaining

- Diagnose the post-launch empty observation and the run-3 rejection. Record the per-step
  foreground package in failures, then decide whether the agent path should wait for a non-empty
  tree after `open_app`.
- Make the warm fixture independent of the previously selected tab.
- Full app Stop, OS revocation and sensitive-action approval tests.
- AndroidWorld API-37 setup/checker adaptation and the frozen 30-task suite.
