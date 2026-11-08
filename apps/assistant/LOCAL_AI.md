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

Full digests and revisions are in the JSON file. The two Tensor G5 entries are the bundles that
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
per the LiteRT-LM 0.19.1 source, the engine builds the executor for the requested backend or
fails, so a successful start on NPU is the runtime's own acceptance of that backend, not a
separate hardware trace. Logcat also carries LiteRT-LM's native log lines.

**Tensor NPU support.** The APK includes `com.google.ai.edge.litertlm:litertlm-android:0.19.1`
(arm64-v8a and x86_64) and the official `com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.2`,
which packages `libLiteRtDispatch_GoogleTensor.so` (arm64-v8a only) and declares the optional
vendor `libedgetpu_litert.so`/`libedgetpu_util.so`. The manifest also declares
`libedgetpu_client.google.so`, `libOpenCL.so` and `libvndksupport.so` as optional, as Google's TPU
sample and LiteRT-LM guide do. Native libraries are extracted at install (legacy packaging) so the
dispatch library is in `nativeLibraryDir`, where LiteRT-LM loads it; this makes the installed app
larger. Before an NPU start Ballistic checks the ABI is arm64-v8a, the SoC manufacturer reports
Google and the dispatch library is present; otherwise it shows "Tensor accelerator not available on
this phone/OS" with the reason. A failed NPU start shows the same message. A native crash inside the
vendor runtime cannot be caught by the app; none has been observed because none has been run.
Compatibility of this LiteRT-LM release with this dispatch runtime and with GrapheneOS's vendor
libraries is established only by running it on the phone.

**Context.** For llama.cpp and generic LiteRT-LM bundles the Context setting (2K/4K/8K) is passed
to the engine. A compiled NPU bundle fixes its context; Ballistic does not pass a size. The Gemma 3
1B G5 bundle's name records a 1,280-token KV cache, so the ~2K prefill check cannot fit there.

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
