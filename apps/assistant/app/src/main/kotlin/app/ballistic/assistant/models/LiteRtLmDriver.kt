// LiteRT-LM engine behind the same LocalDriver/Models port as llama.cpp.
//
// Can: load a `.litertlm` bundle on the CPU, GPU or NPU backend the owner chose, run one fresh
// Conversation per call (no context carried between calls or roles), cancel an active call, and
// report LiteRT-LM's own benchmark numbers (time to first token, prefill and decode rates).
// Cannot: pick or change a backend by itself. A failed start is an explicit error; nothing falls
// back to another backend, another model or a connected model. Per the LiteRT-LM 0.19.1 source,
// the engine creates the executor for the requested backend or fails; it does not switch backends.
//
// NPU (Google Tensor): the official `litert-npu-runtime-google-tensor` AAR packages
// libLiteRtDispatch_GoogleTensor.so (arm64 only) and declares the vendor libedgetpu libraries.
// The dispatch library is loaded from nativeLibraryDir, so jniLibs use legacy (extracted)
// packaging. A G5 bundle is compiled for the Tensor G5 NPU and is not expected to run elsewhere.

package app.ballistic.assistant.models

import android.content.Context
import android.os.Build
import android.util.Log
import app.ballistic.assistant.core.ChatMessage
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Facts about Tensor NPU support on this device, for the precheck and the technical details. */
object TensorSupport {
    const val DISPATCH_LIBRARY = "libLiteRtDispatch_GoogleTensor.so"
    private val VENDOR_LIBRARIES = listOf("/vendor/lib64/libedgetpu_litert.so", "/vendor/lib64/libedgetpu_util.so")

    val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    val soc: String get() = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
    fun dispatchPackaged(context: Context) = File(context.applicationInfo.nativeLibraryDir, DISPATCH_LIBRARY).isFile
    /** Visibility only: SELinux may hide a present file, so absence is reported, not enforced. */
    fun vendorLibrariesVisible(): Boolean = VENDOR_LIBRARIES.any { runCatching { File(it).exists() }.getOrDefault(false) }

    /** Null when an NPU start may be attempted; otherwise the reason it cannot be. */
    fun unavailableReason(context: Context): String? = when {
        abi != "arm64-v8a" -> "Tensor accelerator not available on this phone/OS: this device is $abi, not ARM64 (for example an emulator)."
        !Build.SOC_MANUFACTURER.equals("Google", ignoreCase = true) -> "Tensor accelerator not available on this phone/OS: the SoC reports \"$soc\", not Google Tensor."
        !dispatchPackaged(context) -> "Tensor accelerator not available on this phone/OS: the Google Tensor dispatch library is not installed with this app."
        else -> null
    }

    fun describe(context: Context): String =
        "ABI $abi · SoC $soc · dispatch library ${if (dispatchPackaged(context)) "packaged" else "absent"} · " +
            "vendor edgetpu libraries ${if (vendorLibrariesVisible()) "visible" else "not visible to the app"}"
}

class LiteRtLmDriver(private val context: Context) : LocalDriver {
    private var engine: Engine? = null
    private var backend: ComputeBackend? = null
    private var context0 = 0
    @Volatile private var conversation: Conversation? = null
    @Volatile private var cancelled = false

    override fun resetCancellation() { cancelled = false }
    override fun cancel() {
        cancelled = true
        runCatching { conversation?.cancelProcess() }
    }

    override fun load(path: String, context: Int, threads: Int): LocalMetadata =
        load(LoadSpec(path, context, threads, ComputeBackend.CPU))

    @OptIn(ExperimentalApi::class)
    override fun load(spec: LoadSpec): LocalMetadata {
        unload()
        if (spec.backend == ComputeBackend.NPU) TensorSupport.unavailableReason(context)?.let { throw LocalFailure(it) }
        val runtimeBackend = when (spec.backend) {
            ComputeBackend.CPU -> Backend.CPU(threadCount = spec.threads)
            ComputeBackend.GPU -> Backend.GPU()
            ComputeBackend.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        }
        // A compiled NPU bundle fixes its context at compile time; asking for another size fails.
        val maxTokens = if (spec.backend == ComputeBackend.NPU) null else spec.context
        val config = EngineConfig(
            modelPath = spec.path,
            backend = runtimeBackend,
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = maxTokens,
            cacheDir = context.cacheDir.resolve("litertlm").apply { mkdirs() }.path,
        )
        ExperimentalFlags.enableBenchmark = true
        val candidate = try { Engine(config) } catch (t: Throwable) { throw startFailure(spec.backend, t) }
        try {
            candidate.initialize()
        } catch (t: Throwable) {
            runCatching { if (candidate.isInitialized()) candidate.close() }
            throw startFailure(spec.backend, t)
        }
        engine = candidate
        backend = spec.backend
        context0 = maxTokens ?: 0
        Log.i(TAG, "LiteRT-LM engine initialized backend=${runtimeBackend.name}")
        return LocalMetadata(
            description = "LiteRT-LM bundle",
            trainingContext = 0,
            context = context0,
            backendReport = "LiteRT-LM engine started on ${runtimeBackend.name}. It was asked for this backend only and did not report a failure.",
        )
    }

