package app.ballistic.assistant.models

import app.ballistic.assistant.core.*
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable enum class ModelBackend { Local, Http }
@Serializable data class ModelSelection(
    val backend: ModelBackend = ModelBackend.Local,
    val contextTokens: Int = 4096,
    val threads: Int = 2,
    /** Catalogue id or [DownloadableModel.IMPORTED_MODEL_ID]; null means the imported slot (older builds). */
    val activeModel: String? = null,
    /** Owner's explicit backend for an entry offering more than one; never set by a failure. */
    val backendChoice: ComputeBackend? = null,
    /**
     * Owner's opt-in: constrain the planner's output to core's action schema where the engine
     * supports it (llama.cpp grammar sampling). Off by default; other roles are never constrained.
     */
    val constrainPlanner: Boolean = false,
) {
    fun validated() = apply {
        require(contextTokens in 512..8192 && threads in 1..8) { "Invalid local engine settings" }
        require(activeModel == null || activeModel.matches(Regex("[a-z0-9-]{1,80}"))) { "Invalid model selection" }
    }
}

/** The backend a load uses: the owner's explicit choice if the entry offers it, else its default. */
fun DownloadableModel?.backendFor(selection: ModelSelection): ComputeBackend {
    val options = this?.backends ?: listOf(ComputeBackend.CPU)
    return selection.backendChoice?.takeIf { it in options } ?: options.first()
}

/** Separate file: old endpoint/role JSON and encrypted keys are never rewritten by migration. */
class ModelSelectionStore(private val file: File, private val hasHttp: () -> Boolean) {
    fun load(): ModelSelection = if (!file.exists()) {
        ModelSelection(backend = if (hasHttp()) ModelBackend.Http else ModelBackend.Local)
    } else try {
        CoreJson.decodeFromString(ModelSelection.serializer(), file.readText()).validated()
    } catch (_: Exception) { ModelSelection() } // Corrupt selection fails local, never sends to HTTP.

    fun initialize(): ModelSelection = load().also { if (!file.exists()) save(it) }

    fun save(value: ModelSelection) {
        value.validated()
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(CoreJson.encodeToString(ModelSelection.serializer(), value))
        check(tmp.renameTo(file)) { "Could not save model selection" }
    }
}

@Serializable data class PhoneProfile(
    val id: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val minMemoryGiB: Int = 0,
    val contextTokens: Int,
    val threads: Int,
    val suggestion: String,
)

fun selectPhoneProfile(profiles: List<PhoneProfile>, manufacturer: String, model: String, totalBytes: Long): PhoneProfile =
    profiles.firstOrNull { it.model?.equals(model, true) == true && it.manufacturer?.equals(manufacturer, true) == true }
        ?: profiles.filter { it.model == null && totalBytes >= it.minMemoryGiB * 1024L * 1024 * 1024 }
            .maxBy { it.minMemoryGiB }

@Serializable data class LocalMetadata(
    val description: String,
    val quantization: String? = null,
    val trainingContext: Int,
    val context: Int,
    /** What the runtime itself reported about where it runs, if anything (code-owned text). */
    val backendReport: String? = null,
)
@Serializable data class LocalMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptMs: Double,
    val generationMs: Double,
    val firstTokenMs: Double? = null,
    val durationMs: Double,
    val capped: Boolean = false,
    /** Rates the runtime measured itself (LiteRT-LM benchmark info); preferred when present. */
    val reportedPromptTokensPerSecond: Double? = null,
    val reportedGenerationTokensPerSecond: Double? = null,
) {
    val promptTokensPerSecond: Double? get() = reportedPromptTokensPerSecond?.takeIf { it > 0 } ?: rate(promptTokens, promptMs)
    // Generation time begins at first token; subsequent tokens each require one decode.
    val generationTokensPerSecond: Double? get() = reportedGenerationTokensPerSecond?.takeIf { it > 0 } ?: rate(generatedTokens - 1, generationMs)
    private fun rate(tokens: Int, ms: Double): Double? = if (tokens > 0 && ms > 0) tokens * 1000.0 / ms else null
}
@Serializable data class LocalReply(val text: String, val metrics: LocalMetrics)

/**
 * Prompt tokens of one LiteRT-LM call, from the counters LiteRT-LM 0.18.1 has: BenchmarkInfo's
 * `lastPrefillTokenCount` and the conversation's KV-cache count (`getTokenCount`) minus the
 * decoded tokens. The last prefill covers the whole prompt when it is at least the KV-derived
 * size (measured on the Tensor G5 NPU: 2,441 = 2,477 KV - 36 decoded); on CPU it was only the
 * final chunk (2 tokens of a 2,008-token prompt), and then the KV-derived size is the prompt.
 * Without a usable KV count nothing shows which case applies, so the size is 0 (not measured).
 */
