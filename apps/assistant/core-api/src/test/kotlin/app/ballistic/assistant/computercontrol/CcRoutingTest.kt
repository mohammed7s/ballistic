package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.Act
import app.ballistic.assistant.core.AppPolicy
import app.ballistic.assistant.core.Bounds
import app.ballistic.assistant.core.Direction
import app.ballistic.assistant.core.ConfirmMode
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.Hands
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.HostCapabilities
import app.ballistic.assistant.core.IntentGrant
import app.ballistic.assistant.core.IntentOp
import app.ballistic.assistant.core.Observe
import app.ballistic.assistant.core.PolicyFile
import app.ballistic.assistant.core.Route
import app.ballistic.assistant.core.ScreenControl
import kotlin.test.*

class CcRoutingTest {
    private val on = AppPolicy(observe = Observe.Tree, act = Act.Full, route = Route.Default, screenControl = ScreenControl.On)
    private val fallback = HostCapabilities(listOf("open_app", "wait"), listOf("com.a", "com.c"))

    @Test fun olderRulesGainNothingWithoutTheScreenControlSwitch() {
        // Values written before Computer Control existed, shown then as "no extra access".
        val old = AppPolicy(observe = Observe.Tree, act = Act.Full, route = Route.Default)
        assertFalse(ScreenControlRules.mayRead(old, localModel = true))
        assertFalse(ScreenControlRules.mayAct(old))
        assertTrue(ScreenControlRules.mayRead(old.copy(screenControl = ScreenControl.On), localModel = false))
        assertTrue(ScreenControlRules.mayAct(old.copy(screenControl = ScreenControl.On)))
    }

    @Test fun otherRulesStillApplyWithScreenControlOn() {
        assertFalse(ScreenControlRules.mayAct(on.copy(act = Act.None)))
        assertFalse(ScreenControlRules.mayAct(on.copy(confirm = ConfirmMode.NeverAutomate)))
        assertFalse(ScreenControlRules.mayRead(on.copy(observe = Observe.Notifications), localModel = true))
        assertFalse(ScreenControlRules.mayRead(on.copy(observe = Observe.None), localModel = true))
        // A device-only app's tree never goes to a model that leaves the phone.
        assertFalse(ScreenControlRules.mayRead(on.copy(route = Route.DeviceOnly), localModel = false))
        assertTrue(ScreenControlRules.mayRead(on.copy(route = Route.DeviceOnly), localModel = true))
    }

    @Test fun targetsAreTheSwitchedOnAppsNeverBallisticAndAtMostSix() {
        var file = PolicyFile().with("app.ballistic.assistant", on).with("com.z", on).with("com.a", on).with("com.off", on.copy(screenControl = ScreenControl.Off))
        assertEquals(listOf("com.a", "com.z"), ScreenControlRules.targets(file, "app.ballistic.assistant"))
        assertNull(ScreenControlRules.targetProblem(ScreenControlRules.targets(file, "app.ballistic.assistant")))
        assertEquals(CcReason.NoTargets, ScreenControlRules.targetProblem(emptyList()))
        for (i in 1..5) file = file.with("com.n$i", on)
        assertEquals(7, ScreenControlRules.targets(file, "app.ballistic.assistant").size)
        assertEquals(CcReason.TooManyTargets, ScreenControlRules.targetProblem(ScreenControlRules.targets(file, "app.ballistic.assistant")))
    }

