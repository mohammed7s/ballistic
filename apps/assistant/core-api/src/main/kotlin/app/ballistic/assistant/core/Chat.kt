// Conversation turns: the Kotlin side of core/src/chat.rs and of core/src/run.rs `Options`.
// The shell keeps the conversation and passes it back on every turn; core caps and checks it,
// makes the one chat model call, and returns what to show plus the turns to append. The shell
// appends exactly [TurnResult.history] and decides nothing from any of it.

package app.ballistic.assistant.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Who a turn came from. Only [Owner] turns are requests. */
@Serializable
enum class TurnSource {
    @SerialName("owner") Owner,
    @SerialName("assistant") Assistant,
    @SerialName("task_result") TaskResult,
}

/**
 * One stored turn. [values] are the values in [text] that came from app content, each with its
 * source; core writes them and checks them again when the turn comes back.
 */
@Serializable
data class ChatTurn(
    val source: TurnSource,
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val values: List<Tagged> = emptyList(),
)

@Serializable
enum class TurnKind {
    /** The model answered; nothing happened on the phone. */
    @SerialName("reply") Reply,
    /** The model asked the owner a question; nothing happened on the phone. */
    @SerialName("clarify") Clarify,
    /** The request ran through core's agent loop; [TurnResult.report] is its report. */
    @SerialName("task") Task,
    /** The model's reply was not one of the shapes; nothing happened. */
    @SerialName("not_understood") NotUnderstood,
    /** Stop arrived before the model was asked. */
    @SerialName("stopped") Stopped,
}

/**
 * What one turn produced. [text] is the thread line, in the first person; [values] are the values
 * in it the owner did not say, with their sources (show them with the text). [report] is the
 * run report of a task turn, kept as JSON because it is shown, never executed; decode it with
 * [RunResult] for the status fields.
 */
@Serializable
data class TurnResult(
    val kind: TurnKind,
    val text: String,
    val values: List<Tagged> = emptyList(),
    val request: String? = null,
    val report: JsonElement? = null,
    val error: String? = null,
    val history: List<ChatTurn> = emptyList(),
)

/**
 * Per-call settings (core/src/run.rs `Options`). Defaults are the app's: the deny-only judge on.
 * The harness runs with the judge off so benchmark results stay comparable.
 */
@Serializable
data class CoreOptions(
    @SerialName("max_steps") val maxSteps: Int = 15,
    val judge: Boolean = true,
    @SerialName("chat_history_turns") val chatHistoryTurns: Int = 12,
)
