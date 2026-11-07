// The scripted engine follows core's stopping rules, so the UI is built against the same
// outcomes: a denial dispatches nothing, Stop before dispatch dispatches nothing, an
// acknowledged guarded input stops the run as unverified.

package app.ballistic.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeCoreEngineTest {
    private val screen = ScreenState("com.android.deskclock", ".Main", 1080, 2400, emptyList(), capabilities = HostCapabilities(listOf("open_app", "tap"), listOf("com.android.deskclock")))
    private val confirm = ConfirmRequest(HandsOp.Tap(1, 2), "com.android.deskclock", "tap e4", "Save", hard = false, reason = "alarm")
    private val script = listOf(
        ScriptedStep(Action.OpenApp("Clock"), "open", HandsOp.OpenApp("com.android.deskclock")),
        ScriptedStep(Action.Tap(4), "save", HandsOp.Tap(1, 2), confirm),
        ScriptedStep(Action.Done("listed"), "done"),
    )

    private class Recorder : RunListener {
        val steps = mutableListOf<StepEvent>()
        override fun onStep(event: StepEvent) { steps += event }
    }

    private fun ports(acted: MutableList<HandsOp>, answer: (ConfirmRequest) -> ConfirmResult) = HostPorts(
        screen = { screen },
        hands = { op -> acted += op; HandsResult.Ok },
        models = { _, _, _ -> error("the fake makes no model calls") },
        confirm = { answer(it) },
    )

    @Test
    fun denialDispatchesNothingFurther() {
        val acted = mutableListOf<HandsOp>()
        val result = FakeCoreEngine(script).run("set alarm", 10, ports(acted) { ConfirmResult.Denied }, Recorder(), CancelToken())
        assertEquals(listOf<HandsOp>(HandsOp.OpenApp("com.android.deskclock")), acted)
        assertEquals("policy_denial", result.failureCategory)
        assertEquals(TaskStatus.Failed, result.taskStatus)
    }

    @Test
    fun stopDuringApprovalPreventsDispatch() {
        val acted = mutableListOf<HandsOp>()
        val token = CancelToken()
        val result = FakeCoreEngine(script).run("set alarm", 10, ports(acted) { token.cancel(); ConfirmResult.Approved }, Recorder(), token)
        assertEquals(1, acted.size)
        assertTrue(result.stopped)
        assertEquals(ExecutionStatus.Executed, result.executionStatus, "the earlier open_app effect is kept in the result")
    }

    @Test
    fun acknowledgedGuardedInputStopsUnverified() {
        val acted = mutableListOf<HandsOp>()
        val recorder = Recorder()
        val result = FakeCoreEngine(script).run("set alarm", 10, ports(acted) { ConfirmResult.Approved }, recorder, CancelToken())
        assertEquals(2, acted.size)
        assertEquals(false, result.done)
        assertEquals("verification", result.failureCategory)
        assertEquals(2, recorder.steps.size)
    }

    @Test
    fun logFormMatchesCore() {
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"action":"tap","target":"e7"}"""), FakeCoreEngine.logForm(Action.Tap(7)))
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"action":"scroll","direction":"down"}"""), FakeCoreEngine.logForm(Action.Scroll(null, Direction.Down)))
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"action":"read","targets":["c1","c2"],"question":"q","shape":"facts"}"""),
            FakeCoreEngine.logForm(Action.Read(listOf(1, 2), "q", ReadShape.Facts)),
        )
    }
}
