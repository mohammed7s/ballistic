package app.ballistic.assistant.session

import app.ballistic.assistant.core.Action
import app.ballistic.assistant.core.ExecutionStatus
import app.ballistic.assistant.core.FakeCoreEngine
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.ballistic.assistant.core.RecordJson

class LabelsTest {
    private fun run(vararg progress: String, steps: List<StepEvent> = emptyList()) =
        RunView("r", "open the Clock app", steps = steps, progress = progress.mapIndexed { i, t -> ProgressLine(i * 1000L, t) })

    private fun step(n: Int, action: Action, status: ExecutionStatus, outcome: String = "{\"raw\":\"model text\"}") =
        StepEvent(n, FakeCoreEngine.logForm(action), why = "the model's private reasoning", outcome = outcome,
            taskStatus = TaskStatus.Pending, executionStatus = status, stepExecutionStatus = status)

    @Test
    fun statusLineWhileRunning() {
        assertEquals("Starting…", runStatusLine(run("Starting request")))
        assertEquals("Checking what's available…", runStatusLine(run("Starting request", "Reading available screen information")))
        assertEquals("Thinking about the next step…", runStatusLine(run("Starting request", "Waiting for planner model")))
        assertEquals("Thinking…", runStatusLine(run("Starting request", "Waiting for chat model")))
        assertEquals("Checking the model's reply…", runStatusLine(run("Waiting for planner model", "Planner reply received; checking response")))
    }

    @Test
    fun statusLineAwaitingApproval() {
        assertEquals("Waiting for your approval", runStatusLine(run("Starting request", "Waiting for planner model"), awaitingApproval = true))
        assertEquals("Waiting for your approval", runStatusLine(run("Starting request", "awaiting approval")))
    }

    @Test
    fun statusLineForExecutedAndFailedSteps() {
        val opened = step(1, Action.OpenApp("Clock"), ExecutionStatus.Executed)
        assertEquals("Step 1 · Opened Clock", runStatusLine(run("Starting request", "Step 1: open Clock → opened_ui", steps = listOf(opened))))
        assertEquals("Step 2 · Did not open Clock", stepSummary(step(2, Action.OpenApp("Clock"), ExecutionStatus.NotExecuted)))
        assertEquals("Step 3 · Tried to tap an item; effect unknown", stepSummary(step(3, Action.Tap(4), ExecutionStatus.Unknown)))
        // Typed text, model reasons and outcomes never reach the line.
        val typed = stepSummary(step(4, Action.Type(4, "I'm late", true), ExecutionStatus.Executed))
        assertEquals("Step 4 · Typed into a field", typed)
        assertEquals("Step 5 · Model reported the task done", stepSummary(step(5, Action.Done("{\"json\":1}"), ExecutionStatus.NotExecuted)))
        // A newer lifecycle event replaces the step line.
        assertEquals("Thinking about the next step…", runStatusLine(run("Step 1: open Clock → opened_ui", "Waiting for planner model", steps = listOf(opened))))
    }

    @Test
    fun statusLineNeverShowsUnknownTextOrErrors() {
        assertEquals("Working…", runStatusLine(run("Starting request", "{\"action\":\"tap\",\"raw\":\"model output\"}")))
        val failed = run("Starting request", "Run ended with an error").copy(error = "HTTP 500 {\"detail\":\"secret\"}", finishedElapsedMs = 5000)
        assertEquals("Something went wrong", runStatusLine(failed))
    }

    @Test
    fun statusLineWhenStoppedOrFinished() {
        assertEquals("Stopping…", runStatusLine(run("Starting request").copy(stopRequested = true)))
        val stopped = run("Stopped; earlier actions remain").copy(stopRequested = true, error = "cancelled", finishedElapsedMs = 2000)
        assertEquals("Stopped; earlier steps may have taken effect", runStatusLine(stopped))
        val opened = RunResult(false, "opened", 1, TaskStatus.OpenedUi, ExecutionStatus.Executed)
        assertEquals("I opened it, but couldn't confirm anything beyond that",
            runStatusLine(run("Starting request").copy(result = opened, finishedElapsedMs = 3000)))
        val stoppedResult = RunResult(false, "stopped", 0, TaskStatus.Pending, ExecutionStatus.NotExecuted, stopped = true)
        assertEquals("Stopped; nothing was done", runStatusLine(run().copy(result = stoppedResult, stopRequested = true, finishedElapsedMs = 1)))
    }

    @Test
    fun acknowledgedInputIsNeverSent() {
        val acknowledged = RunResult(false, "guarded input acknowledged", 3, TaskStatus.Pending, ExecutionStatus.Executed, failureCategory = "verification")
        val label = resultLabel(acknowledged).lowercase()
        assertFalse("sent" in label || "done" in label || "completed" in label, label)
    }

    @Test
    fun unknownExecutionIsUnresolved() {
        val r = RunResult(false, "x", 1, TaskStatus.Pending, ExecutionStatus.Unknown)
        assertEquals(Outcome("UNRESOLVED", LabelStyle.Inverted, "May or may not have happened. Check before trying again."), outcome(r))
    }

