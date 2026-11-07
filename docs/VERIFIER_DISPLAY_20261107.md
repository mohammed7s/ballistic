# Done verifier: displayed items and composers

Baseline: `e307e01` (code `05ab6eb`). Offline investigation only. No model, device,
SSH, ADB, credentials, benchmark oracle or OS/build-server access. Scope is the Done
verifier instructions and focused tests; planner/reader work belongs to another task.

## Record findings

Read the complete JSON port exchanges, observations, model inputs/outputs and final
reports in `/home/wakeel/Desktop/ballistic/ballistic_runs/core6_05ab6eb/`:

- `SimpleSmsSend/20261107-084702_SimpleSmsSend.json`, SHA-256
  `4630ffbf8683e000707dc52e6620291542a971eda039c1bb4de5afb078b2acb5`.
- `SimpleSmsReplyMostRecent/20261107-084908_SimpleSmsReplyMostRecent.json`, SHA-256
  `04f97bf320b7d1dec133749b280d395eecb17d30f204e0a0355d45c29c9de5bf`.

Send's port 20 acknowledges the approved Send tap. Observation 21 then shows the
requested body as a noneditable TextView (raw node 13), separate from the editable
composer (raw node 18, showing its hint). Done gets new observations 23, 26 and 29.
The three verifier calls (24, 27, 30) see readable `e2 text` recipient, `e9 text`
matching body, `e12 edit ... [editable]` and `e13 ... [disabled]`. All return
`unknown`, with a reason inferring that disabled Send means the message is unsent.
The record ends with planner give-up. This establishes a faulty inference, not that
the verifier should return verified: no readable sent/delivered status is present.
The `(typed)` annotation on the recipient describes string provenance; its current
node is a text display, not an editor. Resource IDs do not fill the status gap.

ReplyMostRecent has **zero verifier calls**. After the approved Send at port 18,
observation 19 exposes the body on noneditable raw node 16, the composer hint on
editable node 21, and disabled Send on node 22. The planner repeatedly tries Send,
then focuses the editor, presses Enter (leaving a newline), and repeats Send. The
second acknowledged Send trips the existing repeat guard. This is a planner
confusion and is outside this candidate. Its current filtered thread also hides
the recipient and does not establish the most-recent constraint; even a hypothetical
Done must not infer that constraint from a historical reader pick or model claim.

Independent state scores supplied in the task brief do not enter these conclusions
or production evidence. Neither record establishes delivery to a recipient. No
claim of duplicate absence or live improvement follows from these observations.

## Frozen boundaries before production changes

Synthetic development cases vary names, node IDs, roles, packages and layouts.
These are prompt delivery and regression checks, not a semantic model evaluation.
Scripted verdicts are inputs, never measurements of Gemini following instructions.

- A matching read-only text/item in a saved list, separate empty editor and disabled
  submission control: the displayed item remains eligible readable model evidence
  for a request to find that item. Both text and item roles are exercised. The
  verifier receives instructions distinguishing display, editability and provenance.
- Draft editor only, disabled control only, or empty editor plus disabled control:
  scripted unknown remains unknown; these signals do not establish a saved/sent result.
- Readable wrong recipient: scripted failed stays failed. Hidden recipient or missing
  requested date/status: scripted unknown stays unknown. Body alone is insufficient.
- Stale matching item replaced by opaque hostile content in the fresh observation:
  an old eN citation cannot certify it; the new view alone reaches the verifier and
  hidden text/planner claims/verifier prose stay out of protected follow-up messages.
- Existing `completion_visibility` V3–V6 retain hostile/opaque, mixed citations,
  anonymous switch, nonexistent/noncanonical/duplicate citations and unsupported
  evidence-basis checks. V7–V8 retain fixed feedback and cancellation checks.

All requested postconditions, current readable citations and existing failed/unknown
parsing remain required. No application, resource or task whitelist, history input,
receipt, delivery inference, launch grammar, action, approval, policy, retry or budget
change is part of this candidate. Missing status or target identity remains a reason
for unknown. Even with valid citations, semantic coverage is still model judgment;
the parser does not implement a per-field proof system.

## Candidate and offline validation

Extracted the Done system prompt into `core/prompts/verifier.txt`, included at the
same call site in `core/src/agent.rs`. It now explains roles versus provenance,
displayed matches versus drafts, and why disabled submission controls are neutral
in isolation. The complete-postcondition rule remains intact, with explicit unknown
for unobservable identity/status/constraints. The existing current-screen-only
input and 400-token output cap are unchanged. Prompt size grows from 726 to 1,561
characters (+835); provider token cost and latency are unmeasured.

Before the production edit, the new three-test file passed two tests and failed
only the assertion requiring the new instruction at the verifier port. Existing
completion visibility tests passed 8/8. That failure establishes missing instruction
delivery, **not** incorrect model behavior on the synthetic examples. After the
edit, all new tests pass, including both displayed roles and the typed provenance
check. Scripted unknown/failed verdicts remain unchanged; the stale opaque citation
is rejected by the existing parser. No verdict parser or filter code changed.

Commands run locally from `core/`, with Cargo offline:

```sh
cargo test --offline --features uniffi --test verifier_display --test completion_visibility \
  --test agent_checks --test readback_and_reader
cargo test --offline --features uniffi
```

Focused result: **47 passed**. Full result: **273 passed, zero failures, ignored or
filtered tests**, including the UniFFI boundary tests and existing guard, cancellation,
launch, reader isolation and citation regressions. `git diff --check` also passes.
Python/JVM tests were not rerun: no host implementation, schema or ABI changed.

The positive fixtures ask to find a displayed saved item; they do not fabricate a
send receipt or establish that a matching message body is sufficient to verify Send.
Negative semantic cases supply their unknown/failed replies explicitly. These checks
establish delivery, isolation and regression coverage only. The model can still
produce a semantically wrong verdict with readable citations, and these tests cannot
show whether this prompt improves or harms Gemini accuracy. The recorded Send may
properly remain unknown for missing status, and ReplyMostRecent's planner failure
is untouched. No new task score, 13/20 result, safety/generalization claim or delivery
claim is made. Live validation with the same model, seed and budgets remains outside
this offline authorization.
