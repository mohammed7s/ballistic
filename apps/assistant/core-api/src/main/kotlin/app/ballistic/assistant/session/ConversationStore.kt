// The conversation on disk: exactly the turns core returned in TurnResult.history, newest last,
// capped to the window core reads (CoreOptions.chatHistoryTurns, 12). The app never writes a turn
// of its own; core re-checks every turn when it comes back (core/README.md §7 "Chat"). Writes go
// to a temporary file renamed over the old one. A file that does not parse reads as empty.
// App-private and excluded from backup like the policy and the log.

package app.ballistic.assistant.session

import app.ballistic.assistant.core.ChatTurn
import app.ballistic.assistant.core.RecordJson
import java.io.File
import kotlinx.serialization.builtins.ListSerializer

class ConversationStore(private val file: File, private val keep: Int = 12) {
    private val serializer = ListSerializer(ChatTurn.serializer())

    @Synchronized
    fun load(): List<ChatTurn> = try {
        if (file.exists()) RecordJson.decodeFromString(serializer, file.readText()).takeLast(keep) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Appends what core returned and returns the stored window. */
    @Synchronized
    fun append(turns: List<ChatTurn>): List<ChatTurn> {
        val next = (load() + turns).takeLast(keep)
        if (turns.isNotEmpty()) write(next)
        return next
    }

    @Synchronized
    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun write(turns: List<ChatTurn>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(RecordJson.encodeToString(serializer, turns))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not replace ${file.name}")
        }
    }
}
