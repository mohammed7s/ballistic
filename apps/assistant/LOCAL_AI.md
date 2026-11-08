# Local AI in Ballistic

8 November 2026. Implementation and validation status are recorded in the delivery log; an
installable debug APK does not establish model quality or production phone-control readiness.

## Where it sits

The native Ask screen submits a request to the same Rust core used by the development harness.
The core owns planning, filtered observations, action validation, verification and approval requests.
When it needs text from a model, its Models port calls the selected backend:

- **On device, llama.cpp:** Kotlin routes the call through JNI to embedded llama.cpp (CPU) and a GGUF.
- **On device, LiteRT-LM:** `LiteRtLmDriver` runs a `.litertlm` bundle with Google's LiteRT-LM
  Android library on the CPU, GPU or NPU backend the owner chose.
- **Connected model:** the existing OpenAI-compatible HTTP adapter uses the owner's endpoint.

The runtime is packaged in the APK; model weights are a separate verified download or managed import. There is no
local server or Ollama process in this path. Android hosts Screen, Hands and Confirm separately;
adding local inference does not grant screen access or additional action permissions.

Planner, reader and verifier are distinct calls with core-owned inputs and output checks. The
local backend shares one loaded model across roles, serializes inference and clears the context
between calls. Separate calls are not process isolation or a claim of injection immunity. The
existing core safety limitations still apply. The judge role is not a completed deny-only judge.

## Using the app

1. Unlock the phone, then open **Local AI**. If you manually locked requests in Ballistic, use
   **Enable requests** first. There is no second app fingerprint prompt.
2. In **Models**, open the dropdown. Each entry shows size, runtime, the hardware it was built
   for, and whether it is downloaded, gated or active.
3. **Download** shows the file, size, licence and storage needed; confirm **Download & use on
   device**. No transfer begins just by opening the screen. Ballistic checks the byte count and
   SHA-256, keeps the file beside the other models, selects on-device inference and loads it.
4. **Use** switches models: it unloads whatever is loaded, selects that model on device and
   loads it. **Delete** removes only that managed copy. If loading fails, nothing is loaded and
   nothing else is tried; the verified file stays for a later attempt.
5. Run **Short check** and **~2K prefill check**, then return to Ask.

## Model catalogue

The catalogue ships in the APK (`app/src/main/assets/model_catalogue.json`). Each entry pins a
Hugging Face file at an immutable commit with its byte size and SHA-256, taken from the Hugging
Face API's LFS metadata on 8 November 2026. Downloads are verified against both before use.

| Entry | Runtime | Built for | Bytes | SHA-256 | Licence | Gated |
| --- | --- | --- | ---: | --- | --- | --- |
| Qwen3 0.6B Q8 (`Qwen/Qwen3-0.6B-GGUF`) | llama.cpp | CPU | 639,446,688 | `9465e63a…bb031` | Apache-2.0 | no |
| Qwen3 1.7B Q8 (`Qwen/Qwen3-1.7B-GGUF`) | llama.cpp | CPU | 1,834,426,016 | `061b54da…0cb1a` | Apache-2.0 | no |
| Gemma 3 1B Q8 GGUF (`ggml-org/gemma-3-1b-it-GGUF`) | llama.cpp | CPU | 1,069,306,368 | `b205840c…10d5` | Gemma Terms of Use | no |
| Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, `gemma-4-E2B-it.litertlm`) | LiteRT-LM | GPU (or CPU) | 2,588,147,712 | `18193810…a63c` | Apache-2.0 | no |
| Gemma 4 E2B Tensor G5 (same repo, `…_Google_Tensor_G5.litertlm`) | LiteRT-LM | Tensor G5 NPU | 3,206,299,658 | `72d05606…84191` | Apache-2.0 | no |
| Gemma 3 1B Tensor G5 (`litert-community/Gemma3-1B-IT`, `…q8_ekv1280_Google_Tensor_G5.litertlm`) | LiteRT-LM | Tensor G5 NPU | 2,018,723,808 | `c418f40c…410a` | Gemma Terms of Use | **yes** |
| Qwen3 0.6B LiteRT-LM (`litert-community/Qwen3-0.6B`) | LiteRT-LM | CPU (or GPU) | 613,722,264 | `c5f39b41…2f108` | Apache-2.0 | no |

