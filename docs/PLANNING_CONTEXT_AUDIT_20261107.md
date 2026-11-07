# Planning/context audit — 7 November 2026

Offline investigation at **`4de7adebb7bf53e5ea28de5dad0d01bf18ef780e`**. Recommendation:
first test **grounded action history with bounded observation receipts**; separately test a
**validated owner-derived numeric plan**. Neither is a demonstrated score improvement.
The evidence supports both missing context structure and incorrect decisions despite available
information; it does **not** support “just retain more reasoning” as the explanation.

Scope: local documentation, source and frozen recordings only. No model calls, device access,
SSH/ADB, emulator interaction, production/test edits or child agents. The parent's separate
targeted validation remains authoritative for new outcomes. Last finalized Core6 result is
**8/20 combined, 11/20 independent state, zero false Done**; >=13/20 remains unmet.

## Evidence and comparison limits

Reviewed `CLAUDE.md`, `docs/CONTEXT.md`, `README.md`, `SPEC.md`, `docs/PLAN.md`,
`harness/README.md`, `core/README.md`, and the planner/action/filter/readback/host contracts.
Core6 ran source **`05ab6eb57408fbf3951ed6b2906eaab21c010534`**, tree
`4a21951b720cae8d53f9783672274d51a83e9eed`; its manifest pins AndroidWorld
`55ddaf5edc6f6c25673aed343907d5b9dca66f84`. The current diff changes reader correction and
Done verification, not planner history, keypad instructions, filter or Hands semantics.
These three Core6 tasks made **only planner calls**, so neither integrated correction directly
addresses their recorded failing path. This is source analysis, not a replayed outcome.

Original T3A JSON confirms 13 successes across 20 records. Specifically:

| Task | Original T3A | Core6 | What can be compared |
|---|---|---|---|
| Timer | FAIL, done false; independent state unrecorded; 10 steps/20 calls; **20:16:46** requested | state fail, done false; 10 steps/10 calls; **12:48:56** requested | Both lose numeric-entry progress; different inputs, not paired trials |
| Calendar add | state fail, done true; 14 steps/27 calls; **Oct 21, 20:00, 30 min** | state fail, done false; 34 steps/34 calls; **Oct 27, 13:00, 15 min** | Both mishandle time constraints; original success was never established |
| Camera | state pass, done true; 3 steps/5 calls; **one photo** | state fail, done false; 10 steps/10 calls; **one photo** | Same words, different initial observations/agent contracts; no isolated causal comparison |

T3A was unseeded; its selected records contain action/reason strings and generated summaries,
but no raw screen/message stream or source SHA. Do not treat summary assertions as actual app
state. The historical harness at `497223d3e436b2e46a34b144f5a3fcff7e00915a` computes
`success = bool(done and task.is_successful(env) == 1)`: Timer's `done=false` short-circuits
scoring. Its FAIL is certain, but an independent state failure is not recorded. Calendar and
Camera have `done=true`, allowing their false/true success fields to reflect checker outcomes.
This historical source explains the record format; it is not an attested baseline deployment pin.
In particular, Calendar's step 12 **assumes** a default 30-minute end time, never adjusts
it, then saves and declares completion. The checker fails; the exact saved mismatch cannot be
reconstructed independently from those summaries. T3A Camera's summary describes a new thumbnail
after one shutter action, then stops; it is text-only T3A despite the summary saying “screenshot.”

## What the traces actually support

**Timer: planning failure with visible progress, not stale action IDs.** Core6 steps 3–10 tap
digits `1,2,3`, backspace three times, then `1,1`. Before step 5, `e3` explicitly displays
`0 hours, 0 minutes, 12 seconds · 00h 00m 12s`; `e6` is clearly digit `3`. The reply nevertheless
says “Tap 3 for 12 hours 48 minutes 56 seconds.” Digit targets and backspace retain their IDs
through these steps. Before step 10 the display shows one second, yet the reply starts with
digit 1 again. First divergence occurs before eight-entry eviction. No schema rejection occurs.

