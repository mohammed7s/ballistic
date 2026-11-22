package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.Bounds
import app.ballistic.assistant.core.Direction
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.IntentOp
import kotlin.test.*

class CcOpsTest {
    private val sleeps = mutableListOf<Long>()
    private fun run(op: HandsOp, input: FakeInput = FakeInput()): Pair<HandsResult, List<String>> =
        CcOps.execute(op, input, 1080, 2400) { sleeps += it } to input.calls

    @Test fun pointerOpsGoToTheSessionInPixels() {
        assertEquals(HandsResult.Ok to listOf("tap 540,1200"), run(HandsOp.Tap(540, 1200)))
        assertEquals(HandsResult.Ok to listOf("longPress 0,0"), run(HandsOp.LongPress(0, 0)))
        assertEquals(HandsResult.Ok to listOf("back"), run(HandsOp.Back))
    }

    @Test fun coordinatesOutsideTheDisplayAreNotSent() {
        for (op in listOf(HandsOp.Tap(1080, 10), HandsOp.Tap(-1, 10), HandsOp.LongPress(10, 2400), HandsOp.Type(5000, 1, "x", false))) {
            val (result, calls) = run(op)
            assertEquals(HandsResult.NotExecuted(CcReason.OutsideDisplay.message), result, "$op")
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun typeTapsWaitsThenCommitsTextWithoutReplacing() {
        assertEquals(HandsResult.Ok to listOf("tap 100,200", "insertText 'hello' replace=false commit=false"), run(HandsOp.Type(100, 200, "hello", false)))
        assertEquals(listOf(CcOps.TYPE_FOCUS_DELAY_MS), sleeps)
        assertEquals("insertText 'hi' replace=false commit=true", run(HandsOp.Type(1, 1, "hi", true)).second.last())
    }

    @Test fun scrollMovesTheFingerAgainstTheDirectionAndSwipeWithIt() {
        val area = Bounds(0, 400, 1080, 2000)
        // Content below comes into view: finger from centre up to the top edge (harness geometry).
        assertEquals(listOf("swipe 540,1200->540,400"), run(HandsOp.Scroll(area, Direction.Down)).second)
        assertEquals(listOf("swipe 540,1200->540,2000"), run(HandsOp.Scroll(area, Direction.Up)).second)
        assertEquals(listOf("swipe 540,1200->1079,1200"), run(HandsOp.Scroll(area, Direction.Left)).second, "x2 == width is clamped")
        assertEquals(listOf("swipe 540,1200->0,1200"), run(HandsOp.Swipe(area, Direction.Left)).second)
        assertEquals(listOf("swipe 540,1200->540,2000"), run(HandsOp.Swipe(area, Direction.Down)).second)
    }

    @Test fun degenerateAreasAreNotSent() {
        for (area in listOf(Bounds(10, 10, 10, 50), Bounds(10, 50, 60, 50), Bounds(2000, 3000, 2100, 3100))) {
            val (result, calls) = run(HandsOp.Swipe(area, Direction.Up))
            assertEquals(HandsResult.NotExecuted(CcReason.EmptyArea.message), result, "$area")
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun thereIsNoHomeAction() {
        val (result, calls) = run(HandsOp.Home)
        assertEquals(HandsResult.NotExecuted(CcReason.NoHome.message), result)
        assertTrue(calls.isEmpty())
        assertFalse("home" in CcOps.OPERATIONS)
    }

    @Test fun typedOperationsDoNotRunInsideASession() {
        val (result, calls) = run(HandsOp.Intent("com.clock", IntentOp.SetTimer(60)))
        assertEquals(HandsResult.NotExecuted(CcReason.IntentDuringSession.message), result)
        assertTrue(calls.isEmpty())
    }

    @Test fun openAppLaunchesThePackageOrComponent() {
        assertEquals(listOf("launch com.a -"), run(HandsOp.OpenApp("com.a")).second)
        assertEquals(listOf("launch com.a com.a.Main"), run(HandsOp.OpenApp("com.a", "com.a.Main")).second)
    }

    @Test fun waitIsBoundedAndSendsNothing() {
        assertEquals(HandsResult.Ok to emptyList<String>(), run(HandsOp.Wait(60_000)))
        assertEquals(listOf(10_000L), sleeps)
    }

    @Test fun refusalBeforeAnyInputIsNotExecutedAndLaterFailureIsUnknown() {
        val refused = run(HandsOp.OpenApp("com.b"), FakeInput(mapOf("launch" to IllegalArgumentException("not a target package")))).first
        assertIs<HandsResult.NotExecuted>(refused)
        assertIs<HandsResult.NotExecuted>(run(HandsOp.Tap(1, 1), FakeInput(mapOf("tap" to SecurityException("revoked")))).first)
        // A remote failure of unknown kind: the input may have gone through.
        assertIs<HandsResult.Error>(run(HandsOp.Tap(1, 1), FakeInput(mapOf("tap" to RuntimeException("DeadSystemException")))).first)
        // The field was tapped before the text failed, so the effect is unknown even for a refusal.
        val (typed, calls) = run(HandsOp.Type(1, 1, "x", true), FakeInput(mapOf("insertText" to IllegalStateException("no input connection"))))
        assertIs<HandsResult.Error>(typed)
        assertEquals(listOf("tap 1,1"), calls)
    }
}
