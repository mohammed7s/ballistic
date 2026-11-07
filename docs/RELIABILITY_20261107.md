# Reliability work: offline protocol and completion regressions

Baseline: `22c3005`. This report belongs to the implementation worktree; merged status and
deployment remain with the parent. No model, emulator, SSH, ADB or benchmark checker is used
by this work. Existing evidence under the main checkout is read only.

## Diagnosis and scope, before implementation

1. Core 4 recorded six HTTP 200 missing-content failures. Current code classifies them safely
   but still aborts. Retry one absent/null/blank textual reply only when the envelope is otherwise
   valid and has no refusal, tool/function call, filtering or length-limit signal. The existing
   total retry ceiling and token limit remain unchanged. This is recovery of model generation,
   never a retry of an action. A 429 always stops; no credentials or raw bodies in diagnostics.
2. Current completion validation accepts any existing citation except an anonymous switch.
   An opaque content reference/resource id is not readable postcondition evidence. Reject those
   citations, provide fixed code-owned feedback, and let the planner inspect the fresh screen
   already acquired for verification. Keep verification classified as model assessment.
3. Missing slider range values, broad Settings content filtering, exact field coverage and
   ambiguous send receipts remain unresolved. No benchmark-specific route or success rule is
   introduced, and an emptied editor never becomes proof of delivery.

## Frozen regression cases (before implementation)

These synthetic cases vary the recorded task labels/layouts/values. They were written before
production changes, but guided implementation: they are regression cases, not an independent
held-out evaluation. Their expectations were fixed before coding. An independent held-out
workflow evaluation is still required; passing these cases does not establish generalization.

Protocol:

- P1: reader role, null content with `stop`, then a single element id: recover in two requests.
- P2: verifier role, whitespace with missing finish reason, then a valid failed verdict: recover
  without converting it to verified; same model/messages/token limit on both requests.
- P3: planner missing content twice: stop after two attempts, even with a third valid reply queued.
- P4: 503, absent content, successful reply: three requests share the existing total budget.
- P5: absent content followed by daily-quota 429: stop; no third request or key change.
- P6: null content with length/content-filter/tool-call/function-call/unknown finish reason, or
  refusal/tool/function-call payload despite `stop`: one request, safe typed error.
- P7: malformed shapes/non-string content: no retry. Unknown usage remains unknown; known usage
  on discarded generations is preserved in safe attempt telemetry.
- P8: an approved guarded tap followed by a missing verifier reply and recovered `unknown`:
  exactly one dispatched action, no inferred completion. Also test exhaustion after a prior action.

Completion (vary node positions/order, packages, resource IDs and strings):

- V1: a saved item `Orchard archive` matching the owner request: verified model assessment allowed.
- V2: a named `Vibration` switch, checked: verified model assessment allowed; named unchecked
  `Reduce motion` with a failed verdict stays failed.
- V3: an opaque `Cobalt ledger` item with only a resource id and hostile hidden prose: verified
  and failed claims both rejected to unknown; no hidden text forwarded.
- V4: a visible matching `Meadow log` plus a second opaque distractor: citing both cannot smuggle
  the opaque reference into verified evidence.
- V5: a nameless switch whose text matches `off` remains unknown; a reader pick changes nothing.
- V6: nonexistent, duplicate/noncanonical IDs, malformed verdict, or authoritative evidence claim
  cannot be accepted. A properly shaped unknown verdict remains unknown.
- V7: rejected completion returns fixed visibility feedback and the fresh verification snapshot
  to the next planner call; hostile verifier reason never enters that call; no action dispatched.
- V8: cancellation after verifier completion prevents another model call or dispatch.

No live score is predicted. Keep `gemini-flash-lite-latest`, role routes, max steps, token limits,
temperature, timeout and the existing overall retry ceiling for eventual comparison.

## Offline results

The cases above and their executable tests were written before production changes. Against
`22c3005`, completion tests V1–V8 passed 4 and failed 4 (V3, V4, V6, V7). Protocol tests P1–P7
passed 1 and failed/errored 6; P8 was initially skipped until a local core binary was built.
After implementation all **16 cases pass**, including P8 against the actual Rust process and
Python bridge, with scripted HTTP and screen/Hands ports. No device or model service was called.

Behavior measured by those fixtures:

- Eligible null/blank generations followed by valid output recover in two HTTP requests;
  repeated absence stops at two even if a valid third reply is queued. A preceding 503 shares
  the existing total ceiling (four requests by default). With `retries=1`, 503 then missing
  content stops after two. No request argument, model, route, credential or token limit changes.
- Length/filter/tool/function/refusal/unknown-finish cases make one request. A missing reply
  followed by 429 makes two total, records `quota_category=daily`, then stops. Malformed envelopes
  and non-string content remain terminal. Raw payloads and provider finish-reason text never
  enter retry diagnostics. Known discarded-generation usage is in `retried`; unknown usage is
  absent. Existing top-level usage represents the final attempt, so cost analysis must include
  nested retry usage rather than treating it as free.