    private fun startFailure(backend: ComputeBackend, t: Throwable): LocalFailure {
        // Class name only: native messages can contain paths. Logcat also has LiteRT's own log.
        Log.w(TAG, "LiteRT-LM start failed backend=$backend error=${t.javaClass.simpleName}")
        val nothingElse = "Nothing was switched to another backend or a connected model."
        return LocalFailure(when (backend) {
            ComputeBackend.NPU -> "Tensor accelerator not available on this phone/OS: LiteRT-LM could not start its NPU backend with this bundle. $nothingElse"
            ComputeBackend.GPU -> "LiteRT-LM could not start its GPU backend on this phone with this bundle. $nothingElse"
            ComputeBackend.CPU -> "LiteRT-LM could not load this bundle on CPU (memory or an unsupported bundle). $nothingElse"
        })
    }

    override fun unload() {
        runCatching { conversation?.close() }
        conversation = null
        engine?.let { runCatching { if (it.isInitialized()) it.close() } }
        engine = null
        backend = null
    }

    @OptIn(ExperimentalApi::class)
    override fun generate(messages: List<ChatMessage>, maxTokens: Int): LocalReply {
        val loaded = engine ?: throw LocalFailure("Local model is not loaded. Open Local AI.")
        val last = messages.lastOrNull()?.takeIf { it.role == "user" }
            ?: throw LocalFailure("The local request has no final user message")
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val history = messages.dropLast(1).filter { it.role != "system" }.map {
            if (it.role == "assistant") Message.model(it.content) else Message.user(it.content)
        }
        if (cancelled) throw LocalFailure("Local inference stopped")
        val started = System.nanoTime()
        // A fresh conversation per call: nothing from a previous call or role stays in the KV cache.
        val call = try {
            loaded.createConversation(ConversationConfig(
                systemInstruction = system.takeIf { it.isNotEmpty() }?.let { Contents.of(it) },
                initialMessages = history,
                maxOutputToken = maxTokens,
            ))
        } catch (t: Throwable) {
            Log.w(TAG, "LiteRT-LM conversation failed error=${t.javaClass.simpleName}")
            throw LocalFailure("LiteRT-LM could not start the request (the prompt may exceed the bundle's context)")
        }
        conversation = call
        try {
            val text = StringBuilder()
            var firstAt: Long? = null
            var failure: Throwable? = null
            val done = CountDownLatch(1)
            call.sendMessageAsync(Message.user(last.content), object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (firstAt == null) firstAt = System.nanoTime()
                    message.contents.contents.forEach { if (it is Content.Text) text.append(it.text) }
                }
                override fun onDone() { done.countDown() }
                override fun onError(throwable: Throwable) { failure = throwable; done.countDown() }
            })
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelled) runCatching { call.cancelProcess() }
            }
            if (cancelled) throw LocalFailure("Local inference stopped")
            failure?.let {
                Log.w(TAG, "LiteRT-LM generation failed error=${it.javaClass.simpleName}")
                throw LocalFailure("LiteRT-LM generation failed (the prompt may exceed the bundle's context)")
            }
            val ended = System.nanoTime()
            val info = runCatching { call.getBenchmarkInfo() }.getOrNull()
            val prefillTokens = info?.lastPrefillTokenCount ?: 0
            val decodeTokens = info?.lastDecodeTokenCount ?: 0
            val prefillRate = info?.lastPrefillTokensPerSecond?.takeIf { it > 0 }
            val decodeRate = info?.lastDecodeTokensPerSecond?.takeIf { it > 0 }
            val wallFirst = firstAt?.let { (it - started) / 1e6 }
            val metrics = LocalMetrics(
                promptTokens = prefillTokens,
                generatedTokens = decodeTokens,
                promptMs = if (prefillRate != null) prefillTokens * 1000.0 / prefillRate else 0.0,
                generationMs = if (decodeRate != null) decodeTokens * 1000.0 / decodeRate else 0.0,
                firstTokenMs = info?.timeToFirstTokenInSecond?.takeIf { it > 0 }?.let { it * 1000 } ?: wallFirst,
                durationMs = (ended - started) / 1e6,
                capped = decodeTokens >= maxTokens,
                reportedPromptTokensPerSecond = prefillRate,
                reportedGenerationTokensPerSecond = decodeRate,
            )
            Log.i(TAG, "LiteRT-LM call backend=$backend prefill_tokens=$prefillTokens decode_tokens=$decodeTokens " +
                "ttft_ms=${metrics.firstTokenMs?.toInt()} prefill_tps=${prefillRate?.toInt()} decode_tps=${decodeRate?.toInt()} max_tokens=${info?.maxNumTokens}")
            return LocalReply(text.toString(), metrics)
        } finally {
            conversation = null
            runCatching { call.close() }
        }
    }

    companion object { const val TAG = "Ballistic" }
}
