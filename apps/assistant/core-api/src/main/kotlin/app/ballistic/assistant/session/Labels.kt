// Words the UI uses for results and actions. Kept beside the session so the rules are tested:
// an acknowledged input is never shown as "sent" or "done" (docs/ANDROID_APP.md, Result).

package app.ballistic.assistant.session

import app.ballistic.assistant.core.ExecutionStatus
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.TaskStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun resultLabel(result: RunResult): String = when {
    result.stopped -> if (result.executionStatus == ExecutionStatus.NotExecuted) "Stopped; nothing was done" else "Stopped; earlier steps may have taken effect"
    result.executionStatus == ExecutionStatus.Unknown -> "Unresolved: an action may or may not have taken effect"
    result.taskStatus == TaskStatus.Completed -> "Completed"
    result.taskStatus == TaskStatus.OpenedUi -> "Opened the app; the task itself is not confirmed"
    result.taskStatus == TaskStatus.Pending -> "Unresolved: input was accepted, the effect is not confirmed"
    result.taskStatus == TaskStatus.Unsupported -> "Not supported yet"
    else -> "Failed"
}

/** What the completion rests on, from core's verification record. */
fun evidenceLabel(result: RunResult): String = when (result.verification.evidenceBasis) {
    "none" -> "no evidence"
    "model_assessment" -> "a model's reading of the screen"
    "visible_ui" -> "a check of the visible screen"
    else -> result.verification.evidenceBasis
}

/** One line for an action in core's log form. [redact] hides typed text and questions. */
fun actionLabel(action: JsonElement, redact: Boolean): String {
    val obj = action as? JsonObject ?: return action.toString()
    fun field(name: String) = obj[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    fun shown(name: String) = field(name)?.let { if (redact) "•••" else "\"$it\"" }
    return when (val kind = field("action")) {
        "open_app" -> "open ${field("app")}"
        "tap" -> "tap ${field("target")}"
        "long_press" -> "long press ${field("target")}"
        "type" -> "type ${shown("text")} into ${field("target")}" + if (field("submit") == "true") " and submit" else ""
        "scroll" -> "scroll ${field("direction")}" + (field("target")?.let { " in $it" } ?: "")
        "swipe" -> "swipe ${field("direction")} on ${field("target")}"
        "read" -> "read ${shown("question")}"
        "answer" -> "answer ${shown("text")}"
        "done" -> "done: ${shown("evidence")}"
        "give_up" -> "give up: ${field("reason")}"
        null -> obj.toString()
        else -> kind
    }
}
