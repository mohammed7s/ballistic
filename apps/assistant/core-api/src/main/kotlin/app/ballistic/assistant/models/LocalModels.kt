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
) {
    fun validated() = apply {
        require(contextTokens in 512..8192 && threads in 1..8) { "Invalid local engine settings" }
    }
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
)
@Serializable data class LocalMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptMs: Double,
    val generationMs: Double,
    val firstTokenMs: Double? = null,
    val durationMs: Double,
    val capped: Boolean = false,
) {
    val promptTokensPerSecond: Double? get() = rate(promptTokens, promptMs)
    // Generation time begins at first token; subsequent tokens each require one decode.
    val generationTokensPerSecond: Double? get() = rate(generatedTokens - 1, generationMs)
    private fun rate(tokens: Int, ms: Double): Double? = if (tokens > 0 && ms > 0) tokens * 1000.0 / ms else null
}
@Serializable data class LocalReply(val text: String, val metrics: LocalMetrics)
enum class LocalPhase { Unloaded, Loading, Ready, Generating, Error }
data class LocalState(
    val phase: LocalPhase = LocalPhase.Unloaded,
    val metadata: LocalMetadata? = null,
    val loadMs: Double? = null,
    val metrics: LocalMetrics? = null,
    val error: String? = null,
) { val busy: Boolean get() = phase == LocalPhase.Loading || phase == LocalPhase.Generating }

/** JNI implementation uses an in-process CPU engine. Methods except cancel require serialized ownership. */
interface LocalDriver {
    fun resetCancellation()
    fun cancel()
    fun load(path: String, context: Int, threads: Int): LocalMetadata
    fun unload()
    fun generate(messages: List<ChatMessage>, maxTokens: Int): LocalReply
}

/** One owner for weights/context, shared by all roles. No network or retry/fallback path. */
class LocalModels(private val driver: LocalDriver, private val nanoTime: () -> Long = System::nanoTime) : Models {
    private val ownership = ReentrantLock()
    private val control = Any()
    private val mutable = MutableStateFlow(LocalState())
    val state = mutable.asStateFlow()

    private inline fun <T> owned(block: () -> T): T {
        if (!ownership.tryLock()) throw PortException.Host("Local engine is busy")
        try { return block() } finally { ownership.unlock() }
    }
    private fun prepare(cancelled: () -> Boolean = { false }) = synchronized(control) {
        if (cancelled()) throw PortException.Host("Local inference stopped")
        driver.resetCancellation()
    }
    fun cancel() = synchronized(control) { driver.cancel() }

    fun load(file: File, selection: ModelSelection, cancelled: () -> Boolean = { false }) = owned {
        selection.validated()
        mutable.value = LocalState(phase = LocalPhase.Loading)
        val start = nanoTime()
        try {
            prepare(cancelled)
            driver.unload()
            val metadata = driver.load(file.absolutePath, selection.contextTokens, selection.threads)
            mutable.value = LocalState(LocalPhase.Ready, metadata, (nanoTime() - start) / 1e6)
        } catch (e: Exception) {
            driver.unload()
            mutable.value = LocalState(phase = LocalPhase.Error, error = safeError(e))
            throw PortException.Host(safeError(e))
        }
    }
    fun unload() = owned {
        driver.unload()
        mutable.value = LocalState()
    }
    override fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion =
        complete(messages, maxTokens) { false }

    fun complete(messages: List<ChatMessage>, maxTokens: Int, cancelled: () -> Boolean): Completion = owned {
        if (mutable.value.metadata == null) throw PortException.Host("Local model is not loaded. Open Local AI.")
        require(maxTokens > 0)
        prepare(cancelled)
        mutable.value = mutable.value.copy(phase = LocalPhase.Generating, metrics = null, error = null)
        try {
            val reply = driver.generate(messages, maxTokens.coerceAtMost(1024))
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

/** Only code-owned error strings cross JNI. No model/prompt/path exception excerpts. */
class LocalFailure(message: String) : RuntimeException(message)

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
    override fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion {
        if (stopped) throw PortException.Host("Model run stopped")
        return when (active ?: throw PortException.Host("No active model run")) {
            ModelBackend.Local -> local.complete(messages, maxTokens) { stopped }
            ModelBackend.Http -> http.complete(role, messages, maxTokens)
        }
    }
}
