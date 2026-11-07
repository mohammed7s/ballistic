# Terminal owner summaries: offline development record

Base: `306919790e6f548f0568f40c3c5a7418547d629b`, isolated worktree. Parent is evaluating
that frozen base separately. No emulator, device, SSH, ADB or model endpoint is used here.
`docs/CONTEXT.md`, injection fixtures/checkers and benchmark budgets remain untouched.

## Frozen before implementation

`core/tests/cases/owner_summaries.json` and `core/tests/owner_summary.rs` were written before
production changes. They exercise the actual Rust drive through separate scripted ports and
the JSON-lines bridge. Sources include ordinary and hostile messages/notes, times/platforms,
amounts, a URL, French source text, and multiple cited sources. None imports injection fixtures.

Baseline command: `/home/wakeel/.cargo/bin/cargo test --manifest-path core/Cargo.toml --test owner_summary -- --nocapture`.
Result on unchanged production code: **1 passed, 9 failed**, zero ignored. Arbitrary planner
Answer is already unsupported (pass). Useful summaries, strict reader shape rejection, reader
errors, stale content, Stop during reader/freshness/publication, compound-task noncompletion,
unsolicited-summary refusal and terminal bridge output fail their required contracts because
summary reads do not call the reader or publish owner output. The bridge case fails on its
scripted freshness reply being consumed as another planner reply. These are development tests,
not independent evaluation or evidence of model quality.

## Implementation contract

The intended minimal channel is a bounded extractive summary: a model selects short exact
excerpts and cites requested content refs. Code binds each excerpt to the actual filter source
and checks fresh raw screen identity before publication. Exact excerpts make every displayed
number/link attributable without trusting a model's source labels. This does **not** establish
truth, relevance, completeness, latest-message identity, or semantic entailment in context.

Owner output is terminal and separate from planner Answer, reader element picks and general
facts. It grants no action or confirmation. Reports keep `done: false`, task pending and
verification unknown even for useful output: selecting visible content does not establish the
whole request, especially a compound action task. Host-visible output status distinguishes
available output from rejection, failure, stale evidence and unsupported requests.

## Implemented and reviewed offline

The frozen baseline is commit `1ea683f`. All ten frozen contracts now pass; seven supplemental
drive/bridge tests cover limits/grants, source substitution, observation failure/identity,
numeric/URL token boundaries, action JSON, correction prompts, prior execution and positional
JSON arrays. The latter matters because serde structs otherwise accept array representations.
The final schema explicitly requires objects and then parses the original bytes to reject
duplicate keys. No benchmark fixture or checker was changed.

`reader/summary.rs` owns bounds, parsing, exact excerpt checks, filter-derived provenance,
freshness and final plain-text rendering. `agent.rs` uses one existing 64-token reader call,
checks cancellation before/after it, checks fresh raw observation and returns before another
model/action/confirmation. Even failed attempts terminate. Summary text stays out of the step
event, history, correction prompts and element picks. The only prose-bearing publication is the
terminal owner report. `Answer` and general `facts` remain restricted as before.

The whole owner request goes to the separate reader; the planner's question cannot replace it.
An explicit English summary imperative at a sentence boundary opts into display only. This
syntactic gate does not establish semantic task coverage: every available summary remains
`done:false`, pending, verification unknown. Existing earlier execution status is preserved.
The new evidence basis is `model_selected_source_excerpt`, never independent device evidence.

The generic report's `reason` carries labeled quotations and full filter evidence. Android's
existing `RecordJson` deliberately tolerates additional record fields, so the structured
`owner_output` need not be a new executable/mirrored type. Existing plain `Text` displays the
reason; the label now distinguishes excerpts from accepted input. The CLI presentation helper
preserves the full bounded owner output rather than truncating it to 300 characters, escapes
terminal controls and performs no URL operation. No FFI signature/binding changes were needed.

## Validation

- Rust: `cargo test --manifest-path core/Cargo.toml --features uniffi --quiet`:
  **190 passed, zero failed/ignored**, including all prior element reader, Markor, Calendar,
  Settings and cancellation regressions. Frozen summary comparison: **1/10 → 10/10**.
- `cargo build --manifest-path core/Cargo.toml --features uniffi --lib --bin ballistic-core`:
  native host library and bridge binary build successfully.
- Python: `BALLISTIC_TEST_CORE_BIN=<this worktree>/core/target/debug/ballistic-core
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s harness -p 'test*.py'`:
  **87 passed, zero skips**, including the actual offline Rust subprocess protocol tests and
  two terminal-presentation tests. Printed injection ERROR/lock diagnostics are expected fake
  test cases, not an emulator run or an actual lock acquisition.
- JVM command attempted: `./gradlew :core-api:test --offline --max-workers=2` in
  `apps/assistant`. **Could not start:** `JAVA_HOME is not set and no 'java' command could be
  found in your PATH.` No JVM pass is claimed. A new scripted UniFFI test covers terminal
  report decoding/rendering and Stop during the reader. Parent must run it with a JDK and
  `BALLISTIC_HOST_CORE=<this worktree>/core/target/debug/libballistic_core.so`. No APK built.
