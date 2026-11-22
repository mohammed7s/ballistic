// Which adapter serves core while a Computer Control session does or does not exist, what the
// host then advertises, and the owner-rule re-check in front of every UI input. Wiring and
// enforcement only; nothing here chooses an action.
//
// While a session owns the screen (starting, active or paused), core observes the virtual
// display and its input goes there; open_app launches onto that display. Otherwise the
// unprivileged adapters serve exactly as before (NotificationScreen, IntentHands returning
// "needs Computer Control"). The two are never mixed in one observation: notification nodes
// have synthetic bounds, and once coordinate operations are advertised a tap on them would land
// on the virtual display.

package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.Confirm
import app.ballistic.assistant.core.ConfirmMode
import app.ballistic.assistant.core.ConfirmRequest
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.Hands
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.HostCapabilities
import app.ballistic.assistant.core.PolicyFile
import app.ballistic.assistant.core.Screen
import app.ballistic.assistant.core.ScreenState
import app.ballistic.assistant.core.operationId

/** Session adapter while a session owns the screen, else the fallback. */
class HostScreen(private val ownsScreen: () -> Boolean, private val session: Screen, private val fallback: Screen) : Screen {
    override fun observe(): ScreenState = if (ownsScreen()) session.observe() else fallback.observe()
}

/** Session adapter while a session owns the screen, else the fallback. */
class HostHands(private val ownsScreen: () -> Boolean, private val session: Hands, private val fallback: Hands) : Hands {
    override fun act(op: HandsOp): HandsResult = if (ownsScreen()) session.act(op) else fallback.act(op)
}

object CcCapabilities {
    /**
     * What core may ask for now (SPEC.md §5.5). No session: the fallback's operations and
     * grants, unchanged. Active: the session's operations, in the targets the owner still lets
     * Ballistic act in. Starting, consent or paused: nothing executable.
     */
    fun compute(state: CcState, policy: PolicyFile, fallback: HostCapabilities): HostCapabilities = when {
        !state.ownsScreen -> fallback
        state is CcState.Active -> HostCapabilities(
            operations = CcOps.OPERATIONS,
            appGrants = state.targets.filter { ScreenControlRules.mayAct(policy.forApp(it)) },
        )
        else -> HostCapabilities(operations = emptyList(), appGrants = emptyList())
    }

    /** Apps core may plan to open while the session owns the screen: its targets the owner lets Ballistic act in. */
    fun openable(state: CcState, policy: PolicyFile): Set<String> =
        if (state is CcState.Active) state.targets.filter { ScreenControlRules.mayAct(policy.forApp(it)) }.toSet() else emptySet()
}

/**
 * Re-checks the owner's rules at dispatch for input into the session's front app, the same way
 * PolicyHands does for open_app: rule allows it, "every action" asks first, then the rule, the
 * Stop state and the front app are read again before anything is sent. open_app is checked for
 * Screen control and then handed to [other] (PolicyHands), which applies its own checks; wait
 * goes straight to [other]. Without a session everything goes to [other] unchanged.
 */
class UiPolicyHands(
    private val policy: () -> PolicyFile,
    private val ownsScreen: () -> Boolean,
    /** Front application package on the session display, read fresh (AxTree.frontPackage). */
    private val frontPackage: () -> String?,
    private val ui: Hands,
    private val other: Hands,
    private val confirm: Confirm,
    private val canDispatch: () -> Boolean = { true },
) : Hands {
    override fun act(op: HandsOp): HandsResult {
        if (!canDispatch()) return HandsResult.NotExecuted("task stopped")
        if (op is HandsOp.Wait || !ownsScreen()) return other.act(op)
        // Not advertised during a session (CcCapabilities); refused here too, before any policy prompt.
        if (op is HandsOp.Intent) return CcOps.notExecuted(CcReason.IntentDuringSession)
        if (op is HandsOp.OpenApp) {
            if (!ScreenControlRules.mayAct(policy().forApp(op.`package`))) return CcOps.notExecuted(CcReason.AppBlocked)
            return other.act(op)
        }
        val pkg = frontPackage() ?: return CcOps.notExecuted(CcReason.NoFrontApp)
        val before = policy().forApp(pkg)
        if (!ScreenControlRules.mayPerform(before, op)) return CcOps.notExecuted(CcReason.AppBlocked)
        if (before.confirm == ConfirmMode.Always) {
            val approval = confirm.ask(ConfirmRequest(op, pkg, op.operationId.replace('_', ' '), pkg, hard = true,
                reason = "Owner requires approval for every action in this app"))
            if (approval != ConfirmResult.Approved) return HandsResult.NotExecuted("owner did not approve")
        }
        val after = policy().forApp(pkg)
        if (!canDispatch() || after != before || !ScreenControlRules.mayPerform(after, op) || frontPackage() != pkg)
            return HandsResult.NotExecuted("task stopped, app access changed or another app came to the front")
        return ui.act(op)
    }
}

/** The first observation/dispatch pins the display for the whole run, even after revocation. */
class CcRunRoute {
    private var run: String? = null
    private var controlled = false
    @Synchronized fun ownsScreen(runId: String?, sessionOwnsScreen: Boolean): Boolean {
        if (runId == null) return sessionOwnsScreen
        if (runId != run) {
            run = runId
            controlled = sessionOwnsScreen
        }
        return controlled
    }
}

/** Callers serialize transitions; cancelling invalidates callbacks even after a new request. */
class CcGeneration {
    private var value = 0L
    fun next(): Long = ++value
    fun invalidate() { ++value }
    fun isCurrent(request: Long): Boolean = request == value
}
