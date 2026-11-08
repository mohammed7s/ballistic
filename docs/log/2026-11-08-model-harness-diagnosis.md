# Model, runtime and agent diagnosis — 8 November 2026

The owner reopened bounded evaluation work to distinguish model quality from harness behavior,
requested latency calculations and asked about frontier-model controls followed by small-model
specialization. This is a diagnostic experiment, not resumption of outcome-driven suite tweaking.

## Verified starting points

- The 6 November T3A baseline was **13/20 with gemini-flash-lite-latest**, not phone-local
  Qwen3 0.6B. It used AndroidWorld observations/actions. Recorded agent time was 1,496 s,
  median 66.4 s, 281 model calls and 3.0 million prompt tokens. Its documented failures include
  planning, observation, app resolution and network faults. See the archived baseline in
  `2026-11-07-before-reorganization-context.md`.
- Latest complete Ballistic core evaluation: **8/20 combined, 11/20 benchmark state**, at
  `05ab6eb`. This is not a controlled comparison with T3A. Deliberate five-second request
  pacing in that round is part of its wall time, not model inference latency.
- Current phone starter: Qwen3 0.6B Q8 GGUF, 639,446,688 bytes. The short counting check measured
  0.4 s first token and 21.8 generated tokens/s. Agent-length prefill throughput has not been
  established by that check; it is not a general latency benchmark.
- Native engine uses CPU only (`n_gpu_layers=0`), disables thinking, clears KV context between
  calls/roles and returns a whole completion. Those facts come from `app/src/main/cpp/engine.cpp`.
- Ask starts a fresh task run; previous displayed requests are not conversation history.
  `core/src/agent.rs` returns unsupported for `Action::Answer` because answer provenance is
  unfinished. Normal conversation needs its own tool-free, streamed chat path and bounded history;
  replacing the model alone cannot add that app behavior.
- The APK has notification observations and open_app/wait, unlike the AndroidWorld bridge's
  screen tree and UI input. Installed APK/native-core build provenance must be checked before
  asserting source/binary parity. A flagship model cannot create missing platform capabilities.

## Latency arithmetic

For a warm sequential call, a useful approximation is
`time ≈ input_tokens / prefill_tokens_per_second + (output_tokens - 1) / decode_tokens_per_second + overhead`.
For a task, sum this over model calls and add observation, action, approval, pacing and retry
time. Cold starts add model load time. Streaming lowers time until text is visible, not time
until the whole answer or a validated action is ready.

At the measured short-check decode rate, 100 additional tokens cost about 4.6 s and 500 cost
about 22.9 s, before prompt processing. These are arithmetic extrapolations, not measured
agent latencies. If a 2,000-token prompt processed at an illustrative 250 tokens/s, prefill alone
would take 8 s; that input speed is not a Pixel measurement.

Dense weight memory alone is approximately `parameters × bits_per_weight / 8`: 0.6B at 4-bit
is 0.30 GB, 1.7B is 0.85 GB, 4B is 2.0 GB. Real files add quantization metadata and some
higher-precision tensors; runtime RAM additionally includes KV cache, activations and buffers.
Model size does not directly specify speed. Quantization, kernels, effective bandwidth, context,
threading and thermal conditions must be measured. Hardware peak TOPS is not a token-rate estimate.