T3A initially spells out the correct six-digit plan `201646` and summaries track the prefix.
It then backspaces at intermediate display `02h 01m 64s` rather than appending the final `6`.
That intermediate buffer is not the intended final duration. Richer prose history therefore
did not eliminate the same class of error; “check units after every tap” alone can invite
premature correction without a representation of expected **intermediate** values.

**Calendar: wrong quantity/field binding, then a persistent wrong anchor.** At step 12 the
planner selects `13 hours` in the start-time picker. Step 13 shows `0 minutes` selected and
`15 minutes` as an option; the reply calls that option a **duration**, taps it, then confirms.
At step 15 the form visibly reports **start 13:15 and end 13:15**, with separate code-owned
Start/End labels and source-tagged numeric observations. Step 18 calculates 13:30 while saying
the start is 13:00. Step 20 notices the wrong start in its `why`, but taps **End time e12**.
Later replies repeatedly anchor the requested duration to the wrong observed start. No Save
action occurs; final pre-action observation is still 13:15/13:15. This is not missing time
visibility. The modal picker lacks an explicit “editing start time” context; bare historic eN
references make that association harder, but incorrect current-screen decisions also persist.

**Camera: two distinct mistakes, and a count nuance.** Step 2 labels `e2` “Toggle mode list,”
yet the reply calls it a shutter. No Shutter is visible in that filtered observation. Step 4
actually taps **Shutter e2**. Step 5 newly shows **Last photo thumbnail e2**, shifting Shutter
to **e3**; the preceding history says only `tap e2 ... changed (elements_added)`.
Steps **5–10 are the six consecutive e3 shutter taps** in the parent diagnosis. Including step
4, the recorded current-target mapping shows **seven Shutter-targeted taps**, not a known photo
count. Steps 8–9 explicitly acknowledge repeated taps and unchanged screens, then choose another.
This rules out simple forgotten history as a sufficient explanation. No capture receipt, exact
actual photo count, persistence result or checker failure detail is present. Neither zero photos
nor seven photos is established. Thumbnail appearance is useful UI evidence, not exact-count proof.

**Interface deficits are concrete but not independently causal.**

The existing [planner prompt](../core/prompts/planner.txt) already says to check keypad values
and units, preserve all constraints, respect counts, and not repeat acknowledged creation merely
because results are invisible. Repeating these instructions is not the proposed improvement.

- [`agent.rs`](../core/src/agent.rs), `drive`: each call starts anew with request, apps, eight
  history lines, reader picks and current screen. `why` is log-only. Eviction can lose older
  context, but cannot explain the first Timer error or Calendar's retained wrong values.
- [`actions.rs`](../core/src/actions.rs), `describe`: history is a short action description
  (e.g. `tap e12`), not a grounded record of that target. [`screen_filter.rs`](../core/src/screen_filter.rs)
  renumbers each view. Camera's historic e2 means mode list, then shutter, then thumbnail;
  calendar e8 means different controls across form/hour/minute screens. Current-ID resolution
  works as designed; historical ambiguity is separate from a stale-ID execution bug.
- [`readback.rs`](../core/src/readback.rs), `compare`: reports fixed structural signals, not
  which meaningful control/value changed. It keeps no temporal field binding or effect count.
  It deliberately cannot turn `unchanged` into failed execution or `changed` into success.
- [`actions.json`](../core/schema/actions.json) expresses one immediate action, no numeric
  relation, pending field or plan cursor. All 54 selected Core6 actions are accepted, so JSON
  syntax/schema complexity is not the proximate failure here. All three finish on step budget.
- `confirmation_reason`/`GuardKey` track guarded inputs only; Shutter is outside that list.
  Its ordinary `host acknowledged input` does not trigger the twice-acknowledged stop. Adding
  every repeated tap to that guard would break legitimate repeated digits and other controls.

## Ranked candidate 1: grounded history and observation receipts