Full digests and revisions are in the JSON file. The two Tensor G5 entries also carry
`contextTokens`, the KV cache they were compiled with: 4,096 (Gemma 4 E2B) and 1,280 (Gemma 3
1B). Other entries have none; their context is the owner's context setting.

**Context limit for core.** The Models port advertises the loaded model's context to core
(`Models.limits`, crossing the binding as `model_limits`): a compiled bundle's `contextTokens`,
otherwise the context the runtime reported it started with (llama.cpp, LiteRT-LM CPU/GPU). The
same limit applies to every role, since all roles share the one loaded model; a connected HTTP
model advertises none. Core's context guard (core/README.md §12, "Context guard") measures every
prompt against it and trims core-written history, old reader facts or chat turns, then lowers the
output budget, in that order, or refuses the call with "prompt does not fit this model's
4,096-token context". It never relies on the runtime to cut a prompt. Implemented and covered by
JVM tests (`ContextLimitTest`, `UniffiCoreEngineTest`) and core's fake-port tests; not yet run
on a phone. Core estimates prompt size (no Kotlin host supplies an exact tokenizer count): the
estimate overstated recorded planner prompts by about 1.28x, so on the G5 bundle a planner
prompt above roughly 2,570 real tokens loses its oldest history lines before the 800-token output
budget is lowered. The Gemma 3 1B G5 bundle (1,280 tokens) cannot hold the planner's rules, so
core refuses every agent run on it before asking the model.

The two Tensor G5 entries are the bundles that
Google's [Tensor SDK page](https://developers.google.com/edge/tensor-sdk/slm-compilation) links
for Pixel 10. Google also lists a Tensor G5 "Mobile Actions" FunctionGemma bundle; its
repository is gated and is not in this catalogue.

**Gating.** On 8 November 2026 an unauthenticated request for the Gemma 4 E2B files returned a
redirect to the CDN (downloadable without an account). `litert-community/Gemma3-1B-IT` returned
HTTP 401 `GatedRepo`: it needs a signed-in Hugging Face account that accepted the Gemma terms.
Ballistic never asks for, stores or sends a Hugging Face token. For a gated entry, download the
named file on a computer, copy it to the phone and use **Import verified file**: Ballistic copies
it and accepts it only if its size and SHA-256 equal the catalogue entry. Any entry can be
imported this way.

**Backends.** A llama.cpp entry runs on CPU only (this build of llama.cpp has no GPU backend).
A Tensor G5 bundle is compiled for that NPU and is offered on NPU only. A generic LiteRT-LM bundle
offers GPU and CPU; the owner picks one with the chips under the entry. Nothing switches backend,
model or to a connected model after a failure. The status card shows **Running on:** the runtime
and the backend the engine was started on. LiteRT-LM has no API that returns "the backend in use";
per the LiteRT-LM 0.19.1 source (read for the earlier build; not re-read for 0.18.1), the engine
builds the executor for the requested backend or fails, so a successful start on NPU is the
runtime's own acceptance of that backend, not a separate hardware trace. NPU execution is claimed
only when LiteRT's log shows nodes delegated to the dispatch delegate and a speed check completes.
Logcat also carries LiteRT-LM's native log lines.