References: [NVIDIA inference phases](https://developer.nvidia.com/blog/mastering-llm-techniques-inference-optimization/),
[Qwen model card](https://huggingface.co/Qwen/Qwen3-0.6B),
[official GGUF model](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF).

## Proposed controlled pilot (not yet executed)

Candidate source pin: `51450340ee587c33d9c371fa6a42f3b69cde185b`. Do not run the unrelated dirty
main checkout. Freeze exact model IDs, request options, prompts, task seeds, initial app state,
capabilities, approval policy, native binary/APK hashes and metrics before starting.

1. **No-model controls:** known valid actions and captured observations establish whether an
   adapter can read the required information, execute and independently verify each operation.
   Missing capabilities are unsupported; denied operations are policy outcomes.
2. **Frozen decision cases:** twelve preselected synthetic/benchmark states, including planning,
   content selection and completion judgments. Compare Flash-Lite, one frontier model and the
   current small model on identical role inputs. One attempt per case/model, no outcome-driven
   retries or prompt edits. At most 24 cloud generation attempts; local calls counted separately.
   Label multiple acceptable decisions and unknown evidence before viewing candidate outputs.
3. **Live core pilot:** fixed OpenAppTaskEval, ClockStopWatchRunning, ClockTimerEntry, seed 0,
   each once with baseline and frontier model. Freeze original task budgets/role caps, with a
   total ceiling of 60 cloud HTTP attempts for the pilot. Stop on 429/auth/billing/environment
   failure; no key rotation. Record unattempted tasks rather than counting them as failures.
   One visible emulator only, exclusively owned, with independent state checking and no fixes
   between arms. This screens for problems; it is too small to claim a general success rate.
4. **APK parity smoke:** same pinned native core, selected backend, supported app grants and
   synthetic task prompts on the emulator APK. Check allowed app launch, denied launch,
   unsupported alarm/control, cancellation and completion reporting. The full AndroidWorld
   suite cannot be called an APK evaluation without implementing and labeling a development-only
   observation/input bridge or the actual platform adapter. Never expose benchmark truth to the agent.
5. **Phone runtime matrix:** keep the model loaded; measure short and 512/2,048-token prompts,
   32/128-token output caps, first-token time, prefill/decode rates, total latency and thermal
   conditions separately. Compare current Q8 with a verified Q4 candidate before choosing a
   larger 1–2B model. Emulator token speed is not phone token speed. Safety-role cache isolation
   must remain intact when testing safe prefix reuse.

Failure labels: environment/quota, observation, capability/policy, model decision, invalid model
output, dispatch, verification and UI/lifecycle. Preserve raw events; report agent claims,
independent state and unintended side effects separately. Frontier success is evidence that a
path is possible, not proof the harness is bug-free; a frontier can compensate for poor context.

## Specialization after diagnosis

First fix missing capabilities, direct chat, unnecessary input/output volume and repeated calls.
Then collect frontier demonstrations validated by independent task outcomes and policy checks.
Fine-tuning teaches the small model the bounded action format and decisions; distillation uses
teacher examples/targets. Neither restores missing observations or permissions, nor guarantees
frontier-level general reasoning. Evaluate on held-out task templates, app layouts, recovery,
injection and refusal cases; then quantize and repeat quality/phone-latency measurements. Keep
training and evaluation data separate. A LoRA does not itself make the same base model smaller
or faster; improvement may come from shorter valid outputs and fewer retries.

## Execution status

No frontier generation, emulator task, fine-tuning or distillation was started. A read-only attempt
to reach the ThinkPad and query the existing Gemini model catalogue timed out at SSH before the
API request ran. The owner selected **the existing Gemini API with a Pro model**. A second SSH
attempt also timed out before the model-catalogue request could run; exact Pro model access and
quota remain unverified. Existing source reads and published documentation support
the diagnosis above, not a new benchmark result.

## Tensor G5 accelerator research — 8 November 2026

Google's current [Tensor SDK documentation](https://developers.google.com/edge/tensor-sdk/slm-compilation)
lists precompiled Tensor_G5 (Pixel 10) LiteRT-LM bundles, including Gemma3-1B, Mobile Actions
and Gemma4 E2B. Its custom fine-tuned SLM compilation instructions explicitly target Tensor_G6;
they do not establish arbitrary fine-tuned Qwen or Gemma deployment on G5. Compiler access is
through an approved beta. Check this constraint before selecting a training target.

The [official Pixel 10 sample](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/google/sample_app_tpu/README.md)
uses LiteRT-LM and libLiteRtDispatch_GoogleTensor.so, with NPU/GPU/CPU fallback. This gives an
app-side integration route, not evidence of successful execution on our GrapheneOS installation.
The current llama.cpp/GGUF CPU backend cannot gain this support simply by selecting NPU;
a separate backend and compatible compiled model are needed behind the existing Models port.
Planning, reader isolation, grants and action dispatch remain in the existing architecture.

The SDK-linked [G5 Gemma4 E2B bundle](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm)
is listed as 3.21 GB; this is download size, not peak runtime memory or a measured speed.
[Pixel 10 Pro specifications](https://store.google.com/product/pixel_10_pro_specs)
list 16 GB RAM, which is not an application memory budget. Accelerator compatibility, available
RAM including cache/buffers, and acceptable sustained latency are separate acceptance criteria.

Proposed first check: run the official sample with a pinned G5 bundle on the actual phone,
verify the backend really uses TPU rather than falling back, then measure first-token time,
prefill/decode, peak RAM and sustained thermal behavior. Only then integrate a LiteRT-LM
backend and model download into Ballistic. No accelerator sample was installed or benchmarked;
no model weights were downloaded and no OS/security configuration was changed in this research.
