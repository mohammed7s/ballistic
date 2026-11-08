// Words the UI uses for results and actions. Kept beside the session so the rules are tested:
// an acknowledged input is never shown as "sent" or "done" (docs/ANDROID_APP.md, Result).

package app.ballistic.assistant.session

import app.ballistic.assistant.core.ExecutionStatus
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.TaskStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun resultLabel(result: RunResult): String = when {
    result.stopped -> if (result.executionStatus == ExecutionStatus.NotExecuted) "Stopped; nothing was done" else "Stopped; earlier steps may have taken effect"
    result.executionStatus == ExecutionStatus.Unknown -> "Unresolved: an action may or may not have taken effect"
    result.verification.evidenceBasis == "model_selected_source_excerpt" -> "Source excerpts available; task completion is not confirmed"
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
    "model_selected_source_excerpt" -> "model-selected quotations; truth and coverage are unverified"
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

/**
 * The collapsed run card's second line: what is happening now, in plain words. Built only from
 * known lifecycle strings, step metadata and result labels; anything unrecognised becomes a
 * generic line, so raw model output, error text or JSON never reaches it. [awaitingApproval] is
 * the session's phase for the active run.
 */
fun runStatusLine(run: RunView, awaitingApproval: Boolean = false): String {
    if (run.finishedElapsedMs != null || run.result != null || run.error != null) return when {
        run.result != null -> resultLabel(run.result)
        run.stopRequested -> "Stopped; earlier actions remain"
        run.error != null -> "Ended with an error"
        else -> "Run ended"
    }
    if (awaitingApproval) return "Waiting for your approval"
    if (run.stopRequested) return "Stopping…"
    val latest = run.progress.lastOrNull()?.text ?: return "Starting…"
    val step = run.steps.lastOrNull()
    if (step != null && latest.startsWith("Step ${step.step}:")) return stepSummary(step)
    return when {
        latest == "Starting request" -> "Starting…"
        latest == "observing" || latest == "Reading available screen information" -> "Reading the screen…"
        latest == "acting" || latest == "Dispatching an action" -> "Carrying out a step…"
        latest == "awaiting approval" -> "Waiting for your approval"
        latest.startsWith("Waiting for ") && latest.endsWith(" model") -> "Waiting for the model…"
        latest.endsWith(" reply received; checking response") -> "Checking the model's reply…"
        latest == "unreadable step event from core" -> "Received a step it could not read"
        else -> "Working…"
    }
}

/**
 * One plain line for a step: what was done and whether it took effect. Only the app name is
 * shown (bounded); targets, typed text, questions and reasons are not.
 */
fun stepSummary(event: StepEvent): String {
    val obj = event.action as? JsonObject
    fun field(name: String) = obj?.get(name)
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        ?.replace(Regex("\\s+"), " ")?.trim()?.take(40)?.ifBlank { null }
    val app = field("app") ?: "an app"
    val direction = when (val d = field("direction")) {
        "up", "down", "left", "right" -> " $d"
        else -> ""
    }
    // (past tense if it happened, infinitive otherwise); null for steps that touch nothing.
    val act: Pair<String, String>? = when (field("action")) {
        "open_app" -> "Opened $app" to "open $app"
        "tap" -> "Tapped an item" to "tap an item"
        "long_press" -> "Long-pressed an item" to "long press an item"
        "type" -> "Typed into a field" to "type into a field"
        "scroll" -> "Scrolled$direction" to "scroll$direction"
        "swipe" -> "Swiped$direction" to "swipe$direction"
        "back" -> "Went back" to "go back"
        "home" -> "Went to the home screen" to "go to the home screen"
        else -> null
    }
    val text = when {
        act == null -> when (field("action")) {
            "wait" -> "Waited for the screen"
            "read" -> "Read the screen"
            "answer" -> "Prepared an answer"
            "done" -> "Model reported the task done"
            "give_up" -> "Model gave up"
            else -> "Planned a step"
        }
        event.stepExecutionStatus == ExecutionStatus.Executed -> act.first
        event.stepExecutionStatus == ExecutionStatus.Unknown -> "Tried to ${act.second}; effect unknown"
        else -> "Did not ${act.second}"
    }
    return "Step ${event.step} · $text"
}
