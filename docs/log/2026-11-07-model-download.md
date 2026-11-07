# In-app starter-model setup — 7 November 2026

The owner authorized the previously proposed download/verify/load onboarding flow and asked for
the takeaway on adapting open weights. Harness reliability and benchmark loops remain paused.

## Starter choice and provenance

The first catalogue entry is the official [Qwen3-0.6B Q8 GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/23749fefcc72300e3a2ad315e1317431b06b590a).
Hugging Face model/artifact metadata was checked on 7 November 2026:

- Revision: `23749fefcc72300e3a2ad315e1317431b06b590a`.
- File: `Qwen3-0.6B-Q8_0.gguf`; 639,446,688 bytes.
- SHA-256: `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`.
- License declared by Qwen: Apache-2.0.

This is a bounded starter **preview**, not a validated phone default. All current phone profiles
offer the same small download. The official current repository contains Q8; an earlier Q4 artifact
was removed from its main tree. This implementation pins the current published artifact rather
than resurrecting the removed quantization. No quality claim is inferred from that repository change.

## Implemented behavior

Local AI shows model name, size, license, free storage and preview status. The setup confirmation
states internet use, replacement behavior, RAM needs and foreground-only download limitations.
Explicit confirmation streams the file into private staging, checks exact size and SHA-256,
installs the verified copy, selects local inference and loads it. It never sends prompts or model
API keys to the file host, or falls back to connected inference. Normal app networking is unchanged.

The old managed copy survives failed/cancelled transfers and bad hashes. The installed copy has
catalogue ID/digest metadata; older manual-import metadata remains readable. Failed loading leaves
the verified model installed. Changing models remains blocked during an agent run. Manual import
is retained. A private source copy is never deleted.

The downloader follows at most five redirects, requires HTTPS and supported Hugging Face/CDN
hosts, uses connection/read timeouts, bounds received bytes and checks storage. Stop disconnects
the captured transfer; partial files are discarded. Process startup clears abandoned partials.
There is no background foreground-service/DownloadManager integration or range-resume support;
users are told to keep Ballistic open and retry interrupted transfers from the start.

## Takeaway on adapting weights

We have integrated an inference runtime and pre-existing model files; **we have not fine-tuned a
Ballistic model or measured a tuning improvement**. Our current hypothesis is to first establish a
base-model result on the stable agent interface, then train on verified examples of the actual
filtered observations and valid actions. Candidate targets include action-format fidelity,
navigation decisions and completion/stop decisions. Missing observations or platform capabilities
still require engineering, not a weight update.

A bounded [LoRA experiment](https://huggingface.co/docs/peft/main/en/conceptual_guides/lora) can adapt
a small subset of parameters before merging/exporting weights for inference. This is a proposed
next experiment, not a training run authorized or performed here. Use appropriately licensed data,
hold out task templates/apps from training, and compare base/tuned models after the same export
and quantization. Measure correctness, refusal/approval behavior, latency and memory. Model
training does not replace the core's code-enforced permissions and exact-operation approval.

The prior broad review is research context, not accepted proof of a performance gain; its numerical
claims and proposed trust rules needed correction. No external benchmark number is treated as a
Ballistic result. The 20-task benchmark remains paused and was not used for this delivery.

## Validation and delivered APK

- Initial JVM suite: **71 tests, 68 passed, 3 skipped**, no failures/errors. The skipped tests
  were the two optional native model tests and the explicit live-download test.
- Final downloader suite: **9 passed, zero skips/failures/errors**, including the real HTTPS
  transfer through the shipping downloader. The installed file matched 639,446,688 bytes and
  the pinned SHA-256, then generated **9 tokens** through the existing host JNI runtime.
- Offline cases cover digest mismatch, truncated/oversized transfers, cancellation before
  installation, storage rejection before network access, partial cleanup/restart, redirect-host
  restrictions and legacy import metadata. No old model replacement occurs in those failures.
- Final Android Compose compilation and `:app:assembleDebug`: **BUILD SUCCESSFUL**. Native
  libraries for ARM64 and x86_64 are packaged. The unchanged Rust libraries from the previous
  validated app build were reused. Parent verified the APK v2 signature, native library inventory,
  shipped catalogue equality and absence of bundled weights.
- APK: `ballistic_runs/model_download_20261107/Ballistic-model-setup-debug.apk`;
  **53,766,650 bytes**, SHA-256 `8120c39a4218887bb1a4f2e0147c628b71ed45a1f81f3286245d0553c17754a1`.
- Logs, XML and artifact metadata: ignored `ballistic_runs/model_download_20261107/`.
  An earlier packaging invocation was stopped while the final test/build invocation completed;
  only the final successful build supplies this APK.
- ThinkPad SSH timed out again. No Android UI, download lifecycle, memory, speed or real-device
  biometric test is claimed. The live JNI test used the laptop, not the Pixel or emulator.

No model weights were modified or fine-tuned. No model-inference API key was used, and no benchmark
run was started. The 639 MB file download is the only new real model transfer in this task.
