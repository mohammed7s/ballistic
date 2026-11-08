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

class LabelsTest {
    private fun run(vararg progress: String, steps: List<StepEvent> = emptyList()) =
        RunView("r", "open the Clock app", steps = steps, progress = progress.mapIndexed { i, t -> ProgressLine(i * 1000L, t) })

    private fun step(n: Int, action: Action, status: ExecutionStatus, outcome: String = "{\"raw\":\"model text\"}") =
        StepEvent(n, FakeCoreEngine.logForm(action), why = "the model's private reasoning", outcome = outcome,
            taskStatus = TaskStatus.Pending, executionStatus = status, stepExecutionStatus = status)

    @Test
    fun statusLineWhileRunning() {
        assertEquals("Starting…", runStatusLine(run("Starting request")))
        assertEquals("Reading the screen…", runStatusLine(run("Starting request", "Reading available screen information")))
        assertEquals("Waiting for the model…", runStatusLine(run("Starting request", "Waiting for planner model")))
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
        assertEquals("Waiting for the model…", runStatusLine(run("Step 1: open Clock → opened_ui", "Waiting for planner model", steps = listOf(opened))))
    }

    @Test
    fun statusLineNeverShowsUnknownTextOrErrors() {
        assertEquals("Working…", runStatusLine(run("Starting request", "{\"action\":\"tap\",\"raw\":\"model output\"}")))
        val failed = run("Starting request", "Run ended with an error").copy(error = "HTTP 500 {\"detail\":\"secret\"}", finishedElapsedMs = 5000)
        assertEquals("Ended with an error", runStatusLine(failed))
    }

    @Test
    fun statusLineWhenStoppedOrFinished() {
        assertEquals("Stopping…", runStatusLine(run("Starting request").copy(stopRequested = true)))
        val stopped = run("Stopped; earlier actions remain").copy(stopRequested = true, error = "cancelled", finishedElapsedMs = 2000)
        assertEquals("Stopped; earlier actions remain", runStatusLine(stopped))
        val opened = RunResult(false, "opened", 1, TaskStatus.OpenedUi, ExecutionStatus.Executed)
        assertEquals("Opened the app; the task itself is not confirmed",
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
        assertEquals("Unresolved: an action may or may not have taken effect", resultLabel(r))
    }

    @Test
    fun redactionHidesTypedText() {
        val log = FakeCoreEngine.logForm(Action.Type(4, "I'm late", true))
        assertEquals("type ••• into e4 and submit", actionLabel(log, redact = true))
        assertEquals("type \"I'm late\" into e4 and submit", actionLabel(log, redact = false))
    }
}