Replace, rather than append to, the eight bare action lines. Capture each action's meaning
**before dispatch**, with an observation sequence, local control handle, typed identity provenance,
execution status and existing readback. Resolve a handle against each fresh view only when unique;
otherwise mark unavailable. Historic handles never become legal current action IDs.

Example conceptual record: `step=4; tap control=h2; role=image; execution=executed;
effect=unknown; next_observation: control=last_photo_thumbnail appeared`.
Here `executed` means the existing Hands acknowledgment, not capture completion. Identity enums
must come from an existing code-owned control definition or supported structural contract;
**do not serialize arbitrary `Element.label`, resource strings, app text or model `why`** into
persistent history. Unknown identity gets an opaque handle and role. Private matching can retain
structural keys; model output receives only bounded code-owned terms and validated values.
Match package, resource/role and relevant container context, not position alone; invalidate on
ambiguous reuse, changed row identity or app transition rather than guessing continuity.
Annotate the uniquely matched current element with the same handle: current `e3` can then show
that `h2` was tapped, without storing its raw “Shutter” caption. Only the thumbnail already has
a code-owned label here; do not invent a new camera-specific identity profile for this experiment.
Spoofed/duplicate identities must not inherit another control's history.

Include at most the relevant target/value and one relevant transition per record: existing typed
calendar values retain `Source::App`, observation age and uncertainty. For a numeric modal,
carry the initiating identified field as a **pending association**, not proof of which field
the app will update; clear on cancellation, ambiguous transition or conflicting readback.
Do not turn data observed in an app into owner constraints, typed provenance or reader picks.
Current `Element.label` does not encode enough origin detail for a safe blanket-copy implementation;
explicit provenance plumbing is part of this candidate, not optional cleanup.

Test this representation first **without** a new dispatch veto, longer history, extra model call,
or relaxed verifier. It may make “already attempted” and picker context salient enough to help;
Camera's explicit repeated-tap replies are strong evidence it may not. If a later hard
repeat-mutation constraint is desired, it needs a supported operation effect class and owner count,
not `unchanged`, label guessing or a universal tap limit. An attempted-count ledger is not a
completed-count ledger. Do not bundle that policy change into the history experiment.

## Ranked candidate 2: bounded owner-derived numeric plan

Represent up to a small fixed number of owner quantities/relations, plus one active numeric edit,
instead of retaining unrestricted reasoning. Inputs are exact owner-request spans with validated
grammar, units and roles (`time_of_day`, `duration`, `count`, `start`, `end`); derived values carry
operand references and a code-checked operation (`add_duration`, unit conversion, digit formatting).
Use a deterministic recognizer for supported unambiguous forms, or proposals within the existing
planner call that code validates. Invalid/ambiguous relations remain absent; a plausible number
and an owner-span match alone do not validate its role. No extra planning call or free-text memory.

For this Calendar request the immutable desired relation is `start=13:00; duration=15min;
derived end=13:15`. Observed start=13:15 is a discrepancy, never a replacement operand. Reject
the unsupported interpretation “15-minute duration is the start minute” in this plan layer.
For Timer, preserve intended digit buffer `124856`, an acknowledged-input cursor, and predicted
intermediate buffer distinct from the final normalized duration. Advance expected progress only
after a compatible fresh observation; acknowledgment alone does not prove a digit landed. A
mid-entry `01:24:85` buffer can be expected even though 85 is not a final seconds component.
Do not normalize away these intermediate digits or clear them solely for exceeding 59.

Important boundary: owner arithmetic supplies goals, **not keypad semantics**. Shift-entry versus
separate-field input needs a supported control contract or a bounded transition hypothesis checked
against observations. Current Node data does not advertise that semantic contract. A model's
hypothesis stays advisory; it cannot authorize queued taps. Modal field bindings likewise need
grounding from candidate 1/current recognized controls. Unsupported widgets retain ordinary
planning. A finite UI numeric observation must use a full bounded grammar and retain app-source
taint; arbitrary digit extraction from app prose is forbidden.

