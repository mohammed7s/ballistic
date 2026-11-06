# Pixel 10 Pro: model candidates for Ballistic

Research date: **7 November 2026**. Scope: local inference for Ballistic's filtered,
text-only UI planner, reader, judge and verifier. No phone/emulator tests were run for
this document. Keep Gemini for the current emulator validation; the local comparison
comes afterwards. Public pages and issue follow-ups were checked, not just search snippets.

## Recommendation

**Start the phone comparison with Gemma 4 E2B on LiteRT-LM, testing its Tensor G5 NPU
bundle against its GPU and CPU paths.** Compare Gemma 4 E4B on GPU for quality, and
Qwen3-4B on LiteRT-LM or llama.cpp CPU as an independent text-planner baseline. There
is no defensible published winner for *our* task yet: a coherent chat demonstration
does not establish reliable element selection, verification or injection resistance.

The most important missing number is sustained, correct inference on a **2,048-token
Ballistic prompt on the owner's Pixel 10 Pro**. I did not find an adequately specified
public measurement of that workload. Do not replace this with an estimated “4–6 seconds
per step” or a decode-only number.

## Hardware and evidence quality

Google lists **16 GB RAM and Tensor G5** for the Pixel 10 Pro. That is total device RAM,
not an allocation available to a model. Pro, Pro XL, Fold and base Pixel results must
remain separate: cooling, firmware and memory pressure can change the outcome.
[Google hardware specifications](https://support.google.com/pixelphone/answer/7158570?hl=en).

Evidence labels below:

- **Artifact checked:** file or documentation inspected on the research date.
- **External measurement:** a developer reports running it; Ballistic has not reproduced it.
- **Vendor result:** supplied by the model/runtime author, not an independent Ballistic test.
- **Estimate:** arithmetic or expectation, never a measured phone result.

| Device and workload | Backend/model | Reported prefill / decode | What it establishes |
|---|---|---|---|
| Pixel 10 Pro, Gallery #1008, July 2026 | Gemma 4 E2B, Tensor G5 TPU | Not supplied / ~15 tok/s | A usable NPU path was reported; prompt length and sustained performance absent |
| Pixel 10 / 10 Pro (report does not distinguish each row), custom source build | Official Gemma 4 E2B G5 bundle, NPU | 91 / 14.7 tok/s; TTFT 0.30 s | A short-prompt result, **not** pp2048; no basis to extrapolate its TTFT to a UI tree |
| Same developer's custom quantized models | NPU | 386–605 / ~10–11 tok/s | Output was garbage: exclude these rows from usable performance comparisons |
| Pixel 10 Pro Fold, GrapheneOS, July 2026 | Gemma 4 E4B, generic LiteRT GPU | Not supplied; initialization ~40 s | One working GrapheneOS GPU integration; no throughput claim |
| Pixel 8, Android 16, April 2026 | Gemma 4 E2B, patched LiteRT-LM 0.10.1 GPU | 426.64 / 11.94 tok/s | Useful older-device reference, not Pixel 10 Pro speed |
| Same Pixel 8 report | E2B CPU | 53.35 / 10.57 tok/s | Large prefill difference on that specific runtime/device |

Sources: [Gallery #1008](https://github.com/google-ai-edge/gallery/issues/1008),
[G5 runtime experiment](https://github.com/bejoyfuuul/litert-lm-tensor-g5-npu),
[LiteRT-LM #2566](https://github.com/google-ai-edge/LiteRT-LM/issues/2566),
[LiteRT-LM #1850](https://github.com/google-ai-edge/LiteRT-LM/issues/1850).

The G5 experiment is particularly useful because it publishes failures alongside
successes. Its broad conclusion about fast NPU prefill must not obscure that its
fastest prefill rows produced invalid output. Its compiler/quantization limitations
are one developer's findings, not a permanent impossibility proof.

**Correction to a misleading issue title:** #1008 initially attributed base Pixel 10
failure to 12 GB versus 16 GB RAM. On **22 July 2026** the reporter said Android 17
resolved it, and the comparison Pro was probably already on Android 17. Do not cite
that issue as proof of a 16 GB minimum. NPU memory still needs measurement.

## Runtime options and current constraints

### LiteRT-LM and Tensor NPU

Google announced the Tensor SDK **Beta on 19 May 2026**, starting with Pixel 10.
The older context note calling it experimental-only is stale. Google's NPU docs
describe Tensor ahead-of-time compilation; JIT compilation is not supported in this
path. A generic CPU/GPU bundle does not become a Tensor bundle by changing a flag.
[SDK announcement](https://developers.googleblog.com/google-tensor-sdk-beta-with-litert/),
[NPU documentation](https://developers.google.com/edge/litert/next/npu).

**Artifact checked:** E2B has both G5 and G6 bundles. G5 is not restricted to Gemma3-1B.
The inspected E4B repository has GPU/generic bundles but no Tensor G5 bundle. Match
model revision, LiteRT-LM, dispatch library and firmware; failures can arise from
their combination. The G5 experiment reports that an official bundle gives clean
output while custom quantizations do not. Use the published bundle first, rather
than making model conversion the prerequisite to our first test.

### GPU and llama.cpp

The #2566 report originally crashed E4B on G5 using LiteRT-LM 0.11.0/0.13.1. Its
**12 July 2026** follow-up reports E4B GPU working on a GrapheneOS Pixel 10 Pro Fold
with 0.12.0 and possibly changed model bytes. Another commenter still had crashes.
This demonstrates feasibility, not universal compatibility or an instruction to
downgrade every installation. Pin and test a model/runtime pair.

llama.cpp CPU is a valuable fallback with a broad GGUF selection. Vulkan on the
actual Pixel 10 Pro has a **reported correctness bug**: Qwen2.5-1.5B produced repeated
garbage tokens with default GPU offload. The reporter obtained coherent output by
disabling cooperative-matrix and integer-dot-product paths. This is not proof that
all current Vulkan versions are unusable, nor that the workaround is fast.
[llama.cpp #28502](https://github.com/ggml-org/llama.cpp/issues/28502).

Do not transfer the Pixel 10a Mali bug directly to Tensor G5. That separate report
concerns long prefill with the app backgrounded/screen off; foreground worked.
Nevertheless, background and screen-off testing belongs in our acceptance matrix.
[llama.cpp #23359](https://github.com/ggml-org/llama.cpp/issues/23359).

### GrapheneOS

GrapheneOS documents a toggle for **direct TPU/GXP access by specified Google apps**.
It is not evidence that an arbitrary Ballistic APK receives the same interface.
The E4B Fold report establishes a GPU example; it does not establish Tensor NPU
access for Ballistic on GrapheneOS. Test NPU loading and inference from the actual
app UID, not only an ADB shell process. Package native libraries with the app and
test with the default hardening settings. No weakening of OS security is justified
by a model-loading failure.
[GrapheneOS usage documentation](https://grapheneos.org/usage).

## Candidate shortlist

| Candidate | Why test it | Limitation / priority |
|---|---|---|
| **Gemma 4 E2B instruct** | Published Tensor G5 bundle, native function calling; CPU/GPU controls | First hardware candidate; task success still unknown |
| **Gemma 4 E4B instruct** | Larger quality candidate with reported G5 GPU success | Version-sensitive; no G5 NPU artifact found; compare full step latency |
| **Qwen3-4B** | Text planner control; published LiteRT INT4 and GGUF paths | Current LiteRT GPU export needs 0.19.0+; 4,096-token artifact context; disable thinking for first comparison |
| **Qwen3.5-2B / 4B** | Newer family worth an offline quality comparison | Official 4B card documents tool serving, but no verified Pixel 10 Pro latency or Tensor NPU path found |
| **LFM2.5-8B-A1B** | CPU-oriented hybrid MoE, official GGUF; 8.3B total / 1.5B active per vendor | Larger resident weight set; no validated G5 LiteRT bundle found; exploratory after first three |
| **UI-Voyager 4B** | Author reports 81.0% AndroidWorld Pass@1 | Qwen3-VL screenshot agent, not a drop-in text-tree planner; separate future vision experiment |

Sources: [Gemma model card](https://huggingface.co/google/gemma-4-E2B-it),
[Qwen3 LiteRT card](https://huggingface.co/litert-community/Qwen3-4B),
[Qwen3.5 card](https://huggingface.co/Qwen/Qwen3.5-4B),
[Liquid model card](https://huggingface.co/LiquidAI/LFM2.5-8B-A1B),
[UI-Voyager paper](https://arxiv.org/abs/2603.24533),
[UI-Voyager weights](https://huggingface.co/MarsXL/UI-Voyager).

The Qwen3 export changed under the same filename on **31 October 2026**. Its card
reports 526 prefill / 19.5 decode tok/s on a Galaxy S26 GPU at 1,024 input tokens;
that is a vendor/converter result on another phone. Record revision hashes, not just
filenames. Native tool support and general benchmarks do not establish Ballistic's
one-action schema or its multi-role safety behavior. Likewise the old note about
an LFM2 real-phone failure must not be relabeled as an LFM2.5 benchmark.

## Memory planning: file size is not peak RAM

Hugging Face repository metadata checked **7 November 2026**:

| Artifact | Bytes | Repository revision |
|---|---:|---|
| E2B `gemma-4-E2B-it-gpu.litertlm` | 2,008,432,640 | `3d48132a00cf76de57d926de30f5c5c5ed9b8c0d` |
| E2B `gemma-4-E2B-it_Google_Tensor_G5.litertlm` | 3,206,299,658 | same |
| E4B `gemma-4-E4B-it-gpu.litertlm` | 2,969,059,328 | `71064b01bd5e2bfd9a5f9dd52df86ae4800138c1` |
| LFM2.5 `LFM2.5-8B-A1B-Q4_K_M.gguf` | 5,155,564,768 | `49c14831707011e64d70b2ebd8462ba08d608434` |

[E2B files](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main),
[E4B files](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/main),
[LFM GGUF files](https://huggingface.co/LiquidAI/LFM2.5-8B-A1B-GGUF/tree/main).

These are download sizes, not installed working sets or measured RAM. Account for
KV cache, graph temporaries, GPU/NPU buffers, native runtime, Android and target apps.
Use one resident model across roles initially; role separation is a prompt/capability
boundary and does not require four simultaneous model copies. Multiple sessions
can still retain separate caches, so measure them explicitly.

## Reproducible next experiment

1. **Finish Gemini emulator validation first.** Preserve its exact task seed/setup,
   prompts, action schema, confirmations and run logs. Capture representative filtered
   screens, including long lists, numeric fields and hostile content cases.
2. **Offline quality screening:** use those same inputs with the first three candidates,
   explicit thinking settings and a fixed output budget. Record valid-schema rate,
   correct element/action rate, false `done`, reader extraction accuracy, judge false
   blocks and injection-test outcomes. Do not give the planner content it normally
   cannot see simply to improve a benchmark score.
3. **Phone benchmark:** record build fingerprint, GrapheneOS version, runtime/library
   hashes, model SHA256/revision, backend actually selected, context limit and sampler.
   Benchmark 512, 2,048 and 4,096 input tokens (where the bundle supports input plus
   output), with 64 generated tokens. Report cold initialization separately. Perform
   one warmup and at least five warm iterations, then 15 minutes of repeated realistic
   calls to expose throttling. Compare screen-on, background and screen-off cases.
4. Report median and p95 TTFT, prefill/decode speed, end-to-end completion latency,
   peak PSS/RSS, available RAM, thermal status, battery change, crashes and wrong output.
   Validate numbers/IDs as well as JSON syntax: coherent-looking GPU output is insufficient.
5. Run the **same 20 AndroidWorld tasks sequentially** with the winner candidates using
   the same core, seed and confirmation policy. A laptop run measures agent quality;
   only a phone backend measures phone speed. A later phone-hosted completion endpoint
   can drive the emulator while inference remains on the phone. Keep infrastructure,
   runtime, policy denial and model failures separate. Repeat before ranking close scores.

LiteRT-LM documents benchmark options including `--benchmark`,
`--benchmark_prefill_tokens=2048`, `--benchmark_decode_tokens=64`, `--async=false`
and peak-memory reporting. Use the options supported by the pinned binary and a
context larger than input plus output; save its help/version with results.
[Build and benchmark documentation](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/getting-started/build-and-run.md).

For interpreting results, **estimate** one role call as
`input_tokens / prefill_rate + output_tokens / decode_rate + overhead`.
At 15 decode tok/s, 64 output tokens alone cost about 4.3 seconds. A planner plus
deny-only judge costs more than one call; reader/verifier and UI waits add further
time. Prefix-cache reuse can help but must be measured with changing screens and
role prompts. The acceptance target is the complete agent step, not decode speed.

Outstanding: actual pp2048 on the owner's Pro; NPU from the GrapheneOS app UID;
sustained thermal behavior; correct tool output on each backend; task quality after
quantization; and total memory with target apps running. Until those exist, select
an experiment, not a permanent default model.
