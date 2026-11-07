// The real Rust core through the generated UniFFI binding, on the JVM, with ports that answer
// from a script. Needs a host build of the library; missing libraries are reported as skipped
// tests (build it with `cd core && cargo build --release --features uniffi --lib`).
// This is a mocked-port test of the binding, not an agent demo: the "model" is a fixed reply.

package app.ballistic.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeNotNull

class UniffiCoreEngineTest {
    private val engine: UniffiCoreEngine? = UniffiCoreEngine.load().getOrElse {
        println("UniffiCoreEngineTest: skipped, libballistic_core not loadable (${it::class.simpleName})")
        null
    }

    private fun nativeEngine(): UniffiCoreEngine {
        assumeNotNull(engine)
        return checkNotNull(engine)
    }

    private val screen = ScreenState(
        "com.android.systemui", "notification_listener", 1080, 2400, emptyList(),
        installedApps = listOf(AppEntry("Clock", "com.android.deskclock", null)),
        capabilities = HostCapabilities(listOf("open_app", "wait"), listOf("com.android.deskclock")),
    )

    private class Recorder : RunListener {
        val steps = mutableListOf<StepEvent>()
        val activities = mutableListOf<String>()
        override fun onStep(event: StepEvent) { steps += event }
        override fun onActivity(activity: String) { activities += activity }
    }

    private fun ports(acted: MutableList<HandsOp>, roles: MutableList<ModelRole>, replies: List<String>) = HostPorts(
        screen = { screen },
        hands = { op -> acted += op; HandsResult.Ok },
        models = { role, messages, _ ->
            check(messages.first().role == "system")
            roles += role
            Completion(replies.getOrElse(roles.size - 1) { """{"action":"give_up","reason":"end of script"}""" }, Usage(10, 5))
        },
        confirm = { ConfirmResult.Denied },
    )

    @Test
    fun openAppRunsThroughTheRealLoop() {
        val core = nativeEngine()
        val acted = mutableListOf<HandsOp>()
        val roles = mutableListOf<ModelRole>()
        val recorder = Recorder()
        val result = core.run("open the Clock app", 2, ports(acted, roles, listOf("""{"action":"open_app","app":"Clock","why":"asked"}""")), recorder, CancelToken())
        assertEquals(listOf<HandsOp>(HandsOp.OpenApp("com.android.deskclock", null)), acted)
        assertEquals(listOf(ModelRole.Planner, ModelRole.Planner), roles)
        assertEquals(2, recorder.steps.size)
        assertEquals("opened_ui", recorder.steps[0].outcome)
        assertEquals("asked", recorder.steps[0].why)
        assertEquals(TaskStatus.Failed, result.taskStatus, "the second plan gives up")
        assertEquals(ExecutionStatus.Executed, result.executionStatus)
        assertTrue("planning" in recorder.activities && "acting" in recorder.activities)
    }

    @Test
    fun stopBeforeStartMakesNoCalls() {
        val core = nativeEngine()
        val acted = mutableListOf<HandsOp>()
        val roles = mutableListOf<ModelRole>()
        val token = CancelToken().apply { cancel() }
        val result = core.run("open the Clock app", 5, ports(acted, roles, emptyList()), Recorder(), token)
        assertTrue(result.stopped)
        assertEquals(0, result.steps)
        assertTrue(acted.isEmpty() && roles.isEmpty())
    }

    @Test
    fun portFailureEndsTheRunAsAnError() {
        val core = nativeEngine()
        val failing = HostPorts(
            screen = { screen },
            hands = { error("not reached") },
            models = { _, _, _ -> throw PortException.Host("no model endpoint configured (Settings > Models)") },
            confirm = { ConfirmResult.Denied },
        )
        val e = assertFailsWith<CoreRunException> { core.run("open the Clock app", 5, failing, Recorder(), CancelToken()) }
        assertEquals("host error: no model endpoint configured (Settings > Models)", e.message)
    }

    @Test
    fun stopDuringPlannerCallPreventsDispatch() {
        val core = nativeEngine()
        val acted = mutableListOf<HandsOp>()
        val roles = mutableListOf<ModelRole>()
        val token = CancelToken()
        val base = ports(acted, roles, listOf("""{"action":"open_app","app":"Clock"}"""))
        val stopping = base.copy(models = { role, messages, maxTokens ->
            base.models.complete(role, messages, maxTokens).also { token.cancel() }
        })
        val result = core.run("open the Clock app", 5, stopping, Recorder(), token)
        assertTrue(result.stopped)
        assertEquals(ExecutionStatus.NotExecuted, result.executionStatus)
        assertEquals(listOf(ModelRole.Planner), roles)
        assertTrue(acted.isEmpty())
    }

    @Test
    fun choiceFallsBackToTheFakeWithoutAModel() {
        val fake = FakeCoreEngine.demo()
        val choice = EngineChoice(UniffiCoreEngine.load(), fake, modelConfigured = { false })
        assertEquals(fake, choice.current)
        assertTrue(choice.description.startsWith("Fake core (scripted replay, no planning)"))
        nativeEngine()
        val configured = EngineChoice(UniffiCoreEngine.load(), fake, modelConfigured = { true })
        assertTrue(configured.current is UniffiCoreEngine)
        assertTrue(configured.description.startsWith("Ballistic core 0.1.0"))
    }
}