    @Test
    fun redactionHidesTypedText() {
        val log = FakeCoreEngine.logForm(Action.Type(4, "I'm late", true))
        assertEquals("type ••• into e4 and submit", actionLabel(log, redact = true))
        assertEquals("type \"I'm late\" into e4 and submit", actionLabel(log, redact = false))
    }

    // The owner's Pixel, 8 Nov: "what's your CPU?" ran as a task, the planner answered, and the
    // card said "Unresolved: input was accepted, the effect is not confirmed". The report below
    // is core's shape for that run (agent.rs, Action::Answer).
    private val cpuAnswer = RecordJson.decodeFromString(RunResult.serializer(), """
        {"done":false,"reason":"I do not have access to the device's CPU information.","steps":1,
         "task_status":"pending","execution_status":"not_executed",
         "verification":{"status":"unknown","evidence_basis":"answer_values_sourced","nodes":[]},
         "failure_category":null,
         "answer":{"text":"I do not have access to the device's CPU information.",
                   "display":"I do not have access to the device's CPU information.","values":[]}}
    """)

    @Test
    fun anAnswerIsTheVisibleResultNotAnInput() {
        val o = outcome(cpuAnswer)
        assertEquals("I do not have access to the device's CPU information.", o.line)
        assertEquals("ANSWER", o.label)
        assertFalse("input" in o.line.lowercase())
        val card = run("Starting request").copy(result = cpuAnswer, finishedElapsedMs = 4000)
        assertEquals("I do not have access to the device's CPU information.", runStatusLine(card))
        assertEquals("each value checked against its source; the answer itself is not verified", evidenceLabel(cpuAnswer))
    }

    @Test
    fun anAnswerShowsItsValueSources() {
        val sourced = RecordJson.decodeFromString(RunResult.serializer(), """
            {"done":false,"reason":"Ali's number is +1 555 0100 (from app:example.sms).","steps":2,
             "task_status":"pending","execution_status":"not_executed",
             "verification":{"status":"unknown","evidence_basis":"answer_values_sourced"},
             "answer":{"text":"Ali's number is +1 555 0100.","display":"Ali's number is +1 555 0100 (from app:example.sms).",
                       "values":[{"value":"+1 555 0100","source":{"kind":"app","detail":"example.sms"}}]}}
        """)
        assertEquals("Ali's number is +1 555 0100 (from app:example.sms).", outcome(sourced).line)
    }

    @Test
    fun excerptsAreTheVisibleResult() {
        val excerpts = RecordJson.decodeFromString(RunResult.serializer(), """
            {"done":false,"reason":"Meeting moved to 3pm (from app:com.example.mail)","steps":1,
             "task_status":"pending","execution_status":"not_executed",
             "verification":{"status":"unknown","evidence_basis":"model_selected_source_excerpt"},
             "owner_output":{"status":"available","items":[]}}
        """)
        assertEquals("EXCERPTS", outcome(excerpts).label)
        assertEquals("Meeting moved to 3pm (from app:com.example.mail)", outcome(excerpts).line)
        val rejected = excerpts.copy(ownerOutput = RecordJson.parseToJsonElement("""{"status":"rejected"}"""), reason = "summary rejected")
        assertFalse(outcome(rejected).label == "EXCERPTS")
    }

    @Test
    fun aRunThatDispatchedNothingNeverSaysInputWasAccepted() {
        for (task in TaskStatus.entries) for (stopped in listOf(false, true)) for (failure in listOf(null, "verification", "policy_denial", "planning")) {
            val r = RunResult(false, "guarded input acknowledged", 2, task, ExecutionStatus.NotExecuted, failureCategory = failure, stopped = stopped)
            val line = outcome(r).line.lowercase()
            assertFalse("accepted" in line, "$task/$stopped/$failure: $line")
        }
        assertEquals("I didn't finish; nothing was done",
            outcome(RunResult(false, "step budget exhausted", 15, TaskStatus.Pending, ExecutionStatus.NotExecuted)).line)
    }

    @Test
    fun collapsedCardKeepsTheRequestAndAPlainResult() {
        val failed = run("Starting request").copy(result = RunResult(false, "x", 1, TaskStatus.Failed, ExecutionStatus.NotExecuted), finishedElapsedMs = 1)
        assertEquals("open the Clock app", failed.request)
        assertEquals("I couldn't do that; nothing was done", runStatusLine(failed))
        assertTrue(runStatusLine(failed) != "Failed")
    }

    @Test
    fun askShowsAtMostThreeRecentItems() {
        assertEquals(listOf(5, 6, 7), recentForAsk((1..7).toList()))
        assertEquals(listOf(1, 2), recentForAsk(listOf(1, 2)))
        assertEquals(3, ASK_RECENT_LIMIT)
    }

    @Test
    fun errorsShowAShortKnownReasonOnly() {
        assertEquals("Something went wrong: the model isn't loaded", errorLine("Host(reason=model not loaded)"))
        assertEquals("Something went wrong: no internet connection", errorLine("java.net.UnknownHostException: api.example"))
        assertEquals("Something went wrong", errorLine("HTTP 500 {\"detail\":\"secret\"}"))
    }
}
