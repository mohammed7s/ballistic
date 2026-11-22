package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.AppEntry
import app.ballistic.assistant.core.Bounds
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.HostCapabilities
import app.ballistic.assistant.core.PortException
import kotlin.test.*

class ComputerControlAdaptersTest {
    private val targets = listOf("com.a", "com.b")
    private val caps = HostCapabilities(listOf("tap"), listOf("com.a"))

    @Test fun handsRunOnlyInAnActiveSession() {
        assertEquals(HandsResult.NotExecuted(CcReason.NotStarted.message), ComputerControlHands({ null }).act(HandsOp.Tap(1, 1)))
        val cases = mapOf(
            CcState.Idle(CcReason.Closed) to CcReason.Closed,
            CcState.Requesting to CcReason.Starting,
            CcState.ConsentNeeded(targets) to CcReason.ConsentNeeded,
            CcState.Starting(targets) to CcReason.Starting,
            CcState.Blocked(targets, CcReason.BlockedSecure, "com.a") to CcReason.BlockedSecure,
        )
        for ((state, reason) in cases) {
            val s = FakeSession(state)
            assertEquals(HandsResult.NotExecuted(reason.message), ComputerControlHands({ s }, sleep = {}).act(HandsOp.Tap(1, 1)), "$state")
            assertTrue(s.input.calls.isEmpty())
        }
        val s = FakeSession(CcState.Active(targets))
        assertEquals(HandsResult.Ok, ComputerControlHands({ s }, sleep = {}).act(HandsOp.Tap(1, 1)))
        assertEquals(listOf("tap 1,1"), s.input.calls)
    }

    @Test fun cancellationDuringFocusDelayPreventsTextInput() {
        val s = FakeSession(CcState.Active(targets))
        val hands = ComputerControlHands({ s }, sleep = { s.state = CcState.Idle(CcReason.Closed) })
        assertIs<HandsResult.Error>(hands.act(HandsOp.Type(1, 2, "never inserted", false)))
        assertEquals(listOf("tap 1,2"), s.input.calls)
    }

    @Test fun focusDelayCannotMoveTypingIntoAnotherAllowedTarget() {
        val s = FakeSession(CcState.Active(targets), listOf(appWindow("com.a")))
        val hands = ComputerControlHands({ s }, sleep = { s.windowList = listOf(appWindow("com.b")) })
        assertIs<HandsResult.Error>(hands.act(HandsOp.Type(1, 2, "never inserted", false)))
        assertEquals(listOf("tap 1,2"), s.input.calls)
    }

    @Test fun openAppStaysInsideTheSessionTargets() {
        val s = FakeSession(CcState.Active(targets))
        val hands = ComputerControlHands({ s }, sleep = {})
        assertEquals(HandsResult.NotExecuted(CcReason.NotTarget.message), hands.act(HandsOp.OpenApp("com.other")))
        assertEquals(HandsResult.Ok, hands.act(HandsOp.OpenApp("com.b")))
        assertEquals(listOf("launch com.b -"), s.input.calls)
    }

    private fun screen(s: FakeSession?, readable: Set<String>, sleeps: MutableList<Long> = mutableListOf()) =
        ComputerControlScreen({ s }, { it in readable }, { listOf(AppEntry("A", "com.a")) }, { caps }, sleep = { sleeps += it })

