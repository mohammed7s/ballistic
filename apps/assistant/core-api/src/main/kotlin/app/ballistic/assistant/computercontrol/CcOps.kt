// HandsOp -> Computer Control calls. Pure translation: coordinates are core's, resolved from the
// tree this session returned, so they are already in the virtual display's pixel space.
// Geometry and timing copy the harness (harness/core_bridge.py CoreHost.act) so a step means
// the same thing on the phone as on the emulator.
//
// Result rules (Types.kt HandsResult):
//   NotExecuted  the host knows nothing reached the session: checks before the first call, and
//                IllegalArgumentException / SecurityException / IllegalStateException from the
//                first call (the client and system_server validate before injecting).
//   Error        a call failed after an earlier call of the same op went through (type = tap,
//                then text), or a failure of unknown kind; core treats the effect as unknown.
//   Ok           every call returned. This is an acknowledgment only: the platform drops input
//                silently when the session stopped being active in between (AOSP
//                ComputerControlSessionImpl.shouldDisallowInteractions), and insertText does
//                nothing without a focused InputConnection. Core observes before it believes.

package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.Bounds
import app.ballistic.assistant.core.Direction
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult

/** The session's input calls (ComputerControlSession in the extension library). */
interface CcInput {
    fun tap(x: Int, y: Int)
    fun longPress(x: Int, y: Int)
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int)
    /** insertText(text, replaceExisting, commit): commitText on the focused field. */
    fun insertText(text: String, replaceExisting: Boolean, commit: Boolean)
    /** performAction(ACTION_GO_BACK). */
    fun back()
    /** launchApplication(packageName), or launchApplication(ComponentName) when [activity] is set. */
    fun launch(`package`: String, activity: String?)
}

object CcOps {
    /** Operation ids this translation executes (core/src/capabilities.rs). No `home`: the API has none. */
    val OPERATIONS = listOf("open_app", "tap", "long_press", "type", "scroll", "swipe", "back", "wait")

    /** harness: tap the field, sleep 1 s, then type. */
    const val TYPE_FOCUS_DELAY_MS = 1_000L
    private const val MAX_WAIT_MS = 10_000

    fun execute(op: HandsOp, input: CcInput, width: Int, height: Int, sleep: (Long) -> Unit): HandsResult {
        fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height
        return when (op) {
            is HandsOp.Tap ->
                if (!inside(op.x, op.y)) notExecuted(CcReason.OutsideDisplay) else dispatch({ input.tap(op.x, op.y) })
            is HandsOp.LongPress ->
                if (!inside(op.x, op.y)) notExecuted(CcReason.OutsideDisplay) else dispatch({ input.longPress(op.x, op.y) })
            is HandsOp.Type ->
                if (!inside(op.x, op.y)) notExecuted(CcReason.OutsideDisplay)
                else dispatch(
                    { input.tap(op.x, op.y) },
                    { sleep(TYPE_FOCUS_DELAY_MS) },
                    // Appends at the cursor, as the harness's `adb input text` does; never replaces
                    // what the field holds. commit = the field's editor action, else Enter.
                    { input.insertText(op.text, false, op.submit) },
                )
            is HandsOp.Scroll -> drag(op.area, opposite(op.direction), width, height, input)
            is HandsOp.Swipe -> drag(op.area, op.direction, width, height, input)
            HandsOp.Back -> dispatch({ input.back() })
            HandsOp.Home -> notExecuted(CcReason.NoHome)
            // A typed operation would open on the default display, outside what the session observes.
            is HandsOp.Intent -> notExecuted(CcReason.IntentDuringSession)
            is HandsOp.OpenApp -> dispatch({ input.launch(op.`package`, op.activity) })
            is HandsOp.Wait -> {
                sleep(op.millis.coerceIn(0, MAX_WAIT_MS).toLong())
                HandsResult.Ok
            }
        }
    }

    /**
     * harness: from the area's centre to its edge in [fingerDirection]. Scroll moves the finger
     * against the requested direction so content lying that way comes into view; swipe moves it
     * that way. Points are clamped onto the display (the platform rejects x == width).
     */
    fun dragPoints(area: Bounds, fingerDirection: Direction, width: Int, height: Int): IntArray? {
        if (area.x2 <= area.x1 || area.y2 <= area.y1 || width <= 0 || height <= 0) return null
        val cx = (area.x1 + area.x2) / 2
        val cy = (area.y1 + area.y2) / 2
        val (ex, ey) = when (fingerDirection) {
            Direction.Up -> cx to area.y1
            Direction.Down -> cx to area.y2
            Direction.Left -> area.x1 to cy
            Direction.Right -> area.x2 to cy
        }
        val points = intArrayOf(cx.coerceIn(0, width - 1), cy.coerceIn(0, height - 1), ex.coerceIn(0, width - 1), ey.coerceIn(0, height - 1))
        return if (points[0] == points[2] && points[1] == points[3]) null else points
    }

    private fun drag(area: Bounds, fingerDirection: Direction, width: Int, height: Int, input: CcInput): HandsResult {
        val p = dragPoints(area, fingerDirection, width, height) ?: return notExecuted(CcReason.EmptyArea)
        return dispatch({ input.swipe(p[0], p[1], p[2], p[3]) })
    }

    private fun opposite(d: Direction) = when (d) {
        Direction.Up -> Direction.Down
        Direction.Down -> Direction.Up
        Direction.Left -> Direction.Right
        Direction.Right -> Direction.Left
    }

    fun notExecuted(reason: CcReason) = HandsResult.NotExecuted(reason.message)

    /** Runs [steps] in order; see the result rules at the top of this file. */
    private fun dispatch(vararg steps: () -> Unit): HandsResult {
        var dispatched = false
        for (step in steps) {
            try {
                step()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return if (dispatched) HandsResult.Error("interrupted after input was sent") else HandsResult.NotExecuted("interrupted")
            } catch (e: RuntimeException) {
                val rejected = e is IllegalArgumentException || e is IllegalStateException || e is SecurityException
                val what = e::class.java.simpleName
                return if (!dispatched && rejected) HandsResult.NotExecuted("Computer Control refused the input ($what)")
                else HandsResult.Error("Computer Control call failed ($what)")
            }
            dispatched = true
        }
        return HandsResult.Ok
    }
}