**Tensor NPU support.** The APK includes `com.google.ai.edge.litertlm:litertlm-android:0.18.1`
(arm64-v8a and x86_64) and the official `com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.2`,
which packages `libLiteRtDispatch_GoogleTensor.so` (arm64-v8a only) and declares the optional
vendor `libedgetpu_litert.so`/`libedgetpu_util.so`. The manifest also declares
`libedgetpu_client.google.so`, `libOpenCL.so` and `libvndksupport.so` as optional, as Google's TPU
sample and LiteRT-LM guide do. Native libraries are extracted at install (legacy packaging) so the
dispatch library is in `nativeLibraryDir`, where LiteRT-LM loads it; this makes the installed app
larger. A native crash inside the vendor runtime cannot be caught by the app. After each
LiteRT-LM start, the status card adds a summary of LiteRT's own log for that start (whether the
Google Tensor dispatch runtime initialized, and "N/M nodes on <delegate>" counts), read from the
app's own process log; that is the runtime's report of where the graph was placed.

**Why an NPU start failed.** `TensorDiagnostics` (core-api, unit-tested) names one cause and never
blames the phone for a packaging fault:

| Cause | How it is recognised | What the app says |
| --- | --- | --- |
| (a) Not a Tensor device | Before the start: ABI is not arm64-v8a, or the SoC manufacturer is not Google | "This device is x86_64, not ARM64…" / "This phone's SoC reports …, not Google Tensor…" |
| (b) Our packaging | Dispatch library absent from `nativeLibraryDir`; LiteRT log has `undefined symbol`/`Could not load symbol LiteRt…`, `Dispatch library not found`, a legacy-symbol warning or a failed Dispatch ABI negotiation | "Ballistic's packaged Tensor runtime is incompatible: <symbol> is missing from libLiteRtDispatch_GoogleTensor.so…" |
| (c) Vendor stack refused | `avc: denied`, `PERMISSION_DENIED`, `Permission denied`, the dispatch library's southbound load/initialise failures, or a failure line naming `libedgetpu*`/`libdarwinn*` | "This phone's Tensor NPU stack refused Ballistic or is not visible to it…" |
| (d) Bundle not for this chip | The dispatch library's SQ container/bytecode load failures, unsupported compiler/runtime/SoC versions | "This model bundle was not compiled for this phone's Tensor chip or this runtime version." |
| Unclassified | None of the above | "LiteRT-LM could not start its NPU backend… LiteRT's log does not name a known cause." |

The status card shows the deciding LiteRT line (or the first failure line other than the
accelerator-plugin line every start logs) as **Details (runtime log)**, without the logcat prefix
and with every path reduced to its file name. GPU and CPU start failures show the same detail line.

**Tensor runtime pair (8 November 2026).** LiteRT-LM bundles its own LiteRT runtime, which loads
`libLiteRtDispatch_GoogleTensor.so` and looks up its entry symbol. The two must speak one LiteRT
Dispatch ABI. On 8 November 2026 the owner's Pixel 10 Pro, with LiteRT-LM 0.19.1 and dispatch 2.3.2,
logged `Could not load symbol LiteRtDispatchQueryInterface: undefined symbol:
LiteRtDispatchQueryInterface` and `No usable Dispatch runtime found`; the earlier build reported this
as "Tensor accelerator not available on this phone/OS", which was wrong: the cause was the APK.

Evidence, from primary sources:

- Google Maven lists `litert-npu-runtime-google-tensor` 2.3.0 and 2.3.2 only (2.3.2 latest) and
  `litertlm-android` up to 0.19.1.
- `llvm-nm -D --defined-only` (NDK 29) on the AARs' `libLiteRtDispatch_GoogleTensor.so`: 2.3.0 and
  2.3.2 each export exactly one dispatch symbol, `LiteRtDispatchGetApi@@VERS_1.0`. The 2.3.2 library
  (SHA-256 `6d378a84…33f18`) has no undefined `LiteRt*` symbols; it needs only libandroid, libdl,
  liblog, libc and libm, and opens the vendor `libedgetpu_litert.so` at run time.
- `liblitertlm_jni.so` (arm64-v8a) resolves the dispatch entry by name (`dlsym`), so the name is a
  string in the library, not a dynamic import. Its dispatch symbol strings: 0.16.1, 0.17.1, 0.18.0
  and 0.18.1: `LiteRtDispatchGetApi`; 0.19.0 and 0.19.1: `LiteRtDispatchQueryInterface` (plus
  `LiteRtDispatchInitialize`, which LiteRT only uses to warn about a legacy library).
