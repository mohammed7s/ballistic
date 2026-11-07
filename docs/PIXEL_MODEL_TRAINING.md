# Improving a small local model for Ballistic

Research date: **7 November 2026**. Primary sources below were checked on this date;
publication, revision and comment dates are supplied where available. This is a research
proposal. No training data generation, training, weight downloads, compute provisioning or
phone/emulator inference was performed. Core fixes continue as separate implementation work.

## Recommendation and confidence

**The idea is technically credible: use a stronger model to produce checked examples, then
fine-tune a smaller model to predict Ballistic's narrow actions. It is not yet the next
experiment. First establish an untuned local baseline on a corrected core, then prove that
custom weights survive export to the intended phone runtime.** Training can improve decisions
and reduce wasted calls; it cannot recover information the filter removed, implement a
missing reader, or make the same dense inference graph intrinsically several times faster.

- **High confidence:** start with supervised fine-tuning (SFT) of compact actions and typed
  outputs, using LoRA, with independently checked demonstrations. Keep policy and completion
  evidence in code. Do not begin with online reinforcement learning (RL), pruning or a new
  architecture.
- **Medium confidence, hardware candidate:** benchmark **Gemma 4 E2B instruct** first using
  its published LiteRT-LM bundles. An official Tensor G5 bundle exists. Treat its GPU path as
  the more plausible custom-weight deployment experiment; custom Tensor NPU deployment is
  an unresolved dependency, not a promised benefit of tuning.
- **Medium confidence, first training control:** **Qwen3-4B**, with thinking explicitly
  disabled, is the clearest independent text-only SFT/LoRA control. It has documented tuning,
  GGUF conversion and a published LiteRT GPU artifact. Select between it and E2B using actual
  baseline quality and export fidelity, not general leaderboard scores.
- **Medium confidence:** retain **Gemma 4 E4B instruct** as the quality challenger if its
  extra memory and latency are acceptable. Do not budget on a Tensor G5 NPU path for it.
- **Low confidence, optional experiment:** Qwen3-1.7B can test whether a narrower reader or
  bounded action selector retains sufficient accuracy at lower cost. It is not an assumed
  replacement for the planner. Qwen3-0.6B is at most a schema/extraction floor, not another
  necessary training run.

**Magnitude:** no measured Ballistic tuning uplift or Pixel 10 Pro throughput uplift exists.
There is no defensible numerical uplift forecast for our workload. Set a minimum useful
improvement before a pilot, then measure it on held-out tasks. Zero improvement, regressions,
and no transfer to unseen apps are credible outcomes.
Larger published gains justify investigating specialization, not promising 70–90% reliable
phone automation. A merged LoRA with the same graph, precision and backend should have
approximately unchanged raw prefill/decode work per token; improvements should first appear
in correct actions, shorter outputs and fewer retries.

## Apply specialization where it helps

The owner's system-controls versus app-workflows distinction (7 November 2026) suggests a
smaller first training target: language → typed operation and arguments for supported phone
controls. Host code still executes, checks grants and verifies results. A deterministic parser
or untuned small model may already suffice; compare against those before training. Do not use
training to teach repeated UI navigation where a reliable structured operation is available.
For selected app workflows, train only the interpretation/navigation decisions that remain;
load relevant capability instructions and retain code-enforced postconditions and approvals.
This is a proposed refinement, not a measured optimization or implemented adapter set.

## What the existing Ballistic evidence does and does not show

Read for this report: [CLAUDE.md](../CLAUDE.md), [context](CONTEXT.md),
[README](../README.md), [specification](../SPEC.md), [plan](PLAN.md),
[harness documentation](../harness/README.md), and the previous
[Pixel model research](PIXEL10PRO_MODELS.md). The recorded suite is the historical baseline
at commit `a13f59e`, not a claim about fixes currently being made elsewhere.

The completed suite's [report](../ballistic_runs/core_suite_20261107/report.txt) and
[comparison](../ballistic_runs/core_suite_20261107/comparison.json) record:

- **3 PASS, 9 FAIL, 8 infrastructure ERROR**, with **6 independent state passes**, 13 state
  failures and 1 unknown. Bluetooth and both SMS tasks reached the requested state without
  agent completion. There were zero final false-done results in this run, not a proof of
  calibrated verification.
- The errors were five HTTP 429s, two Gemini read timeouts and one setup DNS failure. They
  are not eight demonstrations of poor local-model reasoning. A local backend removes that
  API dependency but may introduce loading, memory, runtime and thermal failures.
- Hidden Quick Settings labels preceded unrelated Flashlight and DND changes. Stale trees
  affected Settings navigation. SMS traces repeated Send/Enter attempts after state success;
  duplicate delivery was **not established**. Markor deletion required the unimplemented
  reader. State success alone does not establish absence of unrelated effects.
- The 125 usage-bearing calls averaged about **2,325 prompt tokens and 35 completion tokens**;
  failed-call usage is unknown. These calls were planner/verifier calls, not the completed
  four-role architecture. The old T3A 13/20 result used different randomized inputs and is
  not a matched causal comparison. Neither the timing nor score delta isolates model quality.

