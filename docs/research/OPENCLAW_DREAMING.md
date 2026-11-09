# OpenClaw "dreaming": what it is and what Ballistic can take

Research note, 9 November 2026. Web sources only; no OpenClaw code was run. Facts carry source
links and evidence qualifiers. Proposals are marked as proposals and are not decisions (decisions
live in `docs/CONTEXT.md`). Nothing here is implemented in Ballistic.

Evidence qualifiers used below: **primary** = OpenClaw's own docs, changelog or GitHub security
advisory; **secondary** = third-party blog, vulnerability database mirror or preprint;
**unverified** = a single source or a claim that sources contradict.

## 1. Facts: what OpenClaw's dreaming is

### 1.1 Naming and provenance of the project

- OpenClaw is the third name of one project: first Clawdbot (published November 2025), renamed
  Moltbot on 27 January 2026 after a trademark notice from Anthropic, then OpenClaw on
  30 January 2026. Author: Peter Steinberger. Secondary sources, which agree on the sequence:
  [DEV: Clawdbot vs Moltbot vs OpenClaw](https://dev.to/zacvibecodez/clawdbot-vs-moltbot-vs-openclaw-are-they-the-same-thing-31ff),
  [morphllm](https://www.morphllm.com/clawdbot). Reasons for the second rename differ between
  sources. One source says OpenAI hired the author in February 2026 (unverified; not needed here).
- "Dreaming" is a feature name, not a project name. It shipped in `2026.4.5` labelled
  "Memory/dreaming (experimental): add weighted short-term recall promotion, a `/dreaming`
  command, Dreams UI" ([release v2026.4.5](https://github.com/openclaw/openclaw/releases/tag/v2026.4.5),
  primary). I did not find an announcement post by the author himself; the primary record is the
  docs and changelog.

### 1.2 What it is

"Dreaming is the background memory consolidation system in `memory-core`" (the bundled memory
plugin). It turns short-term recall signals into durable long-term memory (`MEMORY.md`) and keeps
each step explainable
([docs: Dreaming](https://docs.openclaw.ai/concepts/dreaming.md), primary). It writes **memory
and a human-readable diary only**. In the docs I read it does not write skills, code, prompts or
policy. (Separate OpenClaw "skills" are authored by the user or fetched from a hub; dreaming does
not generate them per the docs.)

### 1.3 How it runs

- **Trigger and schedule.** One managed cron job runs a full sweep, default `0 3 * * *` (3 a.m.,
  timezone configurable). Manual triggers: `openclaw memory promote [--apply]`,
  `promote-explain`, `rem-harness`, `rem-backfill`, `session-backfill`; chat command
  `/dreaming status|on|off|help`. Cron/heartbeat sessions are not durable inputs.
  ([docs](https://docs.openclaw.ai/concepts/dreaming.md), primary)
- **Three phases per sweep, in order: light, REM, deep.**
  - Light: dedupe recent short-term material, stage candidates, record reinforcement signals;
    never writes `MEMORY.md`.
  - REM: build theme and reflection summaries; never writes `MEMORY.md`.
  - Deep: rank candidates and consolidate them against current memory; the only phase that writes
    `MEMORY.md`.
- **Ranking.** Candidates must pass `minScore`, `minRecallCount` and `minUniqueQueries` together.
  Weights: relevance 0.30, frequency 0.24, query diversity 0.15, recency 0.15, consolidation 0.10,
  conceptual richness 0.06. The docs do not publish default values for the three thresholds.
- **Inputs.** Short-term recall state, daily memory files, and *interactive* session
  transcripts only. Cron, heartbeat, sub-agent and unknown sessions are excluded. Recalled
  context marked by the runtime is stripped so it is not re-learned. "Transcript text is not
  secret-masked before ingestion." So API keys or personal data pasted in chat can reach the
  consolidation prompt.
- **Outputs.** SQLite plugin state (recall store, phase signals, ingestion checkpoints, locks and
  a pre-image of the previous `MEMORY.md` saved before each accepted rewrite); `DREAMS.md` (the
  "Dream Diary" plus Light/REM/Deep summaries, for human reading, never a promotion source);
  optional per-phase reports under `memory/dreaming/`; and `MEMORY.md` entries, each with up to
  three concept tags, an importance score 1-10 and a mandatory `Source: path#Lx-Ly` reference.
- **Model.** Fresh, tool-free completions with no retained chat session. Default is the workspace
  agent's model; `dreaming.model` overrides it, and that requires
  `subagent.allowModelOverride: true` (with an `allowedModels` allowlist). The deep phase asks the
  model for *operation decisions* (add, merge, supersede), not replacement prose. If the model is
  unavailable or the result fails validation it falls back to the older append-only promotion.
  Runs at background priority within `subagents.maxConcurrent`.
- **Default state: the docs contradict each other across versions.** The 2026.6.6 docs
  ([unpkg](https://app.unpkg.com/openclaw@2026.6.6/files/docs/concepts/dreaming.md)) say
  "Dreaming is opt-in and disabled by default" and list `enabled` default `false`. The current
  docs and the 2026.11.1 package docs say "Dreaming is enabled by default" with `enabled` default
  `true` ([docs](https://docs.openclaw.ai/concepts/dreaming.md),
  [unpkg 2026.11.1](https://app.unpkg.com/openclaw@2026.11.1/files/docs/concepts/dreaming.md)).
  I did not find the changelog entry that flipped it, so the date is unknown. Many third-party
  guides (written in April 2026) still say opt-in. Treat "default-on today" as primary but
  of unknown start date.

### 1.4 How it is bounded

Bounds in the docs (primary):

| Bound | Value |
|---|---|
| Max share of prior `MEMORY.md` entries a rewrite may remove | `maxPriorEntryLossFraction` 0.25; larger rewrites rejected |
| Per-snippet size | `maxPromotedSnippetTokens` 160 |
| Provenance of every promoted item | required `Source:` pointer; snippets re-read from live daily files, so deleted or stale text is skipped |
| Output shape | rewrite must parse as expected structure and fit the `MEMORY.md` bootstrap budget |
| Rollback | pre-image of `MEMORY.md` saved before every accepted rewrite |
| Model access | tool-free completion; background priority; concurrency cap |
| Who can toggle | owner (channel) or `operator.admin` (gateway) |
| Inputs excluded | tool output, web content, non-owner turns (session backfill); candidates with `untrusted` or `system` provenance removed *before* the consolidation prompt is built ("structural exclusion, not a score penalty") |

**Cost.** Not bounded by a token or money budget that I could find; it uses the agent's (cloud)
model by default, and I found no published per-run cost. **Privacy.** Data stays in the user's
workspace but the transcripts go to whatever model the agent uses, unmasked. Directories are
created `0700`.

### 1.5 Measured benefit

I found **no measured benefit for OpenClaw dreaming**: no benchmark, no before/after task
success. The one detailed review reports none, and its author did not enable dreaming for
himself, preferring manual curation; he names noise (low-value entries polluting later
sessions) and a scorer that cannot tell tactical notes from strategic decisions as the risks
([DEV: OpenClaw Dreaming explained](https://dev.to/michael_xero_ai/openclaw-dreaming-explained-how-ai-memory-consolidation-actually-works-4m2l),
secondary, opinion, promotional). Positive coverage is about inspectability (the diary shows what
it kept), not outcomes
([hongkiat](https://www.hongkiat.com/blog/?p=74285), secondary).

### 1.6 Known problems and security

- **GHSA-62qm-6fjj-6g23 / CVE-2026-100535 (High, CVSS 7.5 v3, 7.7 v4; fixed in 2026.8.1;
  affects 2026.4.5 up to 2026.8.1).** "Dreaming could widen restricted sender authority": session
  text from a restricted external sender could be saved without its untrusted marking; a later
  unattended dreaming agent with broader file and command access could act on it. Interim
  mitigations given: disable dreaming for agents with restricted external senders, disable
  session-memory capture, run background memory processing without file or command tools.
  ([GitHub advisory](https://github.com/openclaw/openclaw/security/advisories/GHSA-62qm-6fjj-6g23),
  primary; [Mend](https://www.mend.io/vulnerability-database/CVE-2026-100535/), secondary.) This
  is the exact failure Ballistic's design must avoid: taint lost on the way into memory, then a
  later privileged run follows it.
- **Authorization bug on the toggle.** Between 2026.4.10 and 2026.7.1 a non-owner sender on an
  external channel could run `/dreaming on|off` (CVSS 5.3; GHSA-22v4-33m3-8p7m)
  ([VulnCheck](https://www.vulncheck.com/advisories/openclaw-before-2026.7.1-authentication-bypass-via-memory-dreaming),
  secondary summarising a primary advisory).
- **Poisoning through memory, demonstrated.** IronCore Labs (26 August 2026) sent emails to an
  OpenClaw agent; direct injection failed, but fragments were saved to daily memory with the
  untrusted flag dropped; about four spaced emails calling the attacker address "internal" got it
  trusted in `MEMORY.md` and `USER.md` (an hourly summary cron job did this; dreaming was
  described as an additional route), after which the agent hid the attacker's mail and forwarded
  everything else. OpenClaw version not stated; disclosure status not stated; tested in early
  April, before the fixes above
  ([IronCore: prompt laundering](https://ironcorelabs.com/blog/2026/prompt-laundering/),
  secondary, single practitioner).
- **Persistence.** Cloud Security Alliance note (13 June 2026; does not mention dreaming):
  `MEMORY.md` is loaded into system context, so the agent treats it as authoritative; restoring
  the file may not remove the compromise if vector indexes keep "behavioral residue"
  ([CSA](https://labs.cloudsecurityalliance.org/research/csa-research-note-openclaw-indirect-prompt-injection-2026061/),
  secondary). The OpenClaw provenance doc concedes provenance-gated promotion "does not
  establish immunity to every poisoning attack" and that "forgetting" is not erasure
  ([provenance doc](https://docs.openclaw.ai/start/why-openclaw/provenance), primary).
- **Residual design weaknesses visible in the docs themselves** (my reading, not a source's
  claim): the consolidation is model-written prose in a file that is later trusted context; the
  exclusion of untrusted candidates depends on the provenance label surviving every hop (the
  CVE is the case where it did not); transcripts are not secret-masked; no cost cap.

## 2. Facts: comparable ideas elsewhere

- **Sleep-time compute** (Lin, Snell, Wang, Packer, Wooders, Stoica, Gonzalez, 17 April 2025):
  a model processes context offline before any query. On modified Stateful GSM-Symbolic and
  Stateful AIME it cut test-time compute for equal accuracy by about 5x; scaling sleep-time
  compute raised accuracy by up to 13% and 18%; amortising over several related queries cut cost
  per query 2.5x; gains correlate with how predictable the queries are
  ([arXiv:2504.13171](https://arxiv.org/abs/2504.13171), preprint; reasoning benchmarks, not
  phone agents). The OpenClaw dreaming doc cites this paper.
- **Letta sleep-time agents.** A second agent edits the primary agent's in-context memory
  blocks in the background; the primary agent cannot edit its own core memory; the sleep-time
  model is configured separately (a larger, slower model suggested); frequency configurable by
  steps or context compaction; optional "agent reviews before applying" pass; memory is a
  git-backed filesystem
  ([Letta blog](https://www.letta.com/blog/sleep-time-compute),
  [Letta docs](https://docs.letta.com/guides/agents/architectures/sleeptime), vendor claims).
- **Voyager** (Wang et al., 2023): a growing library of executable skills verified by
  environment feedback and self-verification before storing; 3.3x more unique items, 2.3x longer
  travel, tech-tree milestones up to 15.3x faster than prior best, in Minecraft
  ([arXiv:2305.16291](https://arxiv.org/abs/2305.16291)). Skills are code the model wrote, which
  Ballistic must not copy (see 4.2).
- **Agent Workflow Memory** (Wang, Mao, Fried, Neubig, 2024): induces reusable workflows from
  past trajectories, offline or online; 24.6% (Mind2Web) and 51.1% (WebArena) relative success
  improvement over baselines, with fewer steps
  ([arXiv:2409.07429](https://arxiv.org/abs/2409.07429)). Web, not phone; results are the
  authors'.
- **Mobile-Agent-E** (2025): persistent "Tips" (general lessons) and "Shortcuts" (reusable
  executable sequences of atomic operations) evolved from earlier tasks; 22% absolute gain over
  prior state of the art for the whole framework, not isolated to the memory module
  ([arXiv:2501.11733](https://arxiv.org/abs/2501.11733)).
- **Generative Agents** (Park et al., 2023): periodic synthesis of observations into
  higher-level reflections; ablation says reflection contributes to believability
  ([arXiv:2304.03442](https://arxiv.org/abs/2304.03442)). The ancestor of the light/REM idea.
- **Attack side: MINJA** (Dong et al., 2025) plants malicious records in an agent's memory using
  only ordinary queries, so memory that learns from interaction is writable by anyone who can
  interact ([arXiv:2503.03704](https://arxiv.org/abs/2503.03704); the abstract gives no rates).

## 3. What the facts say about the idea (summary judgement)

The useful part of dreaming is structural, not the metaphor: consolidation done **offline, in
bounded batches, on logs, with a separate review step**, producing proposals that cite their
source. The risky part is also structural: model-written text that lands in a file the agent
later trusts. OpenClaw's own CVE shows the failure mode, and its fixes (provenance outside the
text, structural exclusion of untrusted candidates, preimage rollback, source pointers, loss
caps) are the controls Ballistic already has the vocabulary for. Ballistic's planner never sees
app content, so most of the dangerous path (content, then memory, then instructions) is already
closed by construction; what must be designed is a consolidation pass that does not reopen it.

## 4. What Ballistic could take (proposals, not decisions)

Premise: Ballistic's learning today is exact-request record/replay saved only after a verified
`done` (`core/README.md` §11). Everything below is a *proposal generator* that sits beside that
path and never replaces its checks.

### 4.1 Proposal A: an idle, charging, on-device "review pass" over the run log

- **When.** Phone idle and charging, owner-enabled (off by default; a visible switch, not a chat
  command a third party could send), run as a background job with a hard cap on model calls and
  wall time per pass. Skip if the on-device model is busy; never use a cloud model unless the
  owner has already routed the planner to one.
- **Inputs (closed list).** Only core-written log lines: per step the action, source tags of
  values, judge verdict, policy decision, confirmation result, outcome, `readback` word, failure
  category, evidence basis (`core/README.md` §13), plus the skill-replay `stopped_at` markers.
  **Never** screen text, reader prose, content references' bodies, answers or summaries. This
  keeps the pass inside "the planner never sees app content" because nothing it reads contains
  any.
- **Outputs (typed candidate records, never prose that becomes instructions).**
  1. *Skill candidates*: a request pattern with several verified runs that share a step
     sequence, proposed as a template with owner-only slots.
  2. *Code-owned label candidates*: an anonymous control seen in failed runs, with package,
     resource id, role, parent and slot, in the same form as `KNOWN_CONTROLS` entries.
  3. *Failure summaries*: counts by `failure_category` and screen signature ("replay stopped at
     step 3 of skill X after app update"), a number table, not narrative.
- **Gate.** A candidate is inert data in a candidate store. It becomes usable only by (a) owner
  approval on a window the agent cannot press, **and** (b) for skills, a verified replay or
  verified live run (the existing done check) before it is stored as a skill. A label candidate
  becomes a `KNOWN_CONTROLS` entry only by a human-reviewed code change (it is policy-adjacent:
  a label decides what the planner can see), never by on-device write.
- **Why this is not dreaming's failure mode.** No free-text memory file is loaded into any
  prompt. The pass's output is structured records validated by code (schema, length caps,
  allowed fields), each with a pointer to the run ids it was derived from, and a
  pre-image/rollback of the candidate store.

### 4.2 Proposal B: safety rules for any consolidation pass (the part to adopt unconditionally)

1. Untrusted content never becomes an instruction. Consolidation inputs are an allowlist of
   core-written fields (above); a field that can carry app, message or web text is not on it.
2. Anything derived from content keeps its source tag through the pass and into the candidate
   (`app:<package>`, `sms:<sender>`, ...). A candidate with any non-`owner:*`/`screen:label`
   value in a slot is dropped, not scored lower. This mirrors OpenClaw's structural exclusion and
   core's existing rule that only `owner:*` values may become skill params (§11).
3. Provenance lives outside the text and is set by code, never by the model. The OpenClaw CVE
   happened because the label was lost in persistence; add a test that a tainted run cannot
   produce a candidate, and a test that the candidate store round-trip preserves tags.
4. Proposals require owner approval, or verified replay, before any use. A proposal never
   widens grants, changes policy, adds an executable capability, or alters the confirmation
   list. Skill replay still goes through validation, grants, taint, judge and confirmation.
5. Nothing a model writes becomes code or policy without review. A model on the review pass may
   only choose among code-enumerated operations (merge candidate, mark stale, discard), as
   OpenClaw's deep phase does with add/merge/supersede, and its reply is parsed in the strict
   style of the reader shapes (unknown keys reject the whole reply).
6. Tool-free and read-only on everything but the candidate store; no Hands, no Screen, no
   network. The pass cannot dispatch anything, so a fooled model costs a bad candidate that the
   owner declines.
7. Bounded and reversible: caps on candidates per pass and on candidate store size, a maximum
   fraction of prior entries a pass may replace (OpenClaw's 0.25 is a precedent, not a tested
   value for us), a pre-image of the store before each pass, an owner-visible report of what the
   pass proposed and dropped, and a per-pass log with token counts.
8. Privacy: log inputs are local; keep the pass on-device; mask or exclude values from the
   owner's request that look like secrets before they reach even a local model; the toggle
   requires the owner, not a message from anyone else (the VulnCheck bug).
9. No claim of benefit without measurement. OpenClaw has none published; Ballistic should
   measure (4.3) before saying the pass helps.

### 4.3 Proposal C: the same idea in the development harness (offline "dream" over benchmark logs)

The harness already writes run JSON with `failure_category`, step events, judge and readback
fields, and skill replay markers (`harness/outcomes.py`, `harness/run_task.py`). A laptop-side
batch pass can:

- **Cluster failures** across the 20-task suite and repeated runs by (failure_category, package,
  screen signature, last action, readback word), and rank clusters by frequency and by how many
  distinct tasks they touch (OpenClaw's frequency and query-diversity weighting is a usable
  precedent).
- **Separate flaky from stable.** Compare repeated runs of the same task and model; a cluster
  that appears once is noise.
- **Propose fixes as reviewable diffs**, never applied automatically: a candidate
  `KNOWN_CONTROLS` entry for a recorded anonymous control, a candidate frozen test case from a
  recorded screen, a prompt-rule suggestion, or a harness-environment fix. A human or a coding
  agent reviews, runs `cargo test` and the harness tests, and re-runs the affected tasks.
- **Keep three things separate in the report**, as the project already requires: agent claim,
  production evidence, and benchmark outcome. The pass reads the independent scoreboard but must
  not feed benchmark-only state into production prompts or routing (`core/README.md` §10).
- **Measure whether it helps.** Before/after pass rate on a held-out set of tasks and runs, not
  only the tasks the pass was fitted on; the proposal quality (accepted by reviewer vs rejected)
  is itself a number. Note that fitting fixes to the same 20 tasks overfits (anything it
  proposes should name a recorded screen).
- The harness pass may use a stronger cloud model because it reads only logs the owner of the
  repository already holds, but log content from injection tests must stay marked as hostile
  fixtures and must never be pasted into a prompt as instructions.

### 4.4 Top proposals in priority order

1. **Adopt the safety rules in 4.2 as a written constraint now**, before any learning code:
   provenance outside the text and set by code, structural exclusion of tainted candidates, no
   model-written code or policy, owner approval plus verified replay before use, bounded and
   reversible.
2. **Build the harness failure-clustering pass (4.3) first.** It is safe (laptop, human review,
   no phone data), uses logs that exist, and produces the measurement that tells us whether an
   on-device pass is worth building.
3. **Then an on-device, idle-and-charging candidate pass (4.1)** limited to code-readable log
   fields, producing typed skill, label and failure-count candidates that need owner approval and
   verified replay; start with skill-template candidates (slots from `owner:*` only), since that
   is the one place core already has a replay verifier.

### 4.5 Things not to take

- A free-text `MEMORY.md` loaded into the planner prompt. It is the channel that dreaming's CVE
  and the laundering attack travel through, and Ballistic's planner is built so no such channel
  exists.
- Model-authored skills as code (Voyager-style) or model-authored prompts/policy.
- Default-on, silent, or remotely toggleable consolidation.
- Consolidating transcripts or summaries (which contain content). Use the log fields.
- The metaphor as a claim: describing this as "dreaming" or "learning" in user-facing text
  would overstate; it is a review pass that proposes.

## 5. Open questions and gaps in this research

- When "enabled by default" began, and whether the 2026.8.1 fix changed the dreaming prompt
  inputs, is not in what I could read (the advisory does not describe the code change).
- No author post on dreaming was found; no measured benefit for it was found.
- The IronCore attack's version and disclosure status are unknown, and it blends the hourly
  summary job with dreaming.
- Letta's model and frequency defaults, and the shape of its writes, come from vendor material.
- Not checked: OpenClaw source code for the deep-phase prompt (validate before copying any
  design detail).