- LiteRT commit `f59b793` (20 Oct 2026, "Migrate the LiteRT Dispatch C ABI to versioned interface
  tables") introduced `LiteRtDispatchQueryInterface`. LiteRT-LM v0.19.1 pins LiteRT `dcb657e`, 104
  commits after it; v0.18.1 pins `ca21dc9`, 233 commits before it. LiteRT's v2.3.2 tag is on a
  release branch without it. At `ca21dc9` and at v2.3.2, `litert/vendors/c/litert_dispatch.h` and
  `litert_dispatch_api.h` are byte-identical and `LITERT_API_VERSION` is 0.1.0 in both.
- The LiteRT binaries bucket's moving `nightly` dispatch library (not a release) exports
  `LiteRtDispatchQueryInterface`; no versioned release does yet.
- Google's `sample_app_tpu` (litert-samples `958de2e`, 14 Oct 2026, its latest revision) uses
  LiteRT-LM 0.12.0 with LiteRT 2.3.0; the Tensor skill's `versions.json` pins Maven 2.3.0 for
  `litert` and `litert-npu-runtime-google-tensor`. Both predate the ABI change.

Chosen pair: **LiteRT-LM 0.18.1 + Google Tensor dispatch 2.3.2**, the newest release pair whose
runtime looks up the one symbol the dispatch library exports, with matching dispatch headers. Do not
move LiteRT-LM to 0.19.x until a released dispatch library exports `LiteRtDispatchQueryInterface`
(check with `llvm-nm -D --defined-only` as above).

**Context.** For llama.cpp and generic LiteRT-LM bundles the Context setting (2K/4K/8K) is passed
to the engine. A compiled NPU bundle fixes its context; Ballistic does not pass a size. The Gemma 3
1B G5 bundle's name records a 1,280-token KV cache, so the ~2K prefill check cannot fit there and
it cannot serve as the planner. The Gemma 4 E2B G5 bundle's largest compiled KV cache is 4,096
tokens (read from its signatures on the phone, 8 Nov 2026), enough for a ~2.5K planner prompt with
limited headroom (see the measurement below).

**Storage.** Each catalogue file lives under `no_backup/local-model/catalogue/<id>/`. The
original slot `no_backup/local-model/model.gguf` keeps your own imported GGUF. A starter downloaded
by an earlier build is moved into its catalogue slot at startup (a rename, not a re-download).
The screen shows storage used by Ballistic models and storage available.

The Qwen3 0.6B Q8 starter remains a **preview**, not a measured phone-agent winner. A
device-specific quality/performance recommendation still requires target-phone testing.

Downloads contact Hugging Face and its HTTPS CDN, not a model inference service. This transfers
model files without sending prompts or API keys. The catalogue is shipped with the app and pins
an immutable source revision and digest. Owner-started setup runs with a foreground service, so
you may leave the app. Transfers are not resumable. Stop discards the partial file; process
restart cleans abandoned partials. Retry starts from byte zero. Existing managed weights survive
network, size or digest failures. A completed verified replacement may remain installed if Stop
arrives during loading.

The phone needs its own internet connection; an ADB/USB debugging connection does not provide
internet access. Transfer progress is shown in decimal MB and a percentage, with download errors
beside the setup button. Ballistic keeps the screen awake during local setup while its activity
is visible. Turning the screen off blocks new requests; already-started work continues.

The command gate checks the phone’s secure unlock state when a request is submitted. Switching
apps does not relock it. A manual app lock disables new requests until re-enabled; sleeping or
locking the phone blocks new requests. Android’s configured unlock methods are trusted; an
unlocked-state check does not prove that a fingerprint rather than a PIN was used. This does not
grant Android notification access or Computer Control, or approve a sensitive operation.

While work is active, the app runs a foreground service with a Stop action and elapsed time in
its notification. Enable **Settings → Task notification settings** to see it. Ask also displays
a live elapsed timer, frozen at completion, and **Continue in background**. Force-stopping the
app or process death interrupts work; no task is automatically resumed. Android may still block
a background action that needs foreground UI. This service does not supply screen-control access.

During an Ask run, timestamped progress lines show observed lifecycle events: reading available
information, waiting for a model, receiving a reply, dispatching and recording actions. The last
six lines stay visible, with time since the latest update during a long call. These are execution
events, not streamed model reasoning or an estimated percentage. Previous runs on Ask start
collapsed; **Show details** expands their history. Activity groups steps by request and also
starts collapsed. In-process progress keeps the latest 250 events per run; after process restart,
Activity retains the existing persisted action steps rather than the transient progress feed.

Manual **Import GGUF** remains available. Ballistic copies a compatible chat/instruct file into
private storage and leaves the source untouched. Imported files are validated by native load;
only catalogue downloads and **Import verified file** receive the publisher-artifact digest check.
Your own import always runs on llama.cpp CPU; there is no unverified `.litertlm` import.

The initial local configuration may need a larger context for an agent request than for the
short speed check. A context overflow fails explicitly; the runtime does not silently truncate
the request or send it to a server. A tiny model that passes the speed check is not necessarily
capable of producing valid agent actions.

Select a connected model explicitly to use an endpoint configured in Settings. Existing HTTP
configuration and encrypted API keys are retained. A missing, invalid or failed local model
never triggers a cloud fallback. Backend selection stays fixed throughout an Ask run; finish or
stop the run before changing it.

Model copies live under Android's no-backup app directory. Unload releases model resources;
remove deletes only the managed copy. After a process restart, load the model again. Stop
requests cancellation of local work and prevents subsequent core dispatch; it cannot undo an
action already submitted. Connected-model HTTP cancellation retains the existing limitations.

## What the overview measures

The Local AI screen reports load duration, first-token latency and model-call timing from this
process. Detailed metrics include prompt and output counts and processing/generation rates.
Unavailable values say **Not measured**. Two speed checks exist: **Short** (a counting prompt, up
to 32 output tokens) and **~2K prefill** (a fixed synthetic list of 90 screen elements, about two
thousand prompt tokens, up to 16 output tokens), because an agent planner call carries an
agent-length prompt that the short check does not exercise. The runtime reports the exact prompt
token count. Each finished check is listed under **Results this session** with model, runtime,
backend, prompt tokens, first-token time, prefill and decode rates, and written to logcat (tag
`Ballistic`, numbers only, no prompt text). For LiteRT-LM the rates and first-token time are its
own benchmark info (`Conversation.getBenchmarkInfo`); for llama.cpp they are its timing counters.
Every call uses a fresh context. These checks measure model execution only, not task success,
battery life, peak memory or sustained thermal performance.

Phone profiles are suggested CPU/context settings and candidate-model guidance. They are not
validated performance ratings. Downloads require the owner to confirm setup. Pixel results require measurements on a
Pixel; laptop and emulator results must be labeled separately.

## What was measured where (8 November 2026)

**Build.** ThinkPad, JDK 21, Gradle 9.9.0, NDK 29.0.14206865, CMake 3.31.6: `:core-api:test
:app:assembleDebug` succeeded. 93 JVM tests, 0 failures/errors, 12 skipped (the optional
UniFFI/JNI/live-download tests, whose host libraries or models were not supplied), including 9 new
catalogue/switching tests. The APK packages `liblitertlm_jni.so` (arm64-v8a, x86_64) and
`libLiteRtDispatch_GoogleTensor.so` (arm64-v8a); its merged manifest declares the five optional
native libraries. The Rust core libraries were the existing prebuilt `jniLibs` from the Latitude
checkout (built 7 Nov from the current core), not rebuilt.

**Emulator (`emulator-5554`, x86_64, Android 13 image, no Tensor).** Emulator numbers are not
phone numbers. The emulator's clock is set to October 2023, so in-app HTTPS download failed with
the app's secure-connection error; the two Qwen files were downloaded on the ThinkPad (digests
matched the catalogue), pushed to Downloads and brought in with **Import verified file**, which
accepted them after its own size and SHA-256 check.

