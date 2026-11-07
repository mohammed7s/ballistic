// The action log on disk: one JSON line per step core reports, appended, never rewritten
// (core/README.md §13, §15 "logs: core, app-private, append-only"). The shell writes what core
// emits and decides nothing from it. Plain java.io so it runs on Android and in JVM tests.

package app.ballistic.assistant.core

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One line: the step event plus which run and request it belongs to. */
@Serializable
data class LogLine(
    val run: String,
    @SerialName("time_millis") val timeMillis: Long,
    val request: String,
    val engine: String,
    val event: StepEvent,
)

class ActionLog(private val file: File) {
    @Synchronized
    fun append(line: LogLine) {
        file.parentFile?.mkdirs()
        file.appendText(RecordJson.encodeToString(LogLine.serializer(), line) + "\n")
    }

    /** Newest last. A line that does not parse is skipped and counted, not fatal. */
    @Synchronized
    fun read(limit: Int = 500): ReadResult {
        if (!file.exists()) return ReadResult(emptyList(), 0)
        var skipped = 0
        val lines = file.readLines().filter { it.isNotBlank() }.takeLast(limit).mapNotNull {
            try {
                RecordJson.decodeFromString(LogLine.serializer(), it)
            } catch (e: IllegalArgumentException) {
                skipped++
                null
            }
        }
        return ReadResult(lines, skipped)
    }

    data class ReadResult(val lines: List<LogLine>, val skipped: Int)
}
