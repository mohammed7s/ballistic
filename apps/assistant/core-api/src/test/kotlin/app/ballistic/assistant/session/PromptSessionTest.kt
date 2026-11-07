// The prompt box driven end to end by the scripted engine: gate, steps, approval, Stop, log.

package app.ballistic.assistant.session

import app.ballistic.assistant.core.ActionLog
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.FakeCoreEngine
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.HostPorts
import app.ballistic.assistant.core.RustCore
import app.ballistic.assistant.core.ScreenState
import app.ballistic.assistant.core.TaskStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class PromptSessionTest {
    @Test
    fun cancellationThatThrowsIsStillRecordedAsAnOwnerStop() = runTest {
        lateinit var session: PromptSession
        val cancelledPorts = ports(mutableListOf()) { ConfirmResult.Approved }.copy(
            screen = { session.stop(); error("local operation stopped") },
        )
        session = PromptSession(this, Gate(true), cancelledPorts,
            { RustCore(FakeCoreEngine(FakeCoreEngine.demoSteps()), it, StandardTestDispatcher(testScheduler)) })
        session.submit("open Clock")
        testScheduler.advanceUntilIdle()
        assertEquals(Phase.Finished, session.state.value.phase)
        assertTrue(session.state.value.runs.single().stopRequested)
        assertTrue(session.state.value.runs.single().error != null)
    }

    @Test
    fun freshDeviceCheckOverridesStaleUnlockedUiState() = runTest {
        val gate = object : CommandGateState {
            override val unlocked = MutableStateFlow(true)
            override fun canAcceptCommands() = false
        }
        val acted = mutableListOf<HandsOp>()
        val session = PromptSession(this, gate, ports(acted) { ConfirmResult.Approved },
            { RustCore(FakeCoreEngine(FakeCoreEngine.demoSteps()), it, StandardTestDispatcher(testScheduler)) })
        session.submit("open Clock")
        session.onUnlocked()
        testScheduler.advanceUntilIdle()
        assertEquals(Phase.NeedsUnlock, session.state.value.phase)
        assertTrue(session.state.value.runs.isEmpty())
        assertTrue(acted.isEmpty())
    }

    @Test
    fun elapsedTimeExcludesAuthenticationAndFreezesAfterFailure() = runTest {
        val gate = Gate(false)
        var monotonic = 100L
        val timedPorts = ports(mutableListOf()) { ConfirmResult.Approved }.copy(
            screen = { monotonic = 1250L; error("observation failed") },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = PromptSession(this, gate, timedPorts,
            { RustCore(FakeCoreEngine(FakeCoreEngine.demoSteps()), it, dispatcher) },
            clock = { -999L }, elapsedClock = { monotonic })
        session.submit("open Clock")
        monotonic = 1000L
        assertTrue(session.state.value.runs.isEmpty())
        gate.unlocked.value = true
        session.onUnlocked()
        assertEquals(1000L, session.state.value.runs.single().startedElapsedMs)
        monotonic = 1100L
        assertEquals(100L, session.elapsedMillis(session.state.value.runs.single()))
        testScheduler.advanceUntilIdle()
        val finished = session.state.value.runs.single()
        assertEquals(Phase.Finished, session.state.value.phase)
        assertEquals(1250L, finished.finishedElapsedMs)
        monotonic = 9000L
        assertEquals(250L, session.elapsedMillis(finished))
    }

    private class Gate(open: Boolean) : CommandGateState {
        override val unlocked = MutableStateFlow(open)
    }

    private val screen = ScreenState("com.android.launcher", ".Home", 1080, 2400, emptyList())

    private fun ports(acted: MutableList<HandsOp>, confirm: () -> ConfirmResult) = HostPorts(
        screen = { screen },
        hands = { op -> synchronized(acted) { acted += op }; HandsResult.Ok },
        models = { _, _, _ -> error("unused") },
        confirm = { confirm() },
    )

    @Test
    fun lockedGateParksTheRequestUntilUnlocked() = runTest {
        val gate = Gate(open = false)
        val acted = mutableListOf<HandsOp>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = PromptSession(this, gate, ports(acted) { ConfirmResult.Approved }, { RustCore(FakeCoreEngine(FakeCoreEngine.demoSteps()), it, dispatcher) })
        session.submit("set an alarm for 7")
        assertEquals(Phase.NeedsUnlock, session.state.value.phase)
        assertEquals(0, acted.size)

        gate.unlocked.value = true
        session.onUnlocked()
        testScheduler.advanceUntilIdle()
        val state = session.state.value
        assertEquals(Phase.Finished, state.phase)
        assertEquals(1, state.runs.size)
        assertEquals(3, state.runs.single().steps.size)
        assertEquals("verification", state.runs.single().result!!.failureCategory, "guarded tap ends unverified")
        assertEquals(3, acted.size)
    }

    @Test
    fun cancelledUnlockDropsTheRequest() = runTest {
        val session = PromptSession(this, Gate(false), ports(mutableListOf()) { ConfirmResult.Approved }, { RustCore(FakeCoreEngine(emptyList()), it) })
        session.submit("anything")
        session.onUnlockCancelled()
        assertEquals(Phase.Idle, session.state.value.phase)
        assertEquals(null, session.state.value.pendingRequest)
        assertTrue(session.state.value.runs.isEmpty())
    }

    @Test
    fun approvalPhaseIsVisibleAndStopThereDispatchesNothing() = runBlocking {
        // Real threads: the engine blocks inside Confirm, as core will.
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acted = mutableListOf<HandsOp>()
        val logFile = File.createTempFile("ballistic-log", ".jsonl").apply { deleteOnExit() }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val session = PromptSession(
            scope, Gate(true),
            ports(acted) { asked.countDown(); release.await(5, TimeUnit.SECONDS); ConfirmResult.Approved },
            { RustCore(FakeCoreEngine(FakeCoreEngine.demoSteps()), it) },
            log = ActionLog(logFile),
        )
        session.submit("set an alarm for 7")
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        assertEquals(Phase.AwaitingApproval, session.state.value.phase)
        assertFalse(session.state.value.canSubmit, "a second submission is refused while a run is active")
        session.submit("second request")
        assertEquals(1, session.state.value.runs.size)

        session.stop()
        release.countDown()
        withTimeout(5_000) { session.state.first { it.phase == Phase.Finished } }
        val run = session.state.value.runs.single()
        assertTrue(run.result!!.stopped)
        assertEquals(TaskStatus.Pending, run.result.taskStatus, "effects so far are kept, not reported as completed")
        assertEquals(2, acted.size, "open_app and wait ran; the approved tap did not")
        assertEquals(2, ActionLog(logFile).read().lines.size)
        scope.cancel()
    }
}