| Model / runtime / backend | Check | Prompt tokens | First token | Prefill | Decode |
| --- | --- | ---: | ---: | ---: | ---: |
| Qwen3 0.6B Q8 / llama.cpp / CPU | Short | 25 | 2.7 s | 9.3 tok/s | 7.8 tok/s |
| Qwen3 0.6B Q8 / llama.cpp / CPU | ~2K | 2,026 | 202.6 s | 10.0 tok/s | 1.2 tok/s (2 tokens) |
| Qwen3 0.6B / LiteRT-LM / CPU | Short | not measured | 6.5 s | not measured | 2.8 tok/s |
| Qwen3 0.6B / LiteRT-LM / CPU | ~2K | 2,008 | 88.9 s | 22.6 tok/s | 2.9 tok/s |

- LiteRT-LM GPU on the emulator started (its log: all 1,306 decode and 1,358 prefill nodes on the
  WebGPU delegate) but generation failed (`Can not find OpenCL library on this device`). The app
  showed the explicit error and stayed on GPU; nothing fell back.
- With a sparse placeholder file standing in for the Gemma 4 E2B G5 bundle (no real weights), **Use**
  showed "Tensor accelerator not available on this phone/OS: this device is x86_64, not ARM64" and
  did not crash. On every LiteRT start the emulator's LiteRT log reported that its NPU accelerator
  could not be loaded.