Keep one current validated action per iteration and all current grants, cancellations,
confirmations, reader isolation and completion checks. The plan does not auto-dispatch a macro,
establish persistence, whitelist an app/task or write new action arguments from app content.
Arithmetic validation could reduce these two failure classes, but may still leave target choice
wrong; a saved plan is not evidence that the model will obey it. Prototype separately after the
history comparison, then test the combination only if individual results justify it.

## What prompts or memory cannot recover

The host's [`core_bridge.py`](../harness/core_bridge.py), `act`, returns `ok` after input transport;
it supplies no completed-capture receipt. Exact photo count/persistence requires a genuinely
permitted operation-bound receipt or structured readback, with source, freshness, app/operation
identity and accepted-versus-completed semantics. No such capability is advertised here. Do not
read the benchmark's privileged media/files state into production planning. Existing thumbnail
evidence can guide inspection, but a snapshot of a thumbnail cannot prove an exact count.

Similarly, calendar's omitted year stays unknown; entering values is not proof of a saved event.
The normalizer merges hint/description/tooltip into `label` and Node lacks showing-hint state:
the [Markor investigation](MARKOR_EXTENSION_OBSERVATION_20261107.md) explains why `.md` cannot be
certified as a current value. Node also lacks a numeric range-value contract. Neither a better
prompt nor a scratchpad can reconstruct these absent distinctions. Host changes would be a
separate capability/observation project, not inferred permissions or part of this documentation task.

## Controlled validation, same budgets

1. Before live work, test proposed projections on frozen traces and synthetic adversarial
   variants: ID renumbering; identical IDs in different apps; duplicate controls; injected
   captions/resource strings; stale/missing receipts; changed modal target; denied grants;
   cancellation; acknowledged versus unknown execution. Assert no new action authority or
   uncontrolled app text enters history. Replay with recorded replies tests plumbing only.
2. Freeze held-out nonbenchmark requests: varied countdowns including zero/repeated digits and
   intermediate overflow; alarm times; appointments with varied start/duration, midnight/date
   rollover and ambiguous locale; numeric forms with separate fields; legitimate repeated
   navigation/digits; one versus multiple requested creations and delayed/missing UI evidence.
   Unsupported arithmetic/time-zone cases must remain explicit, not guessed. Freeze requests
   before tuning; do not bake benchmark names, goals, timings or eN IDs into implementation.
3. Parent can compare current `4de7ade` against history-only, then numeric-only and a justified
   combination. Pair identical goals and initial states/seeds, counterbalance order and use a
   prespecified finite repeat count. Same model/configuration, 800/64/400 output caps, action/step
   limits, correction slots, retries, pacing and confirmation policy; no extra summary call.
   Keep original unseeded T3A as historical context, not the control arm. One-step prompt
   comparisons measure action selection only; closed-loop runs are needed for task outcomes.
4. Report independent state, accepted Done, false Done, unknown stops, acknowledged mutation
   attempts and **independently measured** duplicate effects separately. Include calls, total
   prompt/completion tokens and latency. Unknown photo counts remain unknown. Require zero new
   safety/provenance regressions and held-out gains before a full-suite claim; no guaranteed
   path from these logs to >=13/20 is established.

Token cost is material: Core6 Timer/Calendar/Camera mean prompt tokens were **2,851.6 / 3,266.2 /
2,592.2**; totals **28,516 / 111,051 / 25,922**. T3A totals were **172,909 / 342,585 / 21,072**
over 20/27/5 calls, including summaries. Thus smaller per-call context already loses on total
Camera cost. A prototype can budget eight compact history records and at most four constraints
plus one edit cursor; measure serialized characters and actual tokens, rather than claiming an
unmeasured token saving. Compare token-matched history formats as well as reporting deployment
cost. Same output/step caps do not imply equal prompt-token cost or equal realized correction calls.

## Reproducibility and hashes

All paths below are relative to `/home/wakeel/Desktop/ballistic/ballistic_runs/`.
SHA-256 values are of complete original bytes, not reserialized JSON:

