// Hands port on a Computer Control session (SPEC.md §5.2): tap, long press, type (commitText),
// scroll, swipe, back and open_app, all on the session's hidden virtual display.
//
// Platform API used (extension library com.android.extensions.computercontrol, API file
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/libs/computercontrol/api_extension/current.txt):
//   ComputerControlSession.tap(int x, int y), longPress(int, int), swipe(int, int, int, int)
//     pixel coordinates on the virtual display; x < getDisplaySize().width, y < height, else
//     IllegalArgumentException (the javadoc still says "relative"; the code validates pixels)
//   ComputerControlSession.insertText(String text, boolean replaceExisting, boolean commit)
//     InputConnection.commitText on the focused field; commit runs the editor action or Enter
//   ComputerControlSession.performAction(ACTION_GO_BACK)  the only action; there is no home
//   ComputerControlSession.launchApplication(String) / (ComponentName)  target packages only;
//     it returns before the app draws, so open_app then waits a bounded time for its tree
// The session runs targets on its own trusted virtual display with its own virtual touchscreen
// and dpad; nothing reaches the owner's display, so Ballistic's confirmation window (which stays
// on the default display) cannot receive this input (ConfirmActivity, InjectedInputFilter).
//
// This class translates and reports; it decides nothing. Policy is re-checked in front of it by
// UiPolicyHands, and core has already applied its own policy and confirmation. Pure Kotlin: the
// platform session is behind [CcSessionView], so the translation is tested with fakes.

package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.Hands
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult

/** What the adapters need from a running session. Implemented by the app over the extension library. */
interface CcSessionView {
    val state: CcState
    val input: CcInput
    /** getDisplaySize() of the session's virtual display. */
    val width: Int
    val height: Int
    /** getAccessibilityWindows(), copied; empty unless the session is active. */
    fun windows(): List<AxWindow>
    /** AxTree.frontPackage of the current windows; hosts may read only the window roots. */
    fun frontPackage(): String? = AxTree.frontPackage(windows())
}

/** Input may have reached the session since the Screen port last observed it. Timing only. */
class CcInputMarker {
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    fun mark() = pending.set(true)
    fun consume(): Boolean = pending.getAndSet(false)
}

class ComputerControlHands(
    private val session: () -> CcSessionView?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val inputs: CcInputMarker = CcInputMarker(),
) : Hands {
    override fun act(op: HandsOp): HandsResult {
        val s = session() ?: return CcOps.notExecuted(CcReason.NotStarted)
        if (op is HandsOp.Wait) return CcOps.execute(op, s.input, s.width, s.height, sleep)
        val state = s.state
        state.notActiveReason?.let { return CcOps.notExecuted(it) }
        val targets = (state as CcState.Active).targets
        if (op is HandsOp.OpenApp && op.`package` !in targets) return CcOps.notExecuted(CcReason.NotTarget)
        // Re-check before every sub-step, especially after Type's focus delay. A stale
        // view cannot keep typing after Stop, blocking, or replacement of its session.
        val front = if (op is HandsOp.OpenApp) null else s.frontPackage()
        val guarded = object : CcInput {
            fun checkActive() {
                check(session() === s && s.state is CcState.Active) { "session ended or blocked" }
                if (op !is HandsOp.OpenApp) check(s.frontPackage() == front) { "front app changed" }
            }
            override fun tap(x: Int, y: Int) { checkActive(); s.input.tap(x, y) }
            override fun longPress(x: Int, y: Int) { checkActive(); s.input.longPress(x, y) }
            override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) { checkActive(); s.input.swipe(fromX, fromY, toX, toY) }
            override fun insertText(text: String, replaceExisting: Boolean, commit: Boolean) { checkActive(); s.input.insertText(text, replaceExisting, commit) }
            override fun back() { checkActive(); s.input.back() }
            override fun launch(`package`: String, activity: String?) { checkActive(); s.input.launch(`package`, activity) }
        }
        val result = CcOps.execute(op, guarded, s.width, s.height, sleep)
        if (result !is HandsResult.NotExecuted) inputs.mark()
        if (op !is HandsOp.OpenApp || result != HandsResult.Ok) return result
        return awaitLaunched(s, op.`package`)
    }

    /**
     * launchApplication returns before the app has drawn. Until the launched app is the front
     * application window and has a tree, an observation is blank (no front package, no nodes),
     * and core checks the next action's app grant against that blank package (27 Nov pilot
     * run 3). Wait a bounded time for it; if it never shows, the launch was sent but its effect
     * is unknown, so report Error, never a blank screen or a success. A session that ends or
     * pauses meanwhile returns Ok: the next observation reports that state.
     */
    private fun awaitLaunched(s: CcSessionView, pkg: String): HandsResult {
        var waited = 0L
        while (true) {
            if (session() !== s || s.state !is CcState.Active) return HandsResult.Ok
            if (launchedTreeShown(s.windows(), pkg)) return HandsResult.Ok
            if (waited >= LAUNCH_WAIT_MS) return HandsResult.Error(CcReason.LaunchNotShown.message)
            try {
                sleep(LAUNCH_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return HandsResult.Error("interrupted after input was sent")
            }
            waited += LAUNCH_POLL_MS
        }
    }

    companion object {
        const val LAUNCH_WAIT_MS = 10_000L
        const val LAUNCH_POLL_MS = 200L

        /** [pkg] is the front application window and at least one of its windows has nodes. */
        fun launchedTreeShown(windows: List<AxWindow>, pkg: String): Boolean =
            AxTree.frontPackage(windows) == pkg &&
                AxTree.toNodes(windows.filter { it.kind == AxWindowKind.Application && it.root?.packageName == pkg }).isNotEmpty()
    }
}