- `git diff --check` passes. `docs/CONTEXT.md` is unchanged. No push/merge, emulator, SSH,
  ADB, live model call, physical phone or ballistic-os changes.

## Strict limits and objections for parent review

This is **extractive summary only**: 1–4 refs, one contiguous 1–240-character excerpt per ref,
4,096 characters per source, 8,192 source characters total, 2,048 reply bytes. General facts,
paraphrases, translation and arbitrary answers are unavailable. Excerpts exclude control
characters and braces/brackets; some legitimate text (including bracketed URLs) is unsupported.
No input is silently shortened. Reader output is still capped at 64 tokens; larger/multisource
responses may truncate and fail closed. Scripted success does not measure model adherence,
relevance, tokenization, latency, multilingual selection or live utility at that cap.

Owner opt-in wording is limited to English `summarize`/`summarise` imperatives, optionally
`please`; a bare “what did I miss?” is not supported. Source text is Unicode and one French
case passes with a scripted excerpt, not a language-coverage claim. The syntax gate can match
an imperative embedded in a more complex owner request, so it never decides task completion.
Any extra action requirements remain unresolved; no general natural-language intent proof is
claimed. Source selection belongs to the existing planner and is not a verified relevance or
latest-message oracle. Navigation before summary follows existing policy; the new channel
does not implement a global read-only policy for the earlier part of a run.

Source citations and exact membership do not prove truth or even a faithful contextual summary.
The model can select a misleading fragment, omit a negation, or quote hostile natural-language
instructions. Those remain inert owner-visible data; they cannot reach subsequent tools/models
because there are none. Full source evidence is labeled and quoted, but can itself be false or
hostile. Existing injection `report_must_mention` checks may find a phrase in quoted evidence;
that alone cannot establish that the **excerpt** is useful. Parent review/live evaluation must
inspect `owner_output.items[].text` and source selection, not infer quality from that assertion.
This patch does not alter or claim passing the live injection checks.

Only content present in the current host observation is covered; app previews may be truncated.
Even all selected refs are not proof of all notes, the latest message, or the complete requested
document. Every raw node must remain exactly unchanged at the fresh observation, so unrelated
UI changes can reject useful summaries. There is no host sequence/freshness proof for arbitrary
content, and no protection against a dishonest host, unseen intervening changes or changes
after the last observation. Cancellation has the existing cooperative port boundary: it does
not abort an in-flight model/host call; a received Stop suppresses later dispatch/publication at
the checks. Cross-thread host/UI publication races outside those checks remain host concerns.

**Parent review, freshness scope:** the whole-screen comparison is intentionally retained in
this focused patch. A clock/battery change can reject an otherwise unchanged source. Current
`ContentRef` records the folded anchor node and assembled text, not all contributing raw node
identities; `ReaderPick` compares normalized content, so reusing it alone would weaken exact
raw-byte freshness (the whitespace-change regression demonstrates that distinction). A later
bounded refinement should have the filter retain raw contributor identities and context for
each requested ref, reject ambiguous relocation, and compare those exact bytes plus grants.
This patch does not substitute normalized equality or silently ignore folded contributors.
Parent will integrate its collection fix first; this branch remains separate from the imminent
full-suite pin and claims neither general reader summaries nor semantic provenance.

Per-app provenance is only as specific as the existing filter: `app:<package>` plus cN, node and
exact evidence. No model-supplied sender/filename is trusted. Full provenance propagation,
Rule of Two, deny-only judge and semantic action authorization remain unfinished. The display
exception is explicitly documented in SPEC §7 and core README §7, preserving the arbitrary
planner Answer restriction. Parent reviews after its `7a9238e` full-suite snapshot; these
changes have no live score and must not be attributed to that frozen run.


## Independent integration review and corrections (7 November 2026)

Astra review integrated the two candidate commits on `7a9238e` as `28b19a7`, without conflicts:
205 Rust/UniFFI, 88 Python and 45 JVM tests passed with zero failures/skips. JVM tests used the
available local JDK and rebuilt host library; the original author's unavailable-Java result
above is superseded for this reviewed integration. Eighteen additional scripted probes passed,
covering folded-source identity, grants, duplicate keys, host reuse and full evidence display.
No blocking security finding was identified within this limited extractive-output contract.
The review did not establish live usefulness, general summarization or semantic provenance.

Parent addressed both review findings before integration: overlapping exact excerpts are now
searched at every character boundary (a real ASCII/Unicode regression fails before the fix),
and docs distinguish one core reader call from the host's existing HTTP retry policy. Neither
change expands token caps, action authority or retry eligibility. The complete review and
probe artifacts are retained under ignored `ballistic_runs/owner_review_offline/`.
The current live full-suite binary remains pinned to `7a9238e`, without this feature.

Parent final combined checks after those corrections: **206 Rust/UniFFI, 88 Python and
45 JVM tests pass, no failures or skips**. Both Python binary variables select the freshly
rebuilt core; JVM tests select the rebuilt host library and local JDK. This adds one overlapping
excerpt regression to the reviewed integration's 205 Rust tests. No APK or live test ran here.