- LiteRT-LM's "last prefill" counters covered only the final 2-token prefill chunk, so Ballistic
  reports prompt size from the conversation's KV-cache count and prefill rate as prompt tokens over
  time to first token. For the short prompt the KV count (23) was below the decode count (32);
  that prompt size is shown as not measured.
- The emulator's backend selection was restored to Connected and the test copies were removed.

**Pixel 10 Pro (USB to the ThinkPad, GrapheneOS, Android 17, `ro.soc.model` Tensor G5).** Vendor
`libedgetpu_litert.so`, `libedgetpu_util.so` and `libedgetpu_client.google.so` exist under
`/vendor/lib64`; AICore is not installed. `adb install -r` of the ThinkPad-signed APK was refused
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, see README signing note); the same APK re-signed with the
Latitude debug key installed in place, keeping app data. Native libraries, including the Tensor
dispatch library, were extracted to the app's library directory. At startup the earlier starter
download moved to its catalogue slot without re-download, and the saved selection kept it active.
The phone stayed locked (keyguard showing) for the whole session, so no Local AI action, download,
NPU load or speed check could be run on it. **No NPU, GPU or phone LiteRT-LM result exists yet.**

**Pixel 10 Pro, later on 8 November 2026 (build fingerprint
`google/blazer/blazer:17/CP3A.261005.005/2026110700:user/release-keys`).** With the phone unlocked,
**Use** on Gemma 4 E2B · Tensor G5 (LiteRT-LM 0.19.1, dispatch 2.3.2) failed with the
`LiteRtDispatchQueryInterface` undefined-symbol lines quoted under "Tensor runtime pair"; LiteRT
never reached the vendor stack. That build is what changed to the 0.18.1 + 2.3.2 pair and the
failure classifier above.

**First NPU measurement, Pixel 10 Pro, 8 November 2026, 21:10–21:14 (phone time).** Build
`1d49dc4` (LiteRT-LM 0.18.1 + Google Tensor dispatch 2.3.2, Rust core rebuilt from current master),
built on the ThinkPad, re-signed with the Latitude debug key and installed in place (APK SHA-256
`374a1559a5a0574a4c56348f1ed52d879be357fb8eda2e60ea0f6780cab92aca`). Same build fingerprint as
above. The 3,206,299,658-byte Gemma 4 E2B · Tensor G5 bundle was already downloaded and verified.

