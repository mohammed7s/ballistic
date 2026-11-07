package app.ballistic.assistant.session

import app.ballistic.assistant.core.Action
import app.ballistic.assistant.core.ExecutionStatus
import app.ballistic.assistant.core.FakeCoreEngine
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LabelsTest {
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
