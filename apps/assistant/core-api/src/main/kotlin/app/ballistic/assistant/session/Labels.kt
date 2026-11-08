// Words the UI uses for results and actions. Kept beside the session so the rules are tested:
// an acknowledged input is never shown as "sent" or "done" (docs/ANDROID_APP.md, Result), an
// answer is shown as the answer (never as an input), and a run that dispatched nothing never
// says an input was accepted. Labels are greyscale: outlined for an ordinary state, inverted for
// a state that needs the owner or may need checking (docs/UX_SYNTHESIS.md §5.3).

package app.ballistic.assistant.session

import app.ballistic.assistant.core.ExecutionStatus
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.TaskStatus
import app.ballistic.assistant.core.VerificationStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** How many recent items Ask shows; the rest are in Activity (owner, 8 Nov). */
const val ASK_RECENT_LIMIT = 3

/** Ask's items: the most recent [limit], oldest first. The active item is always the last. */
fun <T> recentForAsk(items: List<T>, limit: Int = ASK_RECENT_LIMIT): List<T> = items.takeLast(limit)

/** Core's context guard refused the prompt (TurnKind.DoesNotFit, failure_category model_context). */
const val TOO_LONG_LINE = "That request is too long for this model's context"

/** Outlined = ordinary state; Inverted = needs the owner or may need checking. */
enum class LabelStyle { Outlined, Inverted }

/** A finished run in the owner's words: a short status label and the plain result line. */
data class Outcome(val label: String, val style: LabelStyle, val line: String)

/** The answer as core displays it (each content value followed by its source), if the run answered. */
fun answerText(result: RunResult): String? {
    val answer = result.answer ?: return null
    if (answer is JsonPrimitive && answer.contentOrNull == null) return null
    val display = (answer as? JsonObject)?.get("display")
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    return (display ?: result.reason).trim().ifEmpty { null }
}

/** Source excerpts core showed the owner (terminal summary), if any. */
fun excerptsText(result: RunResult): String? {
    val status = (result.ownerOutput as? JsonObject)?.get("status")
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    val shown = status == "available" ||
        (status == null && result.verification.evidenceBasis == "model_selected_source_excerpt")
    return if (shown) result.reason.trim().ifEmpty { null } else null
}

fun outcome(result: RunResult): Outcome {
    val nothingDone = result.executionStatus == ExecutionStatus.NotExecuted
    answerText(result)?.let { return Outcome("ANSWER", LabelStyle.Outlined, it) }
    excerptsText(result)?.let { return Outcome("EXCERPTS", LabelStyle.Outlined, it) }
    return when {
        result.stopped -> Outcome("STOPPED", LabelStyle.Outlined,
            if (nothingDone) "Stopped; nothing was done" else "Stopped; earlier steps may have taken effect")
        result.executionStatus == ExecutionStatus.Unknown -> Outcome("UNRESOLVED", LabelStyle.Inverted,
            "May or may not have happened. Check before trying again.")
        result.failureCategory == "model_context" -> Outcome("TOO LONG", LabelStyle.Outlined,
            if (nothingDone) TOO_LONG_LINE else "$TOO_LONG_LINE; earlier steps may have taken effect")
        result.blockedBy == "judge" -> Outcome("BLOCKED", LabelStyle.Outlined,
            "I stopped: the safety check blocked a step")
        result.taskStatus == TaskStatus.Completed && result.done -> Outcome("DONE", LabelStyle.Outlined,
            if (result.verification.status == VerificationStatus.Verified) "Done. I checked the screen afterwards." else "Done")
        result.taskStatus == TaskStatus.Completed -> Outcome("NOT CONFIRMED", LabelStyle.Outlined,
            "I finished the steps, but couldn't confirm the result")
        result.failureCategory == "policy_denial" -> Outcome("NOT DONE", LabelStyle.Outlined,
            if (nothingDone) "Not done: an approval was declined or a rule blocked it"
            else "Stopped at a declined approval or a blocking rule; earlier steps may have taken effect")
        result.taskStatus == TaskStatus.OpenedUi -> Outcome("NOT CONFIRMED", LabelStyle.Outlined,
            "I opened it, but couldn't confirm anything beyond that")
        result.taskStatus == TaskStatus.Unsupported -> Outcome("NOT SUPPORTED", LabelStyle.Outlined,
            "I can't do that on this phone yet")
        result.taskStatus == TaskStatus.Failed -> Outcome("FAILED", LabelStyle.Outlined,
            if (nothingDone) "I couldn't do that; nothing was done" else "I couldn't finish; earlier steps may have taken effect")
        // Pending from here on: an input went through but nothing confirms its effect, or
        // nothing was dispatched at all. Only the first may say an input was accepted.
        nothingDone -> Outcome("NOT FINISHED", LabelStyle.Outlined, "I didn't finish; nothing was done")
        else -> Outcome("NOT CONFIRMED", LabelStyle.Outlined, "Input was accepted; the effect is not confirmed")
    }
}

/** The one-line result for a finished run (kept for the Activity list and progress feed). */
fun resultLabel(result: RunResult): String = outcome(result).line