object LiteRtPromptTokens {
    fun count(lastPrefillTokens: Int, kvTokens: Int?, decodeTokens: Int): Int {
        val fromKv = kvTokens?.let { it - decodeTokens }?.takeIf { it > 0 } ?: return 0
        return if (lastPrefillTokens >= fromKv) lastPrefillTokens else fromKv
    }

    /** True when the last prefill was the whole prompt, so its rate is the prompt's rate. */
    fun prefillCoversPrompt(lastPrefillTokens: Int, kvTokens: Int?, decodeTokens: Int): Boolean =
        lastPrefillTokens > 0 && count(lastPrefillTokens, kvTokens, decodeTokens) == lastPrefillTokens
}
enum class LocalPhase { Unloaded, Loading, Ready, Generating, Error }

/** Which weights to load, on which runtime and backend. Chosen by the owner, never by a failure. */
data class LoadTarget(
    val modelId: String,
    val modelName: String,
    val runtime: ModelRuntime = ModelRuntime.LlamaCpp,
    val backend: ComputeBackend = ComputeBackend.CPU,
    /** A precompiled bundle's fixed context ([DownloadableModel.contextTokens]), if any. */
    val contextTokens: Int? = null,
)
data class LoadSpec(val path: String, val context: Int, val threads: Int, val backend: ComputeBackend)

data class LocalState(
    val phase: LocalPhase = LocalPhase.Unloaded,
    val metadata: LocalMetadata? = null,
    val loadMs: Double? = null,
    val metrics: LocalMetrics? = null,
    val error: String? = null,
    /** The runtime line behind [error], if the driver supplied one (see [LocalFailure.detail]). */
    val errorDetail: String? = null,
    /** The loaded (or loading/failed) target. Backend is the one the engine was started on. */
    val target: LoadTarget? = null,
) { val busy: Boolean get() = phase == LocalPhase.Loading || phase == LocalPhase.Generating }

/** In-process engine. Methods except cancel require serialized ownership. */
interface LocalDriver {
    fun resetCancellation()
    fun cancel()
    fun load(path: String, context: Int, threads: Int): LocalMetadata
    /** Engines with more than one backend override this; the default is the CPU-only signature. */
    fun load(spec: LoadSpec): LocalMetadata {
        if (spec.backend != ComputeBackend.CPU) throw LocalFailure("This runtime supports CPU only")
        return load(spec.path, spec.context, spec.threads)
    }
    fun unload()
    fun generate(messages: List<ChatMessage>, maxTokens: Int): LocalReply
    /** True if [generate] with a JSON Schema constrains decoding to it (llama.cpp grammar sampling). */
    val supportsJsonSchema: Boolean get() = false
    /** [jsonSchema] null: the plain call. Only engines with [supportsJsonSchema] accept a schema. */
    fun generate(messages: List<ChatMessage>, maxTokens: Int, jsonSchema: String?): LocalReply =
        if (jsonSchema == null) generate(messages, maxTokens) else throw LocalFailure("This runtime cannot constrain output to a schema")
}

/**
 * One owner for weights/context, shared by all roles, with one engine per runtime. Only one model
 * is loaded at a time: loading any target first unloads every engine. A failed load leaves
 * nothing loaded; there is no network, retry, other-backend or previous-model fallback path.
 */
