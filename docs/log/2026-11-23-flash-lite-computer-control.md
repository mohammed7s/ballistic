# Flash Lite APK pilot and platform failure, 23 November 2026

The owner explicitly authorized Flash Lite when Pro was quota-blocked. All five model roles in
the disposable emulator app were switched to `gemini-flash-lite-latest` at the existing Gemini
endpoint. This is a mutable model alias, not a pinned Pro comparison. No phone change, production
authorization bypass or automatic fallback was introduced.

Environment: visible `BallisticControlApi37`, API 37, build `2026112200`; real APK Rust core and
Computer Control adapters, Clock-only app policy, 15-step limit. Original Pro and adapter evidence
is retained in [the pilot record](2026-11-23-computer-control-pilot.md).

| Request/check | Result | Agent seconds / total seconds | Model calls |
|---|---|---|---|
| Open Clock and show Stopwatch; do not start it | Agent done; tab was already selected by the earlier fixture | 15.879 / 19.188 | 5 |
| Run the stopwatch, after clearing disposable Clock data | Failed; open succeeded, later scroll rejected, stopwatch remained reset | 22.263 / 25.270 | 6 |
| Fixed adapter fixture after clearing Clock data again | Empty tree for the full bounded wait; `session_clock_tree_missing` | n/a / 14.857 | 0 |

The first result is confounded by the starting screen and is not proof of navigation from a fresh
Clock. The second report labels the final failure `permission`; it must not be interpreted as
proof that the owner denied access or that the model alone failed. The independent model-free
reproduction and OS exception establish a platform defect affecting observation. Its incidence
and contribution to other runs are not yet quantified.

Independent host inspection read Clock's device-encrypted preferences after the agent runs.
The first had `selected_tab=3`; neither had `sw_state`. Pinned DeskClock source defines missing
`sw_state` as RESET (RUNNING is ordinal 1). The second task therefore did not start the stopwatch.
These checks were performed outside the agent; no checker data was supplied to it. The runner's
original JSON still says `independent_state: not_checked`; these are supplemental manual checks,
not an integrated AndroidWorld checker or a side-effect safety audit.

## Platform diagnosis and correction

At 10:58:41.889, the failing zero-model run logged a NullPointerException in
`AccessibilityManager.registerDisplayProxy`, called by `ComputerControlSession.onActive`.
The display proxy was null. Clock itself launched on the session display and drew successfully,
but the client could not obtain its tree even after the fixture's approximately ten-second wait.

The OS client constructor called remote `initialize(callback, surface)` before assigning its
accessibility proxy. A fast active callback could therefore publish the active state and attempt
to register null. This is an initialization-order race, not evidence that cold startup merely
needs a longer sleep. Earlier warm success does not establish that only cold starts are affected.

The OS recipe now appends a focused patch that creates the proxy before publishing the callback.
It includes a regression test delivering the active callback before initialize returns. The
existing gates and public API are unchanged. Its incremental emulator build started on Contabo
at 11:06:17 UTC, using the existing separate emulator output. Build and regression execution are
pending; no runtime fix claim is made. Details belong in the
[OS record](../../../ballistic-os/docs/log/2026-11-23-accessibility-proxy-race.md).

## Artifacts and next checks

Latitude and ThinkPad: `ballistic_runs/cc_flash_20261123/`:

- `cc_1246aa5dc94149a69ea3cce986617e79.json`: warm tab request.
- `cc_6b93a58d925045c2b9b6df0fa22df498.json`: fresh stopwatch request.
- `cc_db5bdfd1f1e7444691c8ee70503200f9.json`: zero-model failure.
- `accessibility-proxy-failure.log`: OS exception stack.
- `stopwatch_tab_after.xml`, `stopwatch_running_after.xml`: supplemental UI snapshots.

After the repaired image boots, repeat cold and warm model-free checks, then the bounded Flash
Lite Clock pilot with independent state verification. Complete API-37 task setup/checker adaptation
before the frozen 30-task suite; preserve protected Settings classifications. Full app Stop,
OS revocation and sensitive-action approvals require their own tests. No full-suite score exists.

Follow-up preparation: `harness/apk_clock_pilot.py` now combines the fresh stopwatch task with
the independent preference check, keeping its supplemental verdict separate from the agent JSON.
All twelve host tests passed. This combined driver has not run live: the corrected image is still
building and ThinkPad SSH began timing out again. The owner was asked to keep the ThinkPad awake
and connected. The persistent Contabo build continues independently.

The repaired image subsequently built successfully at 11:26:22 UTC (19:09 build duration).
Transfer began when ThinkPad SSH recovered, then stopped with exit 255 when the connection was
lost again. The partial ZIP is unverified and the old emulator image remains in use. The OS
regression-test package is compiling separately; no further live agent result is available.