*Did it run on the NPU?* Yes, per the runtime's own log (saved on the ThinkPad as
`~/ballistic_runs/npu_20261108/logcat_full.txt`):
- every main model subgraph (`prefill_128_cache_*`, `decode_cache_*`, `verify_cache_*`) logged
  "Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)", i.e. it went whole to the
  Google Tensor dispatch runtime;
- the vendor TPU driver logged activity on `/dev/edgetpu` (`Darwinn … Firmware Build Label:
  lga_tpu_20260603.0.0`) at each call;
- only small helper graphs (embedder, masks, rope; 2–14 nodes each) ran on CPU via XNNPack;
- one warning, "NPU accelerator could not be loaded and registered: kLiteRtStatusErrorNotFound",
  refers to LiteRT's generic NPU accelerator plugin; the dispatch path above ran regardless;
- the app's status card read "Running on: LiteRT-LM · NPU". AICore is not installed; it was not
  needed.

*Compiled context.* The bundle's signatures are `prefill_128_cache_{640,1024,1536,4096}`,
`decode_cache_{640,1024,1536,4096}` and `verify_cache_*`: the largest KV cache is **4,096 tokens**
(prompt plus generated output). Ballistic does not choose it; the engine picks the cache variant.

*Real planner calls.* The owner submitted requests from Ask while the model was loaded; the core
made three planner calls, each a full agent prompt (rules, schema, request, history, filtered
screen):

| Call | Prompt tokens (prefill) | KV tokens after | Prefill | First token | Decode | Output tokens | Whole call |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 2,441 | 2,477 | 2,970 tok/s | 0.87 s | 21 tok/s | 36 | 2.5 s |
| 2 | 2,449 | 2,495 | 3,181 tok/s | 0.82 s | 21 tok/s | 46 | 2.9 s |
| 3 | 2,525 | 2,574 | 2,890 tok/s | 0.92 s | 21 tok/s | 49 | 3.2 s |

Engine initialisation (model load) took 3.0 s. For comparison, the only earlier agent-length
numbers were emulator CPU: 202.6 s (llama.cpp) and 88.9 s (LiteRT-LM) to first token for ~2,000
tokens; the planning assumption in SPEC.md used ~430 tok/s prefill measured on a Pixel 8 GPU.

*What this does and does not establish.*
- Established: agent-length prefill on the Tensor G5 NPU is about 2.9–3.2K tokens/s, and a full
  planner call is about 3 s, on this phone and OS, for three calls.