- Opaque-only and mixed opaque/readable citations that previously accepted `verified` now return
  `unknown`. Positive matched-item and named-switch evidence still returns `model_assessment`;
  a valid negative verdict stays `failed`. Noncanonical/duplicate citations are rejected.
- Rejected completion supplies fixed visibility feedback and the already-acquired verification
  snapshot to the next planner call: two observations total in V7, no third observation and no
  dispatch. Neither hidden content nor the hostile verifier reason enters planner messages.
- P8 acknowledges one guarded Send, then retries only the verifier HTTP generation. Both
  successful recovery to `unknown` and retry exhaustion produce exactly **one Hands dispatch
  and one confirmation**. Recovery preserves `execution_status=executed` and `done=false`;
  exhaustion stays an explicit model error. Stop after verification prevents another model
  call or dispatch.

Validation: **84 relevant Rust tests with UniFFI**, including the three UniFFI boundary tests,
and **71 Python tests** pass. Wallet-specific integration and library tests were excluded;
no wallet source, dependencies or fixtures changed. Commands, from the respective directories:

```sh
# core/ (cargo is ~/.cargo/bin/cargo on this host)
cargo test --features uniffi --test completion_visibility --test agent_checks \
  --test readback_and_reader --test benchmark_regressions --test known_controls
cargo test --features uniffi --lib -- --skip tools:: \
  --skip stop_during_planning_prevents_a_granted_tool_call \
  --skip legacy_library_entry_has_no_implicit_tool_adapter
# harness/; the cargo test above builds core/target/debug/ballistic-core
BALLISTIC_TEST_CORE_BIN="$PWD/../core/target/debug/ballistic-core" python3 -m unittest \
  test_model_recovery test_core_bridge test_outcomes test_suite_row test_injection_checks
python3 -m unittest test_core_bridge_robustness
```

## Integration and remaining uncertainty

Production changes are limited to `ModelClient` in `harness/core_bridge.py`,
`core/src/verification.rs`, and the `Action::Done` arm in `core/src/agent.rs`. That arm changes
the verifier instructions, checks Stop after the verifier returns, consumes fixed assessment
feedback and reuses the fresh screen on rejection. No Tools/session/dispatch hunks are touched;
the concurrent wallet removal should integrate independently. No ABI/type/schema changes or
Kotlin binding regeneration are required. The Python HTTP recovery does **not** change the
native app's separate HTTP client.

This is offline regression evidence, **not a benchmark uplift**. Historical missing-content
responses did not preserve finish reasons, so the fraction eligible for recovery is unknown.
Length-limited replies remain errors without a larger output budget. The verifier still makes
semantic judgments and may accept an irrelevant readable label or miss an exact field mismatch;
there is no code-enforced per-field checklist or authoritative send/persistence receipt.
Conservative evidence rejection can lower completion claims when real state is correct but not
observable. Slider values and Settings label identity remain unresolved. The current generic
final budget classifier can still label malformed-evidence exhaustion as planning; per-step
verification rejection is explicit in the log. No claim about 13/20 is supported.

## Targeted live recommendation for the parent

After native Stop testing releases `~/ballistic_runs/emulator.lock`, take that lock atomically,
verify a visible emulator and explicitly target **emulator-5554 only**. Parent deploys/rebuilds
the integrated core and host first; do not use an older native APK to measure the Python recovery.
Run the following tasks one at a time, retain each full JSON and compare production evidence
with the independent checker before proceeding: **OpenAppTaskEval**, **SystemWifiTurnOff**,
**MarkorCreateNote**, **ExpenseDeleteSingle**. These cover a positive completion control, an
unrelated-switch negative case, exact file identity/persistence, and post-deletion verification
where a response error previously interrupted the run. Do not repeat a possibly executed send.

For each task use the existing suite settings (replace TASK with the name above):

```sh
ANDROID_SERIAL=emulator-5554 ~/android_world/.venv/bin/python -u harness/run_task.py \
  --task TASK --agent core --model gemini-flash-lite-latest \
  --base_url https://generativelanguage.googleapis.com/v1beta/openai/ \
  --console_port 5554 --seed 0 --confirm approve --max_tokens 2000 --timeout 120
```

Use the existing environment key without printing/copying it. No max-step override: preserve
each task's current budget. Stop on quota and inspect sanitized telemetry; do not rotate keys.
These live calls may never produce an eligible empty response, so the scripted bridge test
remains the deterministic retry proof. Only after these targeted checks and the existing
injection gates should the parent consider the full 20-task measurement.

## Further trace-backed planning refinements (7 November 2026)

The Core 4 timer recording shows a `type` attempt on a non-editable duration display.
Core's correction incorrectly suggested tapping it first; the model then emitted a `tap`
containing the type-only `submit` field and exhausted correction attempts. Feedback now
requires a genuine editable input and does not suggest that tapping makes a display editable.
Planner instructions clarify per-action fields and visible numeric-keypad entry without any
task-name, requested-value or app-specific branch.