The implementation must restore appropriately trusted static labels, enforce observation
freshness and target resolution, stop ambiguous side-effect retries, and implement the
typed-reader/evidence path before the affected examples are useful policy targets. A frontier
teacher given the same inadequate observation may only infer or guess; supervising that guess
would teach unsafe confidence. Giving the teacher hidden state or raw content that the deployed
planner cannot receive creates an unrealizable training target. Independent state inspection
may **label outcomes**, but must not enter the production model's input.

Three clean-looking successful episodes cover very few screens and decisions. Even the six
state passes cannot be accepted wholesale: a successful outcome can coexist with unnecessary
or adverse actions. Repeated screens and adjacent steps are correlated, so 100 copied turns
are not 100 independent tasks. Use these artifacts as regression/debugging evidence; do not
turn the protected 20-task benchmark into the training corpus.

## Verified candidate identities, licenses and tuning support

“Open weights” describes access to parameters. It does not automatically imply an unrestricted
license, available training data, reproducible pretraining, or permissively licensed runtime
dependencies. These exact student checkpoints advertise **Apache 2.0**; this is a verified
property of them, not a generalization to earlier Gemma, Llama, or all Qwen variants.

| Exact student | Verified identity and size | Tuning evidence and caution |
|---|---|---|
| `google/gemma-4-E2B-it` | Official Google card; 2.3B effective, 5.1B including embeddings; multimodal model used text-only here | Transformers architecture support; Unsloth publishes Gemma 4 LoRA recipes. Effective count is not total training storage. |
| `google/gemma-4-E4B-it` | Official Google card; 4.5B effective, 8B including embeddings | Same family support, larger footprint. Do not confuse it with Gemma 3n E4B. |
| `Qwen/Qwen3-4B` | Official Qwen dense text model; 4.0B total, 3.6B non-embedding | Qwen's training docs explicitly support SFT, LoRA/QLoRA and preference/RL tooling. Pin this original hybrid-thinking checkpoint; `Thinking-2507` and `Instruct-2507` are different candidates. |
| `Qwen/Qwen3-1.7B` | Official dense text model; Apache 2.0 | Optional smaller control within the same family and training stack; assess task accuracy before accepting its resource advantage. |

