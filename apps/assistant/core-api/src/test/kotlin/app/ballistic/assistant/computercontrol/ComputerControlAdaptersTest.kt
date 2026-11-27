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
        val s = FakeSession(CcState.Active(targets), listOf(appWindow("com.b")))
        val hands = ComputerControlHands({ s }, sleep = {})
        assertEquals(HandsResult.NotExecuted(CcReason.NotTarget.message), hands.act(HandsOp.OpenApp("com.other")))
        assertEquals(HandsResult.Ok, hands.act(HandsOp.OpenApp("com.b")))
        assertEquals(listOf("launch com.b -"), s.input.calls)
    }

    @Test fun openAppWaitsForTheLaunchedAppsTree() {
        // 27 Nov pilot run 3: the first observation after launch was blank. The launch now
        // returns once the launched app is in front with nodes, here after two empty reads.
        val s = FakeSession(CcState.Active(targets), listOf(appWindow("com.a")))
        s.firstReads.addLast(emptyList()); s.firstReads.addLast(listOf(appWindow("com.b")))
        val sleeps = mutableListOf<Long>()
        assertEquals(HandsResult.Ok, ComputerControlHands({ s }, sleep = { sleeps += it }).act(HandsOp.OpenApp("com.a")))
        assertEquals(listOf(ComputerControlHands.LAUNCH_POLL_MS, ComputerControlHands.LAUNCH_POLL_MS), sleeps)
        assertEquals(listOf("launch com.a -"), s.input.calls, "never launched again")
    }

    @Test fun aLaunchThatNeverShowsIsUnknownNotBlank() {
        val s = FakeSession(CcState.Active(targets), listOf(appWindow("com.b")))
        var waited = 0L
        val result = ComputerControlHands({ s }, sleep = { waited += it }).act(HandsOp.OpenApp("com.a"))
        assertEquals(HandsResult.Error(CcReason.LaunchNotShown.message), result)
        assertEquals(ComputerControlHands.LAUNCH_WAIT_MS, waited, "bounded")
        assertEquals(listOf("launch com.a -"), s.input.calls)
    }

    @Test fun aSessionEndingDuringTheLaunchWaitStopsWaiting() {
        val s = FakeSession(CcState.Active(targets))
        var sleeps = 0
        val result = ComputerControlHands({ s }, sleep = { sleeps++; s.state = CcState.Idle(CcReason.Closed) }).act(HandsOp.OpenApp("com.a"))
        assertEquals(HandsResult.Ok, result, "the launch call returned; the next observation reports the closed session")
        assertEquals(1, sleeps)
    }

    @Test fun launchedTreeNeedsTheLaunchedPackageInFront() {
        assertFalse(ComputerControlHands.launchedTreeShown(emptyList(), "com.a"))
        assertFalse(ComputerControlHands.launchedTreeShown(listOf(appWindow("com.b")), "com.a"))
        assertFalse(ComputerControlHands.launchedTreeShown(listOf(appWindow("com.b"), appWindow("com.a", active = false)), "com.a"))
        assertFalse(ComputerControlHands.launchedTreeShown(listOf(AxWindow(AxWindowKind.Application, active = true, root = null)), "com.a"))
        assertTrue(ComputerControlHands.launchedTreeShown(listOf(appWindow("com.a")), "com.a"))
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
        assertEquals(4, s.windowReads, "two empty reads, the tree, one settling read")
        assertEquals(listOf(ComputerControlScreen.EMPTY_RETRY_MS, ComputerControlScreen.EMPTY_RETRY_MS, ComputerControlScreen.SETTLE_MS), sleeps)
        // Still empty after the retries: returned as it is, not invented.
        val empty = FakeSession(CcState.Active(targets), emptyList())
        assertTrue(screen(empty, setOf("com.a")).observe().nodes.isEmpty())
        assertEquals(1 + ComputerControlScreen.EMPTY_RETRIES, empty.windowReads)
    }

    private fun tab(selected: Boolean, text: String = "Stopwatch") = appWindow("com.a",
        AxNode(className = "android.widget.LinearLayout", contentDescription = "Stopwatch", text = text, packageName = "com.a",
            bounds = Bounds(0, 0, 100, 100), clickable = !selected, selected = selected, visibleToUser = true))

    @Test fun aChangingSkeletonIsReadUntilTwoReadsAgree() {
        val s = FakeSession(CcState.Active(targets), listOf(tab(selected = true)))
        s.firstReads.addLast(listOf(tab(selected = false)))
        val sleeps = mutableListOf<Long>()
        val state = screen(s, setOf("com.a"), sleeps).observe()
        assertTrue(state.nodes[1].flags.selected, "the settled tab state, not the transitional read")
        assertEquals(listOf(ComputerControlScreen.SETTLE_MS, 2 * ComputerControlScreen.SETTLE_MS), sleeps)
    }

    @Test fun changingTextAloneSettlesAndAnUnsettledScreenIsBounded() {
        var n = 0
        val ticking = object : CcSessionView by FakeSession(CcState.Active(targets)) {
            override fun windows() = listOf(tab(selected = true, text = "00:0${n++}"))
        }
        val sleeps = mutableListOf<Long>()
        ComputerControlScreen({ ticking }, { true }, { emptyList() }, { caps }, sleep = { sleeps += it }).observe()
        assertEquals(listOf(ComputerControlScreen.SETTLE_MS), sleeps, "a running stopwatch's digits do not unsettle it")
        var flip = false
        val flapping = object : CcSessionView by FakeSession(CcState.Active(targets)) {
            override fun windows() = listOf(tab(selected = flip).also { flip = !flip })
        }
        val waits = mutableListOf<Long>()
        ComputerControlScreen({ flapping }, { true }, { emptyList() }, { caps }, sleep = { waits += it }).observe()
        assertEquals(ComputerControlScreen.SETTLE_SAMPLES, waits.size)
        assertTrue(waits.all { it <= ComputerControlScreen.SETTLE_MAX_MS })
    }

    @Test fun anUnchangedTreeAfterInputIsReadAgainBounded() {
        val s = FakeSession(CcState.Active(targets), listOf(tab(selected = false)))
        val inputs = CcInputMarker()
        val sleeps = mutableListOf<Long>()
        val port = ComputerControlScreen({ s }, { true }, { emptyList() }, { caps }, sleep = { sleeps += it }, inputs = inputs)
        port.observe()
        // An input, then the app redraws only after the first read: the redrawn tree is returned.
        inputs.mark()
        s.firstReads.addLast(listOf(tab(selected = false))); s.firstReads.addLast(listOf(tab(selected = false)))
        s.windowList = listOf(tab(selected = true))
        sleeps.clear()
        assertTrue(port.observe().nodes[1].flags.selected)
        assertTrue(ComputerControlScreen.UNCHANGED_RETRY_MS in sleeps)
        // An input after which the screen really stays the same: returned after the bounded re-reads.
        inputs.mark()
        sleeps.clear()
        assertTrue(port.observe().nodes[1].flags.selected)
        assertEquals(ComputerControlScreen.UNCHANGED_RETRIES, sleeps.count { it == ComputerControlScreen.UNCHANGED_RETRY_MS })
        // No input: no re-read for an unchanged screen.
        sleeps.clear()
        port.observe()
        assertEquals(0, sleeps.count { it == ComputerControlScreen.UNCHANGED_RETRY_MS })
    }

    @Test fun handsMarkInputOnlyWhenSomethingMayHaveBeenSent() {
        val s = FakeSession(CcState.Active(targets), listOf(appWindow("com.a")))
        val inputs = CcInputMarker()
        val hands = ComputerControlHands({ s }, sleep = {}, inputs = inputs)
        hands.act(HandsOp.OpenApp("com.other"))
        assertFalse(inputs.consume(), "refused before dispatch")
        hands.act(HandsOp.Tap(1, 1))
        assertTrue(inputs.consume())
        assertFalse(inputs.consume(), "consumed once")
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