    @Test fun screenReturnsOnlyReadableAppsWithLabelsAndTextApart() {
        val ime = AxWindow(AxWindowKind.InputMethod, root = AxNode(className = "android.widget.FrameLayout", packageName = "com.keyboard",
            children = listOf(AxNode(className = "android.widget.TextView", text = "clipboard: secret", packageName = "com.keyboard"))))
        val s = FakeSession(CcState.Active(targets), listOf(
            appWindow("com.a", AxNode(className = "android.widget.Button", contentDescription = "Send", text = "Hi Nora", packageName = "com.a",
                bounds = Bounds(10, 10, 100, 60), clickable = true, visibleToUser = true)),
            ime,
            appWindow("com.b", AxNode(className = "android.widget.TextView", text = "not readable", packageName = "com.b"), active = false),
        ), width = 720, height = 1600)
        val state = screen(s, readable = setOf("com.a")).observe()
        assertEquals("com.a", state.`package`)
        assertEquals("", state.activity, "the API reports no activity")
        assertNull(state.foreground, "no foreground readback is claimed")
        assertEquals(720 to 1600, state.width to state.height)
        assertEquals(listOf("com.a", "com.a"), state.nodes.map { it.`package` })
        assertEquals("Send", state.nodes[1].label)
        assertEquals("Hi Nora", state.nodes[1].text)
        assertTrue(state.nodes.none { it.text == "clipboard: secret" || it.text == "not readable" })
        assertEquals(caps, state.capabilities)
        assertEquals(listOf(AppEntry("A", "com.a")), state.installedApps)
    }

    @Test fun readableWindowCannotSmuggleAnotherPackagesNodes() {
        val s = FakeSession(CcState.Active(listOf("com.a")), listOf(appWindow("com.a",
            AxNode(packageName = "com.a", children = listOf(
                AxNode(packageName = "com.secret", text = "hidden"),
                AxNode(packageName = "com.b", text = "not a target"),
                AxNode(text = "inherits permitted package"),
            )))))
        val result = screen(s, setOf("com.a", "com.b")).observe()
        assertFalse(result.nodes.any { it.text in listOf("hidden", "not a target") })
        assertTrue(result.nodes.any { it.text == "inherits permitted package" })
    }

    @Test fun anUnreadableFrontAppGivesItsNameButNoNodes() {
        val s = FakeSession(CcState.Active(targets), listOf(appWindow("com.b", AxNode(className = "android.widget.TextView", text = "x", packageName = "com.b"))))
        val state = screen(s, readable = emptySet()).observe()
        assertEquals("com.b", state.`package`)
        assertTrue(state.nodes.isEmpty())
    }

    @Test fun anEmptyTreeIsRetriedInsideThePort() {
        val s = FakeSession(CcState.Active(targets), listOf(appWindow("com.a")))
        s.firstReads.addLast(emptyList()); s.firstReads.addLast(emptyList())
        val sleeps = mutableListOf<Long>()
        val state = screen(s, setOf("com.a"), sleeps).observe()
        assertEquals(1, state.nodes.size)
        assertEquals(3, s.windowReads)
        assertEquals(listOf(ComputerControlScreen.EMPTY_RETRY_MS, ComputerControlScreen.EMPTY_RETRY_MS), sleeps)
        // Still empty after the retries: returned as it is, not invented.
        val empty = FakeSession(CcState.Active(targets), emptyList())
        assertTrue(screen(empty, setOf("com.a")).observe().nodes.isEmpty())
        assertEquals(1 + ComputerControlScreen.EMPTY_RETRIES, empty.windowReads)
    }

    @Test fun screenFailsWithTheSessionReasonWhenNotActive() {
        assertEquals(CcReason.NotStarted.message, assertFailsWith<PortException.Host> { screen(null, emptySet()).observe() }.reason)
        val blocked = FakeSession(CcState.Blocked(targets, CcReason.BlockedAuth, "com.a"))
        assertEquals(CcReason.BlockedAuth.message, assertFailsWith<PortException.Host> { screen(blocked, setOf("com.a")).observe() }.reason)
        assertEquals(0, blocked.windowReads)
    }

    @Test fun screenWaitsBrieflyForAStartingSession() {
        val s = FakeSession(CcState.Starting(targets), listOf(appWindow("com.a")))
        var polls = 0
        val waiting = ComputerControlScreen({ s }, { true }, { emptyList() }, { caps }, sleep = {
            if (++polls == 3) s.state = CcState.Active(targets)
        })
        assertEquals(1, waiting.observe().nodes.size)
        val stuck = FakeSession(CcState.Starting(targets))
        val sleeps = mutableListOf<Long>()
        assertFailsWith<PortException.Host> { screen(stuck, setOf("com.a"), sleeps).observe() }
        assertEquals(ComputerControlScreen.START_WAIT_MS, sleeps.sum())
    }
}