```text
e5482714e08208be6b4a29d1712ded111b41ad035f7c3ed40aea6f07e5a9f148  core6_05ab6eb/ClockTimerEntry/20261107-083805_ClockTimerEntry.json
d0e77f6fc50a03f49ec3b912ebefcb8b606293b55e6f93d94b2155365df5ff9f  core6_05ab6eb/SimpleCalendarAddOneEvent/20261107-085242_SimpleCalendarAddOneEvent.json
5c2e94fc14ffac5de36b0a74b02ec81af3e4509362e9c33d035060a0d60d8021  core6_05ab6eb/CameraTakePhoto/20261107-090455_CameraTakePhoto.json
d61983df5839db8ef6603236ec0b3ba5ba157cac00f3b25ab718ec7c8413ff5e  core_suite_20261107/baseline/20261106-181654_ClockTimerEntry.json
42bebcfb58f80ff46848306e25c0a7371076fbf95c38eb01e9669f366d1909d2  core_suite_20261107/baseline/20261106-183003_SimpleCalendarAddOneEvent.json
56714547f7145662e1c747a5740fa349760501d33f5cb6bc55e83b338ce09110  core_suite_20261107/baseline/20261106-184740_CameraTakePhoto.json
0bacedd34cc662843bc9bf0afcacc6c9a71fcd12f6ccc1bcc03c550679cacd8f  core6_05ab6eb/report.txt
ed1b5fa83e70edbc20533da5270fda519d97e98cdb9771a9b8d3e9234da5c8d8  core6_05ab6eb/diagnoses.json
8bf55082d414576d4f8410df42010e9c8529136e1e5dd41c12ed56984a2ce82d  core6_05ab6eb/manifest.json
```

Reproduce locally with Python standard library only. This reads files, associates each accepted
action with its **pre-action** target line, and prints raw replies; it executes no trace actions.
The one-call-per-step assertion is specific to these three recordings, not a general replay rule.
The embedded analysis was executed successfully; all nine hashes above were separately checked.
Its output is retained in ignored `ballistic_runs/planning_context_audit_20261107/analysis.txt`
in this worktree. No runtime tests were run for this documentation-only change.

```python
from pathlib import Path
import hashlib, json

root = Path('/home/wakeel/Desktop/ballistic/ballistic_runs')
tasks = ('ClockTimerEntry', 'SimpleCalendarAddOneEvent', 'CameraTakePhoto')
baseline = root / 'core_suite_20261107/baseline'
records = [json.loads(p.read_text()) for p in sorted(baseline.glob('*.json'))]
assert len(records) == 20
print('Original T3A successes:', sum(d['success'] for d in records), '/20')
for group in (root / 'core6_05ab6eb', baseline):
    for task in tasks:
        paths = list(group.rglob('*_' + task + '.json'))
        assert len(paths) == 1
        path = paths[0]
        data = path.read_bytes()
        d = json.loads(data)
        print(hashlib.sha256(data).hexdigest(), path.relative_to(root))
        print(d['goal'], 'success=', d['success'], 'done=', d['done'])
        print('steps/calls:', len(d['steps']), len(d['model_calls']),
              'prompt tokens:', sum(c['prompt_tokens'] for c in d['model_calls']))
        if d['agent'] == 't3a':
            for step in d['steps']:
                print(step['step'], step['action'], step['summary'])
            continue
        calls = [x for x in d['lines'] if x.get('ask') == 'complete']
        assert all(x['role'] == 'planner' for x in calls)
        assert len(calls) == len(d['steps'])
        replies = {x['id']: x for x in d['lines'] if x['dir'] == 'reply'}
        for step, call in zip(d['steps'], calls):
            target = step['action'].get('target')
            state = call['messages'][1]['content']
            screen = state.split('\nScreen ', 1)[1]
            label = next((s for s in screen.splitlines()
                          if target and s.startswith(target + ' ')), None)
            print('step', step['step'], 'target at dispatch:', label)
            print('raw reply:', replies[call['id']]['data']['text'])
            # Uncomment to inspect exact supplied history and visible values:
            # print(state)
```