- Headroom: the largest prompt used 2,574 of 4,096 KV tokens. The planner may generate up to 800
  tokens, so a prompt above about 3,300 tokens (long history, busy screen, reader facts, chat
  history) would not fit. Since this measurement core has a context guard that measures every
  prompt against the advertised 4,096 tokens and trims or refuses explicitly (see "Context limit
  for core" above); it is implemented and tested with fake ports and JVM tests, not yet on the
  phone.
- Not established: planning quality (whether the steps were correct), sustained performance over
  many calls (heat, throttling, battery), peak memory, and the app's own Short and ~2K speed checks.
- Reporting gap at the time: the app logged `prompt_tokens=0` for these calls; the prompt sizes
  above are LiteRT-LM's `last_prefill_tokens`, which on the NPU covered the whole prompt. Cause:
  the KV-derived size (KV tokens minus decoded tokens, e.g. 2,477 − 36 = 2,441) was accepted only
  if strictly larger than the last prefill, and on the NPU it was equal. Core therefore received
  zero prompt-token usage. Fixed since (`LiteRtPromptTokens`): the last prefill is used when it is
  at least the KV-derived size (whole-prompt prefill, as on the NPU), otherwise the KV-derived size
  (chunked CPU prefill), and 0 (not measured) only without a usable KV count; the last-prefill
  rate is then reported as the prompt rate. The count reaches core as `usage.prompt_tokens`, and
  core's step events now carry `prompt_tokens`/`completion_tokens`. The rule is unit-tested
  against the numbers above; the rebuilt app has not yet been run on the phone.

| Model | Backend (per runtime log) | Prompt tokens | First token | Prefill | Decode | Load | Peak memory |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Gemma 4 E2B · Tensor G5 | NPU (DispatchDelegate) | 2,441–2,525 | 0.82–0.92 s | 2,890–3,181 tok/s | 21 tok/s | 3.0 s | not measured |
| Gemma 4 E2B · LiteRT-LM | GPU: not measured | not measured | not measured | not measured | not measured | not measured | not measured |
| Gemma 4 E2B · LiteRT-LM | CPU: not measured | not measured | not measured | not measured | not measured | not measured | not measured |
| Qwen3 0.6B Q8 · llama.cpp | CPU: short check only (25 tokens, 0.4 s first token, 21.8 tok/s decode, 7 Nov) | | | | | | |

## Current capability boundary

### Access controls

Use **Settings → Phone permissions → Ballistic in phone settings** for Android/GrapheneOS
permissions. These govern the app's system access. Notification access is a separate Android
special-access grant; task notifications merely show progress and Stop. Local model inference
does not need notification access. GrapheneOS's Network toggle supplies an OS-enforced offline
boundary; model downloads and connected inference require network access.

Use **Settings → App access rules** for Ballistic's own restrictions within those system grants.
Unconfigured apps are blocked for reading and actions, with on-device-only routing. Search for
an app, expand its rules, choose notification reading and/or opening it, and **Save with
biometric**. Review the exact settings in the system strong-biometric prompt. Cancelling or
failing authentication leaves the saved rules unchanged. Finish active work before editing.
Explicit existing saved grants remain; missing fields now use the restrictive defaults.

Only notification reading and opening apps are offered in this build. Legacy tree/full rules
are labeled as legacy and provide no additional capabilities. Device-only notifications are
excluded entirely when a connected model is selected. Every-action approval applies to opening
an app too. Grants are checked again at dispatch, including after an approval. To try Open Clock,
first grant Clock **Actions: Open app**; notification reading can remain blocked.

Android's notification grant may deliver notifications broadly to Ballistic's process. Its
per-app filter limits what reaches the assistant; it is not OS-level isolation between sources.
The ordinary app cannot add its policy table to Android's permission manager. Sensitive-operation
approval remains a separate mechanism, and these changes do not establish a completed security audit.

The app can launch supported apps through Android intents. Its notification adapter cannot
observe another app's complete screen, and general tap/type/scroll control still needs a platform
adapter. Local model support and the Local AI UI do not complete that integration. Production
biometric approval also remains subject to physical-device validation.

## Building this slice

Use the existing Rust/UniFFI build script, plus Android SDK platform 37, NDK `29.0.14206865`
and CMake `3.31.6` for the embedded runtime. The Android build includes `arm64-v8a` and `x86_64`.

```bash
cd apps/assistant
scripts/build_core.sh
./gradlew :core-api:test :app:assembleDebug --max-workers=2
```

LiteRT-LM and the Google Tensor NPU runtime come from Google Maven (versions in
`gradle/libs.versions.toml`); the build needs network access to Google Maven the first time.

The native CMake configuration pins the llama.cpp source revision and archive digest. For an
offline build, `-PllamaArchive=/absolute/path/to/the/pinned/archive.tar.gz` supplies the same
hash-verified source archive. Never substitute an unverified archive or bundle test weights.

Optional host JNI tests require a host CMake build of `app/src/main/cpp` and
`BALLISTIC_LLAMA_LIBRARY_DIR` pointing at its library directory. For the real inference check,
also set `BALLISTIC_LLAMA_TEST_MODEL` to a compatible local test file and
`BALLISTIC_LLAMA_TEST_MODEL_SHA256` to its independently verified digest. Without these inputs,
optional native/model tests are skipped; a generic Gradle success is not proof of real inference.