The Calendar recording shows start and end values of `16:00` after a request for 13:00 and
15 minutes. No time-setting action occurred. Its verifier accepted the title and description;
date/time value observations are separate pending work. The Markor recording shows separate
name/extension inputs presented as `my_note` and `.md`; the planner entered only the requested
basename. Exact package/resource/role mappings now name those two inputs `File name` and
`File extension`, keeping their displayed strings as content. The separate fields are confirmed
in the pinned [Markor v2.10.9 layout](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/res/layout/new_file_dialog.xml).
Two development tests written before this mapping improve from 1/2 to 2/2, covering content
isolation, exact text dispatch, typed readback and incorrect package/resource/role variants.

Planner instructions also require checking all requested constraints before saving and
distinguish screen changes from effect receipts. An unchanged screen should lead to waiting or
inspection, not blindly repeating a creation. The Camera trace contains four shutter dispatches;
the installed AndroidWorld checker requires exactly one new photo, but the collected run JSON
does not establish the resulting file count. Do not describe it as proven failure to capture.
These instructions are model guidance, not code-enforced semantic verification or duplicate
prevention. They cannot replace the pending typed evidence and policy work.

Combined local validation: **114 Rust tests with UniFFI and 79 Python tests pass, no skips**.
The planner rules grew from 1,661 to 2,489 characters (rough character/4 estimate: 416 to 623
tokens); actual provider token and behavior effects remain unmeasured. No model, budget,
action schema, permission requirement or benchmark checker changed. The live caption round
at `96d9c94` excludes these later changes; no full-suite uplift is claimed.

## Markor creation button semantics (7 November 2026)

The targeted seed-0 failure above exposes a concrete interpretation error: the FOLDER button
creates a directory immediately; it does not select a folder mode. The pinned
[NewFileDialog implementation](https://github.com/gsantner/markor/blob/ab2175460680eb4a914b6006b9c962756b2067d2/app/src/main/java/net/gsantner/markor/frontend/NewFileDialog.java)
binds the neutral button to directory creation and the positive OK button to file creation.
An exact package/class/role/parent/resource envelope, including both editable fields and the
three expected buttons, now labels them `Create folder` and `Create file`. Generic Android
button IDs alone, filenames, task names and requested values supply no identity. Unknown or
localized layouts fall back. Displayed content remains subject to the existing filter.

The two additional development tests (positive semantics and eight malformed envelopes) were
written before implementation: the focused file-form tests improved from 3/4 to 4/4. On all
four recorded dialog snapshots from the failed seed-0 run, offline filtering now emits both
creation labels. This demonstrates the observation change, not new model behavior. Combined
validation: **116 Rust tests with UniFFI and 79 Python tests pass, no skips**. No action guard,
benchmark checker, model budget or external schema changed. Live uplift remains unmeasured.


## Empty-response diagnostics after injection round (7 November 2026)

The pinned `07e5546` note case returned HTTP 200 with missing content and a finish reason stored
as `other`. This does not establish quota, safety filtering or malformed function generation;
the original value was intentionally not retained. No retry or provider probe followed.

Future records retain fixed allowlisted [Gemini finish enums](https://ai.google.dev/api/generate-content#FinishReason)
and boolean refusal/tool-call/function-call presence for non-text replies. Arbitrary provider
strings, refusal prose and function arguments remain excluded. This is diagnostic telemetry,
not a fix for empty generation: retry eligibility is unchanged, including no retry for native
uppercase `STOP` or any other added enum. Model, token limits, action dispatch and budget remain
unchanged. One regression covers every enum, payload exclusion and unchanged request counts;
all **86 Python tests pass without skips**, including the existing Rust/Python bridge tests.
Full Rust tests were not repeated for this Python-only change; the integrated base passed 173.


## Brightness observation limit (7 November 2026)

Read-only inspection of the installed AndroidEnv accessibility protobuf confirms that
`AndroidAccessibilityNodeInfo` has no range/progress/current-value field. It has bounds, text,
content descriptions, hints, flags and actions. Module:
`~/android_world/.venv/lib/python3.12/site-packages/android_env/proto/a11y/android_accessibility_node_info_pb2.py`,
SHA-256 `f3890146b3c033fac07010ceec8fe689506743c9e92b355ae587850ce83f90c1`.
This was source/descriptor inspection only: no device command or model call.

Core 4 `20261107-024334_SystemBrightnessMax.json` (SHA-256
`afe1109b7ca89648e2c002d757356f53ed31952c4a31d25dec478565c82fd36b`) has four slider
observations. Every one exposes `Display brightness`, bounds and flags, with no numeric value.
Independent benchmark state passed, while agent `done` and combined success were false.
The record does not prove which swipe first reached maximum or any value visible to core.

This is an observation gap upstream of our normalization, not merely a forgotten field mapping.
A future general range-value adapter or explicitly permitted host state-read capability needs
real source evidence and its own contract. Do not infer maximum from swipe geometry, a changed
screen, repeated attempts or the private benchmark checker. No host capability, verifier rule,
AndroidWorld code or frozen Core 5 build was changed for this finding.