/** What the completion rests on, from core's verification record. */
fun evidenceLabel(result: RunResult): String = when (result.verification.evidenceBasis) {
    "none" -> "no evidence"
    "model_assessment" -> "a model's reading of the screen"
    "model_selected_source_excerpt" -> "model-selected quotations; truth and coverage are unverified"
    "answer_values_sourced" -> "each value checked against its source; the answer itself is not verified"
    "visible_ui" -> "a check of the visible screen"
    else -> result.verification.evidenceBasis
}

/**
 * A short reason for a run that ended with an error, from known failure shapes only, so a
 * provider error body or stack text never reaches the card. The full text stays in details.
 */
fun errorLine(error: String): String {
    val e = error.lowercase()
    return when {
        "not loaded" in e || "no model loaded" in e -> "Something went wrong: the model isn't loaded"
        "unknownhost" in e || "unable to resolve host" in e || "no address associated" in e ||
            "network is unreachable" in e -> "Something went wrong: no internet connection"
        "timeout" in e || "timed out" in e -> "Something went wrong: the model didn't answer in time"
        else -> "Something went wrong"
    }
}

/**
 * The finished-run outcome including runs that ended without a report. A chat task's line is
 * core's own first-person result line (TurnResult.text; for an answer, the answer with its
 * sources); the label still comes from the report's code-owned fields.
 */
fun runOutcome(run: RunView): Outcome {
    val base = when {
        run.result != null -> outcome(run.result)
        run.stopRequested || run.kind == ExchangeKind.Stopped -> Outcome("STOPPED", LabelStyle.Outlined, "Stopped; earlier steps may have taken effect")
        run.error != null -> Outcome("ERROR", LabelStyle.Inverted, errorLine(run.error))
        run.restored -> historyOutcome(run.reply)
        else -> Outcome("ENDED", LabelStyle.Outlined, "Ended")
    }
    return if (run.isTask && run.result != null && run.reply != null) base.copy(line = run.reply) else base
}

/** A label for a task restored from core's history line (`Task: … → <outcome words>`). */
fun historyOutcome(words: String?): Outcome {
    val w = words.orEmpty()
    val label = when (w.lowercase()) {
        "done, verified" -> "DONE"
        "opened, not confirmed" -> "NOT CONFIRMED"
        "answered, not verified" -> "ANSWER"
        "excerpts shown to the owner" -> "EXCERPTS"
        "owner declined a confirmation" -> "NOT DONE"
        "blocked by the safety check" -> "BLOCKED"
        "stopped by owner" -> "STOPPED"
        "stopped by an error" -> "ERROR"
        "failed" -> "FAILED"
        "not supported" -> "NOT SUPPORTED"
        "not finished" -> "NOT FINISHED"
        else -> "EARLIER"
    }
    return Outcome(label, if (label == "ERROR") LabelStyle.Inverted else LabelStyle.Outlined, w.ifEmpty { "Earlier task" })
}

/**
 * A past task from the action log only (no report kept): its last step event. An answer's text
 * is in the step's action and is shown only when the owner reveals typed text.
 */
fun logOutcome(last: StepEvent, showContent: Boolean): Outcome {
    val obj = last.action as? JsonObject
    val kind = obj?.get("action")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    if (kind == "answer") {
        val text = obj["text"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        return Outcome("ANSWER", LabelStyle.Outlined, if (showContent && text != null) text else "I answered (text hidden)")
    }
    return outcome(RunResult(
        done = last.taskStatus == TaskStatus.Completed && last.verification.status == VerificationStatus.Verified,
        reason = last.outcome, steps = last.step, taskStatus = last.taskStatus,
        executionStatus = last.executionStatus, verification = last.verification, failureCategory = last.failureCategory,
    ))
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
 * The run card's second line: what is happening now, or the plain result once finished. Built
 * only from known lifecycle strings, step metadata and core-owned result fields; anything
 * unrecognised becomes a generic line, so raw model output, error text or JSON never reaches it.
 * An answer is the exception by design: core checked each value's source and shows it.
 * [awaitingApproval] is the session's phase for the active run.
 */
fun runStatusLine(run: RunView, awaitingApproval: Boolean = false): String {
    if (run.finished) return runOutcome(run).line
    if (awaitingApproval) return "Waiting for your approval"
    if (run.stopRequested) return "Stopping…"
    val latest = run.progress.lastOrNull()?.text ?: return "Starting…"
    val step = run.steps.lastOrNull()
    if (step != null && latest.startsWith("Step ${step.step}:")) return stepSummary(step)
    return when {
        latest == "Starting request" -> "Starting…"
        latest == "observing" || latest == "Reading available screen information" -> "Checking what's available…"
        latest == "acting" || latest == "Dispatching an action" -> "Acting on the phone…"
        latest == "awaiting approval" -> "Waiting for your approval"
        latest == "Waiting for chat model" -> "Thinking…"
        latest.startsWith("Waiting for ") && latest.endsWith(" model") -> "Thinking about the next step…"
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