class LocalModels(
    private val drivers: Map<ModelRuntime, LocalDriver>,
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * The JSON Schema a role's output is constrained to, or null (the default for every role).
     * Applied only by an engine with [LocalDriver.supportsJsonSchema]; others run unconstrained.
     */
    private val outputSchema: (ModelRole) -> String? = { null },
) : Models {
    constructor(driver: LocalDriver, nanoTime: () -> Long = System::nanoTime) : this(mapOf(ModelRuntime.LlamaCpp to driver), nanoTime)

    private val ownership = ReentrantLock()
    private val control = Any()
    private val mutable = MutableStateFlow(LocalState())
    val state = mutable.asStateFlow()
    @Volatile private var active: LocalDriver? = null

    private inline fun <T> owned(block: () -> T): T {
        if (!ownership.tryLock()) throw PortException.Host("Local engine is busy")
        try { return block() } finally { ownership.unlock() }
    }
    private fun prepare(driver: LocalDriver, cancelled: () -> Boolean = { false }) = synchronized(control) {
        if (cancelled()) throw PortException.Host("Local inference stopped")
        driver.resetCancellation()
    }
    fun cancel() = synchronized(control) { drivers.values.forEach { it.cancel() } }
    private fun unloadAll() { active = null; drivers.values.forEach { it.unload() } }

    fun load(file: File, selection: ModelSelection, cancelled: () -> Boolean = { false }) =
        load(file, selection, LoadTarget(DownloadableModel.IMPORTED_MODEL_ID, file.name), cancelled)

    fun load(file: File, selection: ModelSelection, target: LoadTarget, cancelled: () -> Boolean = { false }) = owned {
        selection.validated()
        mutable.value = LocalState(phase = LocalPhase.Loading, target = target)
        val start = nanoTime()
        try {
            val driver = drivers[target.runtime] ?: throw LocalFailure("${target.runtime.label} is not available in this build")
            prepare(driver, cancelled)
            unloadAll()
            val loaded = driver.load(LoadSpec(file.absolutePath, selection.contextTokens, selection.threads, target.backend))
            // A compiled bundle's context is what it holds; otherwise what the runtime was started with.
            val metadata = target.contextTokens?.let { loaded.copy(context = it) } ?: loaded
            active = driver
            mutable.value = LocalState(LocalPhase.Ready, metadata, (nanoTime() - start) / 1e6, target = target)
        } catch (e: Exception) {
            unloadAll()
            mutable.value = LocalState(phase = LocalPhase.Error, error = safeError(e), errorDetail = (e as? LocalFailure)?.detail, target = target)
            throw PortException.Host(safeError(e))
        }
    }
    fun unload() = owned {
        unloadAll()
        mutable.value = LocalState()
    }
    override fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion =
        complete(messages, maxTokens, role) { false }

    /** The loaded model's context for core's guard: every role shares the one loaded model. */
    override fun limits(role: ModelRole): ModelLimits = ModelLimits(contextLimit())
    fun contextLimit(): Int? = state.value.metadata?.context?.takeIf { it > 0 }

    /** True if [role]'s next call on the loaded engine will be schema-constrained. */
    fun constrains(role: ModelRole): Boolean = active?.supportsJsonSchema == true && outputSchema(role) != null

    fun complete(messages: List<ChatMessage>, maxTokens: Int, cancelled: () -> Boolean): Completion =
        complete(messages, maxTokens, null, cancelled)

    /** [role] null: a speed check, never constrained. */
    fun complete(messages: List<ChatMessage>, maxTokens: Int, role: ModelRole?, cancelled: () -> Boolean): Completion = owned {
        val driver = active
        if (mutable.value.metadata == null || driver == null) throw PortException.Host("Local model is not loaded. Open Local AI.")
        require(maxTokens > 0)
        prepare(driver, cancelled)
        mutable.value = mutable.value.copy(phase = LocalPhase.Generating, metrics = null, error = null)
        try {
            val schema = role?.let(outputSchema)?.takeIf { driver.supportsJsonSchema }
            val reply = driver.generate(messages, maxTokens.coerceAtMost(1024), schema)
            if (cancelled()) throw PortException.Host("Local inference stopped")
            mutable.value = mutable.value.copy(phase = LocalPhase.Ready, metrics = reply.metrics)
            Completion(reply.text, Usage(reply.metrics.promptTokens, reply.metrics.generatedTokens))
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(phase = LocalPhase.Ready, error = safeError(e))
            throw PortException.Host(safeError(e))
        }
    }
    private fun safeError(e: Exception): String = if (e is LocalFailure) e.message!! else "Local engine failed; unload and retry"
}

/**
 * Only code-owned error strings cross JNI. No model/prompt/path exception excerpts. [detail] is at
 * most one runtime log line with paths reduced to file names, shown as status-card details only.
 */
class LocalFailure(message: String, val detail: String? = null) : RuntimeException(message)

/** Frozen for a whole Ask run, including all roles; Stop remains latched between callbacks. */
interface ModelRunLifecycle {
    fun beginRun()
    fun endRun()
    fun cancelRun()
}
class RoutedModels(
    private val selection: () -> ModelBackend,
    private val http: Models,
    private val local: LocalModels,
) : Models, ModelRunLifecycle {
    @Volatile private var active: ModelBackend? = null
    @Volatile private var stopped = false
    @Synchronized override fun beginRun() {
        check(active == null) { "A model run is already active" }
        stopped = false
        active = selection()
    }
    @Synchronized override fun endRun() { active = null }
    override fun cancelRun() { stopped = true; local.cancel() }
    override fun limits(role: ModelRole): ModelLimits = when (active ?: throw PortException.Host("No active model run")) {
        ModelBackend.Local -> local.limits(role)
        ModelBackend.Http -> http.limits(role)
    }
    override fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion {
        if (stopped) throw PortException.Host("Model run stopped")
        return when (active ?: throw PortException.Host("No active model run")) {
            ModelBackend.Local -> local.complete(messages, maxTokens, role) { stopped }
            ModelBackend.Http -> http.complete(role, messages, maxTokens)
        }
    }
}