    @Test fun screenControlIsWrittenOnlyWhenOnAndOldFilesReadAsOff() {
        val old = """{"version":1,"apps":{"com.a":{"observe":"notifications","act":"navigate","route":"default","confirm":"default_list"}}}"""
        assertEquals(ScreenControl.Off, PolicyFile.decodeOrDefault(old).forApp("com.a").screenControl)
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(old),
            kotlinx.serialization.json.Json.parseToJsonElement(PolicyFile.decodeOrDefault(old).encode()), "an Off file keeps the old form")
        val withOn = PolicyFile().with("com.a", on).encode()
        assertTrue("\"screen_control\":\"on\"" in withOn)
        assertEquals(ScreenControl.On, PolicyFile.decodeOrDefault(withOn).forApp("com.a").screenControl)
    }

    @Test fun capabilitiesFollowTheSessionState() {
        val policy = PolicyFile().with("com.a", on).with("com.b", on.copy(act = Act.None))
        assertEquals(fallback, CcCapabilities.compute(CcState.Idle(), policy, fallback))
        assertTrue(CcCapabilities.compute(CcState.ConsentNeeded(listOf("com.a")), policy, fallback).operations.isEmpty())
        val active = CcCapabilities.compute(CcState.Active(listOf("com.a", "com.b")), policy, fallback)
        assertEquals(CcOps.OPERATIONS, active.operations)
        assertEquals(listOf("com.a"), active.appGrants)
        for (paused in listOf(CcState.Starting(listOf("com.a")), CcState.Blocked(listOf("com.a"), CcReason.Blocked, null))) {
            val caps = CcCapabilities.compute(paused, policy, fallback)
            assertTrue(caps.operations.isEmpty() && caps.appGrants.isEmpty(), "$paused")
        }
        assertEquals(setOf("com.a"), CcCapabilities.openable(CcState.Active(listOf("com.a", "com.b")), policy))
    }

    @Test fun hostAdaptersSwitchOnlyWhileASessionOwnsTheScreen() {
        var owns = false
        val calls = mutableListOf<String>()
        val hands = HostHands({ owns }, { calls += "cc"; HandsResult.Ok }, { calls += "intent"; HandsResult.Ok })
        hands.act(HandsOp.Back); owns = true; hands.act(HandsOp.Back)
        assertEquals(listOf("intent", "cc"), calls)
        assertTrue(CcState.ConsentNeeded(emptyList()).ownsScreen)
        assertTrue(CcState.Requesting.ownsScreen)
        assertTrue(CcState.Blocked(emptyList(), CcReason.Blocked, null).ownsScreen)
    }

    private class Recorder : Hands {
        val ops = mutableListOf<HandsOp>()
        override fun act(op: HandsOp): HandsResult { ops += op; return HandsResult.Ok }
    }

    @Test fun uiInputIsRecheckedAgainstTheFrontAppsRules() {
        var file = PolicyFile().with("com.a", on)
        var front: String? = "com.a"
        val ui = Recorder(); val other = Recorder()
        val hands = UiPolicyHands({ file }, { true }, { front }, ui, other, { error("no confirmation expected") })
        assertEquals(HandsResult.Ok, hands.act(HandsOp.Tap(1, 1)))
        front = "com.b"
        assertEquals(HandsResult.NotExecuted(CcReason.AppBlocked.message), hands.act(HandsOp.Tap(1, 1)))
        front = null
        assertEquals(HandsResult.NotExecuted(CcReason.NoFrontApp.message), hands.act(HandsOp.Back))
        front = "com.a"; file = file.with("com.a", on.copy(screenControl = ScreenControl.Off))
        assertIs<HandsResult.NotExecuted>(hands.act(HandsOp.Swipe(Bounds(0, 0, 9, 9), Direction.Up)))
        assertEquals(listOf<HandsOp>(HandsOp.Tap(1, 1)), ui.ops)
        assertTrue(other.ops.isEmpty())
    }

    @Test fun openAppNeedsScreenControlThenGoesThroughPolicyHands() {
        val file = PolicyFile().with("com.a", on).with("com.c", on.copy(screenControl = ScreenControl.Off))
        val ui = Recorder(); val other = Recorder()
        val hands = UiPolicyHands({ file }, { true }, { "com.a" }, ui, other, { error("unused") })
        assertEquals(HandsResult.NotExecuted(CcReason.AppBlocked.message), hands.act(HandsOp.OpenApp("com.c")))
        assertEquals(HandsResult.Ok, hands.act(HandsOp.OpenApp("com.a")))
        assertEquals(HandsResult.Ok, hands.act(HandsOp.Wait(5)))
        assertEquals(listOf(HandsOp.OpenApp("com.a"), HandsOp.Wait(5)), other.ops)
        assertTrue(ui.ops.isEmpty())
    }

    @Test fun withoutASessionEverythingGoesToTheUnprivilegedPath() {
        val ui = Recorder(); val other = Recorder()
        val timer = HandsOp.Intent("com.clock", IntentOp.SetTimer(60))
        val hands = UiPolicyHands({ PolicyFile() }, { false }, { error("not read") }, ui, other, { error("unused") })
        hands.act(HandsOp.Tap(3, 4)); hands.act(timer)
        assertEquals(listOf(HandsOp.Tap(3, 4), timer), other.ops)
        assertTrue(ui.ops.isEmpty())
    }

    @Test fun typedOperationsAreRefusedWhileASessionOwnsTheScreen() {
        val ui = Recorder(); val other = Recorder()
        val result = UiPolicyHands({ PolicyFile().with("com.clock", on) }, { true }, { "com.clock" }, ui, other, { error("unused") })
            .act(HandsOp.Intent("com.clock", IntentOp.SetTimer(60)))
        assertEquals(HandsResult.NotExecuted(CcReason.IntentDuringSession.message), result)
        assertTrue(ui.ops.isEmpty() && other.ops.isEmpty())
        val withIntents = fallback.copy(intents = listOf(IntentGrant("set_timer", "com.clock")))
        assertTrue(CcCapabilities.compute(CcState.Active(listOf("com.clock")), PolicyFile().with("com.clock", on), withIntents).intents.isEmpty())
        assertEquals(withIntents, CcCapabilities.compute(CcState.Idle(), PolicyFile(), withIntents))
    }

    @Test fun everyActionApprovalAndChangesDuringItBlockTheInput() {
        for (outcome in listOf("deny", "stop", "revoke", "front", "approve")) {
            var file = PolicyFile().with("com.a", on.copy(confirm = ConfirmMode.Always))
            var front = "com.a"
            var active = true
            val ui = Recorder()
            val hands = UiPolicyHands({ file }, { true }, { front }, ui, Recorder(), { request ->
                assertTrue(request.hard)
                assertEquals("com.a", request.app)
                assertEquals(HandsOp.Tap(7, 8), request.operation)
                when (outcome) {
                    "stop" -> active = false
                    "revoke" -> file = PolicyFile()
                    "front" -> front = "com.b"
                }
                if (outcome == "deny") ConfirmResult.Denied else ConfirmResult.Approved
            }, { active })
            val result = hands.act(HandsOp.Tap(7, 8))
            assertEquals(if (outcome == "approve") 1 else 0, ui.ops.size, outcome)
            if (outcome != "approve") assertIs<HandsResult.NotExecuted>(result)
        }
    }

    @Test fun navigationCannotActivateControlsOrType() {
        val navigation = on.copy(act = Act.Navigate)
        for (op in listOf(HandsOp.Tap(1, 2), HandsOp.LongPress(1, 2), HandsOp.Type(1, 2, "secret", false))) {
            val ui = Recorder()
            val hands = UiPolicyHands({ PolicyFile().with("com.a", navigation) }, { true }, { "com.a" }, ui, Recorder(), { error("no prompt") })
            assertIs<HandsResult.NotExecuted>(hands.act(op))
            assertTrue(ui.ops.isEmpty())
        }
        assertTrue(ScreenControlRules.mayPerform(navigation, HandsOp.Back))
    }

    @Test fun stoppedRunsDispatchNothing() {
        val ui = Recorder(); val other = Recorder()
        val result = UiPolicyHands({ PolicyFile().with("com.a", on) }, { true }, { "com.a" }, ui, other, { error("unused") }, { false })
            .act(HandsOp.Tap(1, 1))
        assertEquals(HandsResult.NotExecuted("task stopped"), result)
        assertTrue(ui.ops.isEmpty() && other.ops.isEmpty())
    }
}
