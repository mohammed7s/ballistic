// Plain speed words for Local AI's main page, from the realistic (~2K-token) speed check, and
// the last result per model kept across app restarts. Exact token counts and rates stay on the
// Advanced page; these lines are estimates and say so where they are explained.

package app.ballistic.assistant.models

import app.ballistic.assistant.core.CoreJson
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** The last ~2K check of one model on one backend. */
@Serializable data class SpeedRecord(
    val modelId: String,
    val backend: ComputeBackend,
    val promptTokens: Int,
    val firstTokenMs: Double,
    val decodeTokensPerSecond: Double,
    val measuredAt: Long,
)

object SpeedWords {
    /** Output tokens a planner step typically emits (one JSON action with a short why). */
    const val STEP_OUTPUT_TOKENS = 60
    /** English text averages about 0.75 words per token for these tokenizers. */
    const val WORDS_PER_TOKEN = 0.75

    fun record(result: SpeedResult, now: Long): SpeedRecord? {
        if (result.check != SpeedCheck.LongPrefill) return null
        val first = result.metrics.firstTokenMs ?: return null
        val decode = result.metrics.generationTokensPerSecond ?: return null
        return SpeedRecord(result.modelId, result.backend, result.metrics.promptTokens, first, decode, now)
    }

    /** Seconds for one agent step: first word after a ~2K prompt plus [STEP_OUTPUT_TOKENS]. */
    fun stepSeconds(r: SpeedRecord): Double = r.firstTokenMs / 1000 + STEP_OUTPUT_TOKENS / r.decodeTokensPerSecond

    /** "About 3 s per step". */
    fun stepLine(r: SpeedRecord?): String? = r?.let {
        val s = stepSeconds(it)
        if (s < 10) "About ${String.format(Locale.ROOT, "%.0f", s.coerceAtLeast(1.0))} s per step"
        else "About ${Math.round(s / 5) * 5} s per step"
    }

    /** "first word 0.9 s · 16 words/s". */
    fun testLine(r: SpeedRecord): String =
        "first word ${String.format(Locale.ROOT, "%.1f", r.firstTokenMs / 1000)} s · " +
            "${String.format(Locale.ROOT, "%.0f", r.decodeTokensPerSecond * WORDS_PER_TOKEN)} words/s"
}

/** One file, newest record per model and backend. Corrupt or missing: no records. */
class SpeedRecordStore(private val file: File) {
    fun load(): List<SpeedRecord> = runCatching {
        CoreJson.decodeFromString(ListSerializer(SpeedRecord.serializer()), file.readText())
    }.getOrDefault(emptyList())

    fun latest(modelId: String, backend: ComputeBackend?): SpeedRecord? =
        load().filter { it.modelId == modelId && (backend == null || it.backend == backend) }.maxByOrNull { it.measuredAt }

    fun save(record: SpeedRecord) {
        val next = load().filterNot { it.modelId == record.modelId && it.backend == record.backend } + record
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(CoreJson.encodeToString(ListSerializer(SpeedRecord.serializer()), next.takeLast(40)))
        check(tmp.renameTo(file)) { "Could not save speed record" }
    }
}
