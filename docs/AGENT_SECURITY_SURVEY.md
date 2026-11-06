# Survey: how other agents defend against prompt injection, and what Ballistic takes from it

Date: 7 Nov 2026. Status: **reported** facts, gathered by web search for the core design review.
Numbers were taken from the papers' abstracts and from secondary write-ups; re-check against the
primary paper before quoting one. The design conclusions at the end are decisions (owner agreed
7 Nov 2026) and are repeated in `docs/CONTEXT.md` §2.

## 1. The question

Ballistic's agent reads text that strangers wrote (SMS, mail, web pages, notes, device names) and
then taps, types and sends on the owner's phone. The owner asked for a "judge" model that gates
every action and "cannot be hacked or prompt-injected under any circumstance". Is that possible,
and how do others do it?

## 2. Short answer

No model is un-injectable. Every published defence that relies on a model noticing the attack is
probabilistic and has been bypassed. The defences with a real guarantee get it from **code that
tracks where data came from and limits what a model's words may cause**, with a human approving
anything the code cannot clear. A judge model is still useful, but only as a layer that can say
"no": a fooled judge then only makes the assistant more cautious.

## 3. What others do

| Who | Approach | What it buys | Weak point |
|---|---|---|---|
| **CaMeL**, Google DeepMind, March 2025 ([arXiv 2503.18813](https://arxiv.org/pdf/2503.18813); [review](https://simonwillison.net/2025/apr/11/camel/)) | A privileged model sees only the user's request and writes the plan. A quarantined model turns untrusted text into typed values. A custom interpreter tracks the **provenance** of every value and blocks tainted values from reaching risky tools (capability policies). | Provable: 0 successful injections on AgentDojo; 77% of tasks solved vs 84% undefended. Code published (google-research/camel-prompt-injection). | Loses utility; the plan cannot adapt mid-way to what it reads; a custom interpreter is heavy. |
| **Dual LLM**, Simon Willison, 2023 | Privileged model never sees content; a quarantined model returns it as symbolic variables (`$VAR1`) the privileged model can pass on but not read. | Simple; CaMeL is its concrete form. | Action arguments (who to send to) can still be forged by the content. |
| **Six design patterns**, Google / Microsoft / IBM / ETH / EPFL, June 2025 ([arXiv 2506.08837](https://arxiv.org/abs/2506.08837); [summary](https://simonwillison.net/2025/Jun/13/prompt-injection-design-patterns/)) | Action-selector, plan-then-execute, dual LLM, code-then-execute (CaMeL), LLM map-reduce, context-minimisation. | Control-flow integrity: untrusted data cannot change *which* tools run or in what order. | Does not protect the *arguments*: "recipients and bodies can still be shaped by untrusted data". Meant for application-specific agents, not general ones. |
| **Agents Rule of Two**, Meta, 31 Oct 2025 ([summary](https://simonw.substack.com/p/new-prompt-injection-papers-agents)) | In one session an agent may hold at most two of: (A) reads untrusted input, (B) touches private data or sensitive systems, (C) changes state or communicates externally. All three needed → a human in the loop. | A one-line scoping rule that assumes an injection will sometimes get through. | A design constraint, not a mechanism; says nothing about how to enforce it. |
| **OpenAI Operator**, Jan 2025 ([launch notes](https://simonwillison.net/2025/Jan/23/introducing-operator/)) | Model trained to ignore injections, a separate **monitor model** that pauses on suspicious content ("Keep paused / Mark safe and resume"), confirmations before consequential actions, "take control". | The closest shipped product to the owner's "judge". | Exploited within weeks (data exfiltration through a GitHub issue; [Embrace the Red](https://embracethered.com/blog/posts/2025/chatgpt-operator-prompt-injection-exploits)). Both attack and defence are probabilistic. Reportedly folded into ChatGPT agent in mid-2025 (unverified). |
| **Visual injection on computer-use agents**, 2025 ([arXiv 2506.02456](https://arxiv.org/html/2506.02456v2)) | Tests system-prompt defences on screenshot-driven agents. | | "System-prompt defences are largely ineffective." |
| **Multi-lab red-teaming**, 2026 ([arXiv 2603.15714](https://arxiv.org/pdf/2603.15714)) | Indirect injection against many models. | | Succeeds across models; capability and robustness correlate weakly, so a smarter model is not a defence. |
| **AgentDojo**, NeurIPS 2024 ([paper](https://proceedings.neurips.cc/paper_files/paper/2024/hash/97091a5177d8dc64b1da8bf3e1f6fb54-Abstract.html)) and follow-ups | 97 tasks, 629 injection cases for tool agents (mail, calendar, banking). Out-of-band defences (CaMeL, FIDES, Progent, RTBAS, FORGE) "near-eliminate" attacks; a June 2026 paper re-tests them with adaptive attacks ([arXiv 2606.26479](https://arxiv.org/abs/2606.26479)); a "firewall at the tool boundary" paper claims full security with high utility ([arXiv 2510.05244](https://arxiv.org/abs/2510.05244v2)). | The yardstick for agent injection work. | Desktop tool world, not phones. **No phone equivalent exists**; Ballistic's `tests/injection/` will be a small one. |
| **Open Android agents**: DroidRun / Mobilerun ([repo](https://github.com/marstrix/droidrun)), MobileUse, Open-AutoGLM ([comparison](https://www.foneclaw.ai/best-open-source-phone-agent-frameworks.html)), Mobile-Agent-v3, AndroidWorld's T3A/M3A | Raw accessibility tree and/or screenshot → one model → action. Open-AutoGLM documents confirmation points for sensitive operations; the others at most ask before "dangerous" actions. | Benchmark scores; a Portal/forwarder app pattern we also use. | **No structural separation between what the model reads and what it may do.** A Cloud Security Alliance note (July 2026) ran seven attacks: Mobile-Agent-v3 fell to all seven, the other frameworks to six of seven ([CSA](https://labs.cloudsecurityalliance.org/research/csa-research-note-mobile-agent-attack-surface-screen-injecti/)). |
| **Mobile-specific attack papers** ([arXiv 2505.12981](https://arxiv.org/pdf/2505.12981); [arXiv 2510.07809](https://arxiv.org/html/2510.07809v1); [arXiv 2507.04227](https://arxiv.org/pdf/2507.04227v2)) | Overlays and pop-ups; text inside an app that is revealed only when an agent (not a human) drives the UI; hijacking native components rather than adding fake ones. | Tell us what the realistic phone attack looks like. | Pop-ups are "visually conspicuous" and need permissions Android controls; the realistic attack is **ordinary content inside a legitimate app**: a message, a note, a contact name, a Wi-Fi SSID. |

## 4. Takeaways

1. **Un-injectable models do not exist.** Operator had a trained model plus a monitor model and was
   exploited. Red-team results say capability does not buy robustness.
2. **Guarantees come from code, not prompts.** The only designs with provable results (CaMeL and
   the out-of-band family) never let a model's words reach a side effect without a code check on
   where the data came from. Tagging text as `<untrusted>` in a prompt is a courtesy, not a
   defence.
3. **Protect the arguments, not only the control flow.** Most patterns stop the attacker from
   choosing *which* action runs; they do not stop a forged recipient or amount. Provenance
   tracking does.
4. **Separate reading from acting.** Meta's Rule of Two, stated as code: a session that read
   untrusted content and a session that sends, pays or deletes must be separated by a human.
5. **A monitor model is a layer, not a gate.** It can only be trusted to tighten.
6. **Expect to lose some score.** CaMeL paid 7 points. We measure the price on the 20 tasks.
7. **Phones have no injection benchmark.** Ours will be small, but it is the only one on the
   emulator.
8. **The open Android agents are harnesses, not defences.** Reusing one would buy a score and a
   portal app, and nothing of what Ballistic is for.

## 5. How this shapes Ballistic's core (decided 7 Nov 2026)

Three layers; the first and third carry the guarantee, the second is defence in depth. Full
design: `core/README.md`.

**Layer 1, code.**
- The planner never reads content: not in tags, not summarised. A list item's title is shown to
  it only when plain string matching finds it in the owner's own request.
- The reader returns **typed fields** (a JSON shape core asked for), never prose for the planner.
  For "which item is Nora?" it returns an element id from the ids on screen: a number from a
  fixed set cannot carry an instruction.
- Every value carries its **source** (`sms:+1555…`, `note:todo.md`, `owner:request`,
  `owner:contacts`). A tag in code, not a word in a prompt.
- **Taint rule.** A tainted value flowing into `type`, `answer`, a recipient, an amount or
  `open_app` stops for a hard confirmation that shows the value and its source.
- **Rule of Two.** A step that read untrusted content and a step that sends, pays or deletes
  cannot both happen without a confirmation between them.
- Send, pay, delete, install, grant → fingerprint, always.

**Layer 2, the judge.** A deny-only model call before every state-changing action. It sees the
request, the action in plain words, the provenance tags and the last steps; never raw content. It
answers `allow` or `block + reason`. `allow` changes nothing; `block` halts and shows the owner why.
A fooled judge can only make the assistant more cautious.

**Layer 3, the owner.** The confirmation window with the fingerprint, which the agent cannot
press (`SPEC.md` §7.1). Every prompt names the value and where it came from.

**What this does not cover.** A fooled planner can still waste steps or give up; a fooled reader
can still produce a misleading summary (which is why summaries show sources). A hostile *app* can
mislabel its own buttons; that is bounded by the per-app allowlist and the confirmation list, not
by the filter.