Identity/license sources: [Google E2B card](https://huggingface.co/google/gemma-4-E2B-it),
[Google E4B card](https://huggingface.co/google/gemma-4-E4B-it),
[Gemma 4 license](https://ai.google.dev/gemma/apache_2),
[Qwen3-4B card](https://huggingface.co/Qwen/Qwen3-4B),
[Qwen3-1.7B card](https://huggingface.co/Qwen/Qwen3-1.7B), and
[Qwen release announcement, 29 April 2025](https://qwenlm.github.io/blog/qwen3/).
Retain required license/notice attribution and modification notices when redistributing.
Do not assume compiler SDK binaries inherit the model's license.

Training support is more than a generic “Gemma supports LoRA” claim:

- Google's [tuning overview](https://ai.google.dev/gemma/docs/tune) describes parameter-efficient
  tuning and warns to choose a supported deployment format.
  [Transformers' Gemma 4 implementation](https://huggingface.co/docs/transformers/model_doc/gemma4)
  and [Unsloth's Gemma 4 training guide](https://unsloth.ai/docs/models/gemma-4/train)
  provide family-specific support. This report verified documentation, not a working local
  training environment.
- Gemma E2B/E4B share KV state between layers. A historical `use_cache=False` training bug
  produced incorrect attention; [Transformers #45242](https://github.com/huggingface/transformers/issues/45242)
  opened 4 April and closed 9 April 2026. Do not apply an old generic QLoRA recipe blindly;
  pin versions and test forward/loss parity before training. Closure is not proof that any
  installed older package contains the fix.
- [Qwen's Unsloth guide](https://qwen.readthedocs.io/en/latest/training/unsloth.html) and
  [MS-SWIFT guide](https://qwen.readthedocs.io/en/v3.0/training/ms_swift.html) document the relevant
  tuning methods. Their general-reasoning recipes are not requirements to collect reasoning
  traces for Ballistic. Train visible actions and typed answers; keep thinking disabled in the
  initial deployment comparison.

The inherited shortlist is supported. Its stronger possible extrapolations are not: general
tool calling does not prove Ballistic schema accuracy; a GPU report from a Pro Fold is not a
measurement on this Pro; a Tensor model file is not a supported custom-weight compiler recipe.
Older statements elsewhere that Gemma necessarily has the old custom license, G5 only has
Gemma 3, or Tensor SDK is only an early-access experiment should not be carried forward.

## Different ways a teacher can help

| Method | What changes | Relevance to Ballistic |
|---|---|---|
| Teacher-generated SFT / action-trajectory imitation | Student weights learn supervised observation → action/output pairs | Best first experiment. Teacher proposes; schema, independent outcomes and side-effect checks decide whether a label is usable. Whole trajectories supply history and recovery states. No hidden reasoning transcript is needed. |
| LoRA | Low-rank updates to selected weight matrices; the original weights are frozen during training | An economical way to perform SFT or preference learning, not a separate source of intelligence. Merge updates for deployment when appropriate. |
| QLoRA | Train adapters against a quantized frozen base, usually with a training-specific 4-bit representation | Saves training memory. Does not mean the result is already GGUF or Tensor-compatible INT4, nor that inference becomes faster than the same final quantization. |
| Preference learning, e.g. DPO | Optimize chosen versus rejected responses for the same input | Later option for verified recovery/stop choices or correct versus incorrect actions. Teacher preference is not ground truth; preference for fluent confidence is dangerous. |
| Environment-reward RL | Student samples actions in an environment; rewards update its policy | Can teach recovery and efficiency beyond imitation, but requires reliable reset, side-effect accounting and rewards. Sparse terminal success can reward harmful shortcuts. Not justified for the first pilot. |
| Distillation into a smaller student | Transfer behavior or output distributions into an already smaller architecture | A 4B teacher/student pipeline can later train a 1.7B student. API action imitation is sequence/behavior distillation, not copying frontier weights. Logit distillation requires accessible distributions and tokenization alignment. Training a new architecture from scratch is a separate, much larger project. |
| Quantization, QAT and pruning | Lower numeric precision; simulate deployment quantization during training; or remove parameters/structure | Primarily deployment optimizations. Unstructured zeros need matching sparse kernels to save work; structured pruning changes shapes and exporter support. Neither is a substitute for task data. |

Foundational primary sources: [LoRA, 17 June 2021](https://arxiv.org/abs/2106.09685),
[QLoRA, 23 May 2023](https://arxiv.org/abs/2305.14314),
[DPO, 29 May 2023; revised 29 July 2024](https://arxiv.org/abs/2305.18290), and
[knowledge distillation, 9 March 2015](https://arxiv.org/abs/1503.02531).

A frontier model can also propose task variants, label recoverable states and review
ambiguous annotations. These are data-generation aids. A model-written imaginary transcript
is not an executed trajectory, and a teacher asserting “success” is not an independent reward.
If both teacher and student fail on the same observation, investigate observability and
capability before increasing training volume.

## Can the tuned weights actually reach Pixel 10 Pro?

**GPU: plausible, with verified export infrastructure and significant fidelity risk.
Tensor G5 NPU: stock E2B artifact verified; custom tuned E2B is still an experimental path.
E4B/Qwen3-4B NPU must not be assumed.**

### Stock artifacts versus custom checkpoints

Hugging Face metadata independently checked on 7 November, without downloading weights:

| Artifact repository | Revision inspected | Relevant contents |
|---|---|---|
| [E2B LiteRT](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/3d48132a00cf76de57d926de30f5c5c5ed9b8c0d) | `3d48132a00cf76de57d926de30f5c5c5ed9b8c0d` | GPU, generic, G5, G6 and uncompiled NPU bundles |
| [E4B LiteRT](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/71064b01bd5e2bfd9a5f9dd52df86ae4800138c1) | `71064b01bd5e2bfd9a5f9dd52df86ae4800138c1` | GPU/generic/web bundles; no G5 bundle in this repository |
| [Qwen3-4B LiteRT](https://huggingface.co/litert-community/Qwen3-4B) | Pin the selected file/revision at experiment time | Card documents 4,096-token artifacts; the INT4 GPU file changed 31 October 2026 and requires LiteRT-LM 0.19.0+ |

The Qwen conversion card is the artifact author's evidence, not an official Qwen phone
benchmark. Its Galaxy S26 measurements are not Pixel measurements. The original Qwen3-4B
checkpoint metadata inspected here is revision `1cfa9a7208912126459214e8b04321603b3df60c`;
the 1.7B checkpoint is `70d244cc86ccca08cf5af4e1e306ecf908b1ad5e`.

[LiteRT Torch release notes](https://github.com/google-ai-edge/litert-torch/releases)
document Gemma 4 export, experimental bring-your-own-model NPU tooling and later multimodal
support; v0.10.0 was released 20 October 2026. The
[export API](https://github.com/google-ai-edge/litert-torch/blob/main/litert_torch/generative/export_hf/export.py)
accepts a local Safetensors directory. Therefore it is wrong to claim custom Gemma 4 GPU
export is categorically unavailable. It is equally wrong to treat successful conversion as
proof of correct decisions on the phone.

Two particularly relevant reports:

- [LiteRT Torch #1013, opened 1 May 2026](https://github.com/google-ai-edge/litert-torch/issues/1013):
  a LoRA-tuned E2B model loaded successfully after export but lost tool-call behavior. A
  6 May follow-up increased the cache from 256 to 512 tokens, removing malformed loops while
  leaving behavioral/safety-contract regressions. It remained open when checked. This is a
  first-person report from a different workload, not a measured Ballistic regression or a
  universal statement about current releases.
- [LiteRT Torch #1005, opened 26 April 2026](https://github.com/google-ai-edge/litert-torch/issues/1005):
  historical Gemma 4 metadata/chat-template problems. Current code has Gemma 4-specific
  [metadata handling](https://github.com/google-ai-edge/litert-torch/blob/d600176b2e29bcc900a7e193f054ab3d6c3c747e/litert_torch/generative/export_hf/model_ext/gemma4/metadata_builder.py).
  An old open issue is not sufficient to declare that every current exporter still has its
  original defect. Version-specific parity testing resolves this.

### The Tensor NPU boundary is concrete

Google's [NPU guide](https://developers.google.com/edge/litert/next/npu) specifies AOT for
Tensor; on-device JIT is not supported in this SDK path. The
[Tensor SDK guide](https://developers.google.com/edge/litert/next/tensor-sdk) still provides
Beta access/sign-up and separate workstation requirements. Merely selecting `backend=npu`
does not compile a GPU bundle.

In [LiteRT #8501](https://github.com/google-ai-edge/LiteRT/issues/8501), a Google maintainer's
[30 September 2026 update](https://github.com/google-ai-edge/LiteRT/issues/8501#issuecomment-5907052523)
recommended the precompiled E2B bundle and described its `export-hf` flow as under development;
no E4B NPU development plan was stated, and Qwen work covered 0.6B/1.7B, not 4B. October 26–27
comments report workarounds that compile E2B graphs but explicitly say they were not run on a
device. The issue remained open, with repro files delivered 3 November. **This is evidence of
progress, not demonstrated tuned-weight deployment.** A September commenter reported compiling
weights, but the thread does not establish a reproducible, correct tuned E2B Pixel result.

There is also a package-tooling gap: [#1383, 26 October 2026](https://github.com/google-ai-edge/litert-torch/issues/1383)
reports missing Tensor backend support in `litert_lm_npu_compiler`. I independently inspected
[the compiler at commit `d600176b`, 6 November 2026](https://github.com/google-ai-edge/litert-torch/blob/d600176b2e29bcc900a7e193f054ab3d6c3c747e/litert_torch/generative/export_hf/experimental/litert_lm_npu_compiler/litert_lm_npu_compiler.py):
its backend selection/manufacturer dispatch still accepts Qualcomm/MediaTek. A separate
[vendor configuration file](https://github.com/google-ai-edge/litert-torch/blob/d600176b2e29bcc900a7e193f054ab3d6c3c747e/litert_torch/generative/export_hf/experimental/npu_export/configs/vendor_configs.py)
already names `google_tensor`; that alone does not complete the package compiler. Lower-level
Tensor AOT tooling exists, so this is not a claim that all Tensor compilation is impossible.

Do not transplant weights or auxiliary sections into an opaque precompiled NPU bundle.
Plan to merge the adapter into its matching floating-point checkpoint, export, calibrate/
quantize for the target, compile, and validate the resulting complete bundle. Check SDK
access and redistribution terms separately when selecting that route. Loading from an ADB
shell is insufficient: the intended GrapheneOS app UID and default hardening must work.
No security weakening or policy bypass is a proposed deployment workaround.

### Merge and fidelity requirements

[PEFT checkpoint documentation](https://huggingface.co/docs/peft/developer_guides/checkpoint)
explains that an adapter needs its base and that merge support depends on method/quantization.
LiteRT-LM has [LoRA runtime code](https://github.com/google-ai-edge/LiteRT-LM/blob/e40257f9c6a3b9dc1af47097c3696bf61cf0aaa7/runtime/components/lora_manager.cc),
but this does not establish direct loading of arbitrary PEFT adapters on every shortlisted
model/backend. **Assume merge and re-export unless the exact adapter/backend combination
has a supported, tested path.** A zero-initialized adapter only tests plumbing; later use a
small nonzero synthetic update to check that exported behavior actually reflects the update.
That deployment test has not been performed.

Gemma's [official QAT overview](https://ai.google.dev/gemma/docs/core) distinguishes ordinary
checkpoints, QAT unquantized references, GGUF and mobile-specific quantization recipes. The
mobile recipe includes mixed low-bit layers and static activations. Do not assume a generic
INT4 export reproduces the published mobile artifact's quality, size or speed. Record the
exact starting checkpoint; ordinary SFT can disturb QAT calibration and require recalibration
or later quantization-aware adaptation. A bitsandbytes/NF4 training representation is not
the target runtime's quantization format.

The validation ladder must compare:

1. Untuned reference checkpoint and untuned published deployment artifact, identifying any
   existing format/precision differences before attributing them to fine-tuning.
2. Tuned base-plus-adapter against the merged floating-point model.
3. Merged reference against unquantized or high-precision export, then the intended quantized
   CPU export, then GPU/NPU execution of that export.
4. Identical rendered prompts/token IDs, chat-template roles, stopping rules, thinking setting,
   context capacity, sampler and action parser at each stage. Test long inputs, Unicode,
   phone numbers, element IDs, nulls, no-op/stop cases and history transitions.
5. Logit/top-token comparisons on fixed prefixes where exposed, plus semantic action/extraction
   parity and independently scored task outcomes. Bit-identical sampling is not required;
   coherent prose or valid JSON alone is insufficient. Verify actual backend delegation and
   CPU fallback rather than trusting a command-line flag.

For a fallback, use merged Safetensors → GGUF → a validated quantization in **llama.cpp CPU**.
[Qwen's conversion guide](https://qwen.readthedocs.io/en/latest/quantization/llama.cpp.html) and
[llama.cpp's Android guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
document that path. Google also publishes Gemma QAT GGUF references. GGUF is a file format,
not a promise of fast prefill. Retest precision-sensitive actions after quantization and
measure the CPU cost on this phone. Vulkan requires its own correctness test; inherited
device reports do not establish that all versions work or fail.

## Empirical evidence: useful precedents, not transferable scores

These are author-reported results, not Ballistic replications. “Held out” has several meanings:
new random parameters within known templates, new tasks within known apps, and entirely new
apps are different tests. None below establishes Pixel 10 Pro performance for a filtered-tree
Gemma 4 or Qwen3-4B planner with Ballistic's confirmation/provenance boundaries.

| Primary work and date | Relevant result and baseline | Generalization / applicability limits |
|---|---|---|
| [DigiRL, 14 June 2024](https://arxiv.org/html/2406.11896v1) | 1.3B AutoUI-based screenshot agent; Web Shopping test success 17.7% SFT → 57.8% filtered behavior cloning → 67.2% offline-to-online RL. General test result 71.9%. | Train/test instructions separated; 96 test instructions per subset. Shopping covers five selected sites and simplified operations, not unseen-app automation. Gemini evaluator is fallible. Roughly 500 initial + 1,000 online trajectories per subset, not three successful traces. |
| [AndroidControl / data-scale study, revised 13 November 2024](https://arxiv.org/html/2406.03679v6) | Text element representation and PaLM-2S LoRA; 13,604 training episodes. Full-test high-level step accuracy at rank 64: 70.8% in-domain, 58.5% unseen apps. In-domain sampled-table baseline: 19.5% untuned PaLM-2S versus 71.5% tuned. | Explicit unseen-task, unseen-app and unseen-category splits; valuable evidence for split design. PaLM-2S is not a downloadable 2–4B candidate. Offline next-action accuracy is not live task completion; the two reported tables use different evaluation samples. |
| [AutoDroid-V2, revised 6 May 2025](https://arxiv.org/html/2412.18116v3) | Synthetic app documentation and validated script training; DroidTask completion 54.4% versus 43.9% for tuned stepwise AutoDroid. Authors report large token/latency savings from script execution and prefix caching. | Evaluation tasks excluded from synthesis, but target apps explored/documented beforehand: not unseen-app generalization. 8B Llama and Snapdragon/M2 hardware, not these students or Pixel. Its script-completion assumption must not replace independent postconditions in Ballistic. |
| [MobileRL, revised 24 October 2025](https://arxiv.org/html/2509.18119v2) | Qwen2.5-VL-7B AndroidWorld: 27.6% base → 50.2% reasoning-free SFT → 56.8% reasoning SFT → 72.0% RL. GLM 9B reaches 80.2%. | 97.9k/23.6k SFT steps and 2,000 AndroidWorld training tasks. AndroidWorld holds out exact content/initial states, not task templates/apps. AndroidLab tasks use existing apps; reward-model training includes test-task traces, weakening clean isolation. Paper says no out-of-domain public-app evaluation. |
| [UI-Voyager, 25 March 2026](https://arxiv.org/html/2603.24533v1) | Qwen3-VL-4B: reported baseline 45.3%, first rejection-tuning round 64.0%, third round 73.2%, final self-distillation 81.0% AndroidWorld. | Over 7,000 training tasks generated from AndroidWorld. Final score averaged over 64 randomized runs; comparison baselines taken from prior papers. No demonstrated app/template-disjoint test. Screenshot/action policy, not Ballistic's filtered-tree policy. |
| [PhoneBuddy, revised 24 June 2026](https://arxiv.org/html/2606.23049v2) | Qwen3.5-4B, 950,758 SFT action steps: SFT → mixed real/mock RL improves AndroidWorld 60.3% → 83.2%, but real-phone success only 36.67% → 45.33%. Cross-app result falls 22% → 18%. | Same-backbone training-stage comparison, not a raw-base-to-tuned gain. Real-phone suite has 150 tasks. The inspected paper does not establish a strict app/template-disjoint training/evaluation split; no Pixel-local latency claim. Real-app reward uses model judging, mock reward uses programmatic state. |

The closest evidence for **text-tree LoRA plus unseen apps** is AndroidControl, but its model
and offline metric differ. The strongest small-model AndroidWorld numbers mostly establish
specialization within a familiar environment. PhoneBuddy shows why benchmark and real-world
gains should not be equated. UI-Voyager's use of failed experience means correcting divergence
with successful alternatives, not relabeling failure as success. Reasoning-free SFT is an
empirically useful stage in MobileRL; Ballistic need not require teacher reasoning traces.

## Teacher-output rights are a separate gate

This is a practical reading of the linked public terms, not a determination of whether a
particular private contract or intended product falls within “competing.” **Owning outputs
does not erase restrictions on their use to train another model.** Before a future dataset
generation run, record the teacher model/version, service, applicable terms/date, permitted
training use and dataset provenance. Existing Gemini evaluation permission is not automatically
permission to train a replacement model from its outputs.

- [Gemini API additional terms, effective 23 March 2026](https://ai.google.dev/gemini-api/terms):
  restrict using the services to develop competing models. Paid-service privacy treatment
  does not remove that restriction. Search-grounded outputs have additional training limits.
  Therefore existing `gemini-flash-lite-latest` traces are **not presumed cleared training
  data**. Keep synthetic fixtures free of real private phone data, especially for unpaid APIs.
- [Anthropic commercial terms, effective 17 June 2025](https://www.anthropic.com/legal/commercial-terms),
  section D.4, restrict building competing services/models absent express approval. Customer
  ownership of outputs in section B is not blanket distillation permission.
- [OpenAI Services Agreement, updated 1 December 2025](https://openai.com/policies/services-agreement/),
  section 3.3(e), restricts competing-model development subject to defined exceptions. The
  exceptions cover certain non-distributed categorization/classification/organization models
  and OpenAI-provided tuning/customization services; they do not clearly authorize this
  distributed generative phone policy. Do not infer permission from on-platform distillation
  features or from ownership of outputs.
- A practical alternative is an appropriately licensed **self-hosted stronger open-weight
  teacher**, or a provider agreement explicitly permitting the proposed training. For example,
  the official [DeepSeek-R1 card](https://huggingface.co/deepseek-ai/DeepSeek-R1) explicitly permits
  distillation into other LLMs under its MIT release; derived Llama/Qwen checkpoints retain
  their respective upstream obligations. That establishes an available licensing route,
  **not** that R1 is the best current phone-action teacher or economical to host. Gemma 4's
  larger Apache models are another possible teacher family. Hosted access may add different
  service terms regardless of weight licensing.

No teacher has been selected or called for training in this research. Public papers using a
proprietary teacher do not establish that Ballistic has the same permission. If terms cannot
be resolved, use independently authored/human demonstrations and a cleared teacher; do not
launder restricted outputs through paraphrasing or another model.

## Proposed pilot

### Gate 0 — stable observations, capabilities and a local baseline

The core owner completes the known safety/observation fixes. Freeze the resulting action
schema, filtering rules, host contracts and evidence types. Mark still-missing roles/capabilities
as unsupported; never train around an absent host operation. Establish base-model results for
E2B, E4B and Qwen3-4B on identical filtered inputs, then a small matched live evaluation once the harness and backend are ready. Use explicit non-thinking output and the existing compact action budget. Add a
prompt-only/few-shot control under the same permissions; use constrained schema decoding if
the exact runtime supports it, identically for base and tuned comparisons.

Save exact model/runtime revisions, prompts, seeds and per-role outcomes. Without a healthy
untuned local score, “improvement over Gemini's 3/20” is uninterpretable. An untuned model that
already satisfies the narrow use case may remove the need for training.

### Gate 1 — bounded export spike before building a large dataset

Test the exact candidate checkpoint through merge/export/quantization and the intended phone
backend before investing in training. Start with a no-op round trip and later a tiny nonzero
synthetic adapter solely to exercise the pipeline. Use non-benchmark synthetic probes covering
all action types and stop/uncertainty behavior. This is a small deployment experiment, not task training. Test the float reference, merged checkpoint, converted CPU model and
phone GPU/NPU model using the fidelity ladder above.

Pass requires a reproducible artifact recipe, preserved task-relevant semantics, visible
adapter effect, adequate context, and execution by the actual app UID. A stock NPU demo alone
does not pass this gate. If tuned E2B NPU export fails, assess GPU/GGUF against the latency
budget; do not buy more training compute hoping the compiler problem will disappear.

### Gate 2 — data with honest boundaries

**Proposed initial scale, not an established sufficiency threshold:** approximately 200–500
independently verified episodes, yielding perhaps 1,000–4,000 useful planner decisions across
3–5 training apps and several task families. Add roughly 200–500 separately constructed cases
each for typed reading and verification, with correct/incorrect/unknown balanced deliberately.
This tests learnability and catches pipeline mistakes; it does not certify a reliable product.
If the current environment cannot supply disjoint coverage, narrow the claim or postpone.

Create the split manifest **before** asking any teacher to generate labels:

- Freeze the existing 20-task suite as a known regression scoreboard. Exclude its recorded
  inputs/outputs, seed instances and near-duplicates from training, demonstrations, teacher
  few-shots and quantization calibration. Also exclude its task templates for a strict
  template-transfer claim. We have inspected these tasks already, so do not call them a
  pristine blind test; reserve a new final lockbox.
- Separate same-app/new-instance cases from held-out task templates and from **entirely
  held-out apps**. Reserve at least two apps from all training and validation material,
  including app documents and teacher exploration. Use additional apps/fixtures if the
  existing 20-task reservation makes that impossible. New random contact names within a
  trained template do not count as new-template generalization.
- Split complete episodes, template families and app identities, not random adjacent turns.
  Keep synthetic descendants, paraphrases and shared screen snapshots in one partition.
  Track generator lineage and deduplicate normalized requests and UI states.
- Hold out seeds, entity strings, numeric values, long lists, locale variations and UI/version
  changes. Reserve unseen injection families and side-effect perturbations, not just
  rewordings of training attacks. Pretrained public-benchmark contamination cannot be ruled
  out; fresh private synthetic tasks reduce the additional contamination we control.

Use resettable, non-personal sandbox state. Generate task variants with a known independent
checker, execute proposals through the same core policy, and retain only labels that pass
schema, preconditions, effect and side-effect checks. Keep complete audit traces separately
from the model-visible training projection. A simulator-created observation must be marked
synthetic and later tested for transfer to real UI trees.

### Role-specific supervision; no reasoning trace requirement

| Role | Permitted training input | Supervised output and independent label |
|---|---|---|
| Planner | Authenticated request, filtered fresh tree, actual available capabilities, bounded history, code-assigned source metadata | One schema-valid action referencing current IDs, or supported stop/unsupported/uncertain status. Validate arguments and execute only through the host. Choose among equivalent valid actions rather than demanding a single textual answer. |
| Reader | Only the specifically authorized untrusted content and requested extraction schema; no tools | Typed fields, exact spans/values where appropriate, null/unknown when absent or ambiguous. Human-authored synthetic ground truth checks correctness. The model cannot assign a trusted provenance tag; host code does that. Do not pass free-form reader prose into the planner. |
| Verifier | Goal/postconditions plus fresh authorized evidence with its strength explicitly represented | Verified/failed/unknown distinctions with evidence references. Label from independent state/side-effect checks, but never include privileged checker internals in deployable inputs. When only weak evidence is observable, the correct output may be unknown even if the evaluator privately knows the state. |

Keep the deny-only judge separate from outcome verification: it evaluates a proposed mutation
against request and provenance, cannot authorize it, and cannot substitute for host checks.
Do not expand the first training trial to all roles at once. Tune the planner first; evaluate
reader/verifier base behavior in parallel as data tasks, and make separate adapter experiments
only after their host paths exist. One model may serve distinct role sessions, but role
separation is a code/data boundary. Never carry hidden content or KV history across roles.

Necessary examples include absent/stale labels, missing capabilities, malformed or multiple
actions, ambiguous recipients, denied or changed approvals, lost acknowledgment after a send,
already-completed tasks, duplicate-send traps, unreadable content, deceptive receipt text and
injected instructions in messages/titles. Correct behavior can be safe stopping; report stop
rate on solvable tasks too, so universal refusal cannot masquerade as improvement.

Every mutating trajectory must check unintended changes, duplicate effects and exact target/
value/attachment identity, not only the requested final state. A failed episode may contribute
a separately verified correct recovery action or a rejected preference example; it must not
be copied wholesale into the positive SFT set. HTTP/setup failures are infrastructure records,
not preferred actions. A successful-but-harmful trajectory is rejected.

### Gate 3 — one small SFT LoRA trial and a held-out comparison

Start from the selected instruct checkpoint with rank 8 or 16 on a verified set of language
projection modules, short training runs and validation-based early stopping. Freeze unused
vision/audio modules and initially embeddings; verify actual trainable parameter names and
gradients. Prefer ordinary LoRA if memory permits; use QLoRA when needed, with the exact
quantization/merge path already tested. Use assistant-target loss masking so untrusted input
content is not itself the output training target. A few epochs are a search limit, not a
promise of optimality; do not choose checkpoints using the final test set.

Compare base, prompt-only control and tuned model on the **same** episodes/seeds, core, schema,
token budget and runtime settings. Include at least 100–200 paired held-out episodes across
the claimed strata for an initial signal, plus a separate adversarial/side-effect battery.
Treat that sample size as exploratory: at 50% success, 200 independent binary trials have
roughly ±7 percentage points of 95% sampling uncertainty; related templates increase
uncertainty. Use paired comparisons and cluster intervals by template/app. Repeat training
with a second seed only if the initial result justifies proceeding; don't rank close models
from a single 20-task run.

Report completion, independent state success, combined success, adverse effects, false-done,
unsupported/uncertain stops, denied actions, and infrastructure errors separately. Per-role
metrics include valid schema, correct current target, exact argument accuracy, reader
field/value accuracy, verifier false-positive rate and appropriate unknowns. Report calls,
tokens and UI actions **per verified clean completion** as well as per attempt.

Suggested decision rule, chosen before the run: continue only for a useful paired quality gain
(for example a preselected minimum gain), or meaningful latency savings with prespecified
quality non-inferiority, with no observed new approval bypass, duplicate mutation or false-done
regression. Zero adverse events in a small test is not proof of safety: zero in 200 independent
trials still leaves an approximate 95% upper failure-rate bound of 1.5%. Code safety gates
remain mandatory regardless of scores. An inconclusive trial calls for more independent
evaluation, not repeated tuning against the same lockbox.

### Growing coverage safely

After the pilot, generate new **training-only** tasks targeted at undercovered states and
student/teacher disagreements. Let the bounded student propose actions in a resettable
sandbox, obtain a teacher correction at a divergence, and independently verify the continuation.
Include already-complete states and recoverable navigation errors, not just easy successful
paths. Stop exploration before an ambiguous external effect; never explore real recipients,
purchases, account changes or confirmation bypasses. Log why each example was selected.

A later scale could be thousands of verified episodes, but growth should follow coverage and
held-out learning curves. Separate model learning from collection-policy changes, retain a
fixed control, and sample/audit every generation batch. New code/filter/schema versions require
reprojection or recollection, not silent mixing of incompatible examples. Keep the final
lockbox closed until a candidate is selected. This is how a few seed traces can bootstrap a
dataset without pretending they already demonstrate a dependable specialized policy.

## Resource and cost envelope

Training is workstation/server work, not a proposed Pixel workload. No GPU reservation or
purchase is recommended now. Evidence and conditional planning assumptions:

- Unsloth's [Gemma 4 guide](https://unsloth.ai/docs/models/gemma-4/train) reports E2B LoRA at
  8–10 GB VRAM and E4B LoRA at 17 GB. These are tool-author recipe claims, not guarantees for
  our sequence length/batch. Its [general requirements](https://unsloth.ai/docs/get-started/fine-tuning-for-beginners/unsloth-requirements)
  explicitly call listed memory values minimums. **Planning assumption:** one 16 GB GPU is
  a plausible E2B/Qwen3-4B parameter-efficient trial target; 24 GB gives more headroom and is
  the initial E4B target. Prove fit with batch 1, roughly 2k tokens, accumulation and compatible
  checkpointing. A 4k context or extra modules may require more memory.
- Arithmetic lower bounds illustrate why effective parameters mislead: BF16 weights alone
  are roughly 8 GB for 4B, 10.2 GB for 5.1B and 16 GB for 8B, before encoders, activations,
  framework buffers or optimizer state. Full Adam-style tuning can require roughly 16 bytes
  per parameter under a common mixed-precision/master-weight setup: about 64 GB for 4B
  **before activations**. Implementations vary; these are storage calculations, not capacity
  measurements or a reason to full-tune.
- **Planning reserve:** 32–64 GB host RAM and 50–100 GB free disk for one candidate's weights,
  merge/export intermediates, checkpoints and logs; verify rather than treating this as a
  minimum. Tensor compilation is a different budget: Google recommends 64 GB for substantial
  inputs, and #8501 includes much larger failed compilation attempts. More RAM does not fix
  unsupported graph patterns. Keep that investigation bounded.
- **Illustrative training arithmetic, not measured throughput:** 2,000 examples averaging
  2,500 total tokens, two epochs, means 10 million processed tokens. At an *assumed*
  500 training tokens/s that is 5.6 GPU-hours, excluding loading, evaluation and export;
  at 100 tokens/s it is 27.8 hours. Measure a short batch on the chosen hardware before
  quoting a runtime. Multiple candidates/seeds multiply this budget. Loss masking does not
  remove the compute needed to process input context.
- **Teacher budget formula:** for 500 accepted episodes × 8 decisions, 2,500 input + 100
  billed output tokens per teacher call means 10M input + 0.4M output tokens if every rollout
  is accepted. At 50% acceptance it is approximately 20M + 0.8M, before retries, multiple
  proposals, critic calls or longer teacher reasoning billed by the service. Cost is
  `20 × price_per_M_input + 0.8 × price_per_M_output`, plus those extras. Student output
  length is not the teacher's billed output length.
- **Dollar illustration only:** if a cleared teacher cost an assumed $3/$15 per million
  input/output tokens, the preceding 50%-acceptance scenario would cost $72. If compute cost
  an assumed $1/GPU-hour, the 5.6-hour training example would be $5.60, before all overhead.
  These are explicit hypothetical rates, **not provider quotes or a total project estimate**.
  Price the actual permitted teacher and hardware only after selection. Data checks, emulator
  resets, manual audits and export debugging can cost more than the adapter optimization.

Offline logit distillation from a very large self-hosted teacher adds substantial inference
memory/compute; an API only avoids hosting, not licensing and token costs. Online RL adds
many environment rollouts and policy/reference/reward-model resources. Published large RL
training configurations are not a budget for this small SFT pilot.

## Phone measurements and the final deployment gate

Tuning does not automatically increase tok/s. With unchanged architecture/precision and a
merged adapter, matrix shapes are unchanged. A smaller student, quantization, specialized
kernels, prefix reuse or speculative decoding can change throughput; each is a separate
experiment. Unmerged adapters can add overhead. A stock speculative drafter may have lower
acceptance after target tuning, so record/disable that feature in the first comparison rather
than assuming the stock bundle's acceleration persists.

Approximate task time is the sum over calls of:

`uncached_input_tokens / prefill_rate + generated_tokens / decode_rate + runtime overhead`,

plus UI waits, host operations, verification and human confirmation. Reader/judge/verifier
calls count. Fewer tokens or steps reduce total time without increasing raw inference speed.
For an **illustration, not a Pixel measurement**, at 400 prefill and 15 decode tok/s a
2,000-token input plus 60-token output costs about 9 seconds before overhead. Halving output
to 30 tokens saves 2 seconds, not 2× total speed. The historical core already emits short
outputs, so eliminating retries and redundant observations may matter more than further
output compression. Never remove policy/evidence checks to manufacture a speed gain.

After an accepted quality result, measure the **exported tuned artifact**, not its desktop
reference, on the owner's Pixel 10 Pro:

- Same core, matched prompts at 512/2,048/4,096 tokens where context permits, fixed output
  budgets, and realistic role sequences. Preserve room for generated tokens. Compare cold
  loading, warm calls and a sustained ~15-minute workload; randomize base/tuned run order.
- Record OS/build, app UID, native runtime/compiler versions, model hashes, precision,
  backend actually used, caches, thinking/sampling/MTP settings and context capacity.
- Measure median/p95 TTFT, prefill/decode, end-to-end task and role latency, UI steps, memory,
  temperatures/throttling, failures, and quality under foreground/background/screen-off use.
- Measure energy per attempt and per verified clean completion with matched brightness,
  radio/network, battery/thermal starting conditions and idle subtraction. Prefer an external
  power monitor or supported device energy counters; coarse battery percentage alone cannot
  justify a precise joules/task claim. No Pixel energy estimate is supplied here.

The inherited <10-second step and ≥70% capability goals are acceptance aspirations, not
results established by these papers. If custom export loses the quality gain or the phone
misses the useful latency/energy envelope, retain the untuned model, choose the verified
fallback, or stop the training track. The current recommendation is an ordered experiment:
**correct core → untuned local baseline → custom export proof → small checked SFT trial →
held-out evaluation → measured phone deployment**.
