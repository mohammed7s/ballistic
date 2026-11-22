// Where Computer Control stands on this phone, as the host knows it, and the owner rules that
// decide which apps the session may read and act in. No planning here: these are facts reported
// by the platform (library present, permission granted, role held, session lifecycle) and the
// owner's own per-app switches. Every reason a Computer Control operation does not run is one of
// [CcReason], so core and the action log get the same wording every time.

package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.Act
import app.ballistic.assistant.core.AppAccess
import app.ballistic.assistant.core.AppPolicy
import app.ballistic.assistant.core.Observe
import app.ballistic.assistant.core.PolicyFile
import app.ballistic.assistant.core.Route
import app.ballistic.assistant.core.ScreenControl

/** Why a Computer Control operation or observation did not happen. [message] goes to core. */
enum class CcReason(val message: String) {
    NoLibrary("needs Computer Control: this phone has no Computer Control library"),
    NeedsVersion5("needs Computer Control extension version 5 or later"),
    NotVisible("keep Ballistic visible to start screen control"),
    NoPermission("needs Computer Control: ACCESS_COMPUTER_CONTROL is not granted to Ballistic"),
    NoNotifications("needs Computer Control: notification permission is not granted"),
    NotAssistant("needs Computer Control: Ballistic is not the digital assistant app"),
    NotAllowed("needs Computer Control: the system does not allow Ballistic to start a session"),
    NotStarted("needs Computer Control: no session is running; start one in Ballistic"),
    ConsentNeeded("needs Computer Control: target app consent is needed; review it in Ballistic"),
    ConsentRefused("needs Computer Control: target app consent was not given"),
    Locked("needs Computer Control: the phone is locked"),
    Busy("needs Computer Control: another Computer Control session is running"),
    NoTargets("needs Computer Control: no app has Screen control turned on"),
    TooManyTargets("needs Computer Control: more than six apps have Screen control turned on"),
    NotAutomatable("needs Computer Control: the system does not allow automating an app with Screen control on"),
    Starting("Computer Control session is starting"),
    BlockedSecure("Computer Control session blocked: the app is showing secure content"),
    BlockedLaunch("Computer Control session blocked: the app opened something outside the session"),
    BlockedAuth("Computer Control session blocked: the app asked for authentication"),
    Blocked("Computer Control session was blocked"),
    Closed("Computer Control session ended"),
    Failed("Computer Control session could not start"),
    NotTarget("app is not in this Computer Control session"),
    NoFrontApp("no app is open in the Computer Control session"),
    AppBlocked("Screen control for this app is off or blocked in Ballistic settings"),
    OutsideDisplay("coordinates are outside the Computer Control display"),
    EmptyArea("scroll or swipe area has no size"),
    NoHome("Computer Control has no home action"),
    IntentDuringSession("typed operations open on the owner's screen, not the Computer Control display; not available during a session"),
}

/** Platform session lifecycle (LifecycleCallback, plus the request phases before it). */
sealed interface CcState {
    /** Nothing requested, or the last session is gone. [reason] says why, when known. */
    data class Idle(val reason: CcReason? = null) : CcState
    /** requestSession sent; no answer yet. */
    data object Requesting : CcState
    /** onSessionPending: the platform's consent screen must be shown to the owner. */
    data class ConsentNeeded(val targets: List<String>) : CcState
    /** onSessionCreated, before LifecycleCallback.onActive. */
    data class Starting(val targets: List<String>) : CcState
    data class Active(val targets: List<String>) : CcState
    /** LifecycleCallback.onBlocked: input and the tree are withheld by the platform. */
    data class Blocked(val targets: List<String>, val reason: CcReason, val blockingPackage: String?) : CcState
}

/** A session exists (requested, starting, running or paused): the session owns the screen and input. */
val CcState.ownsScreen: Boolean
    get() = this !is CcState.Idle

/** The reason an operation cannot run in this state; null when Active. */
val CcState.notActiveReason: CcReason?
    get() = when (this) {
        is CcState.Active -> null
        is CcState.Idle -> reason ?: CcReason.NotStarted
        CcState.Requesting -> CcReason.Starting
        is CcState.ConsentNeeded -> CcReason.ConsentNeeded
        is CcState.Starting -> CcReason.Starting
        is CcState.Blocked -> reason
    }

/** What the platform offers now, before any session (read by the app; see ComputerControlPlatform). */
data class CcPlatform(
    val library: Boolean,
    val permission: Boolean,
    val notifications: Boolean,
    val assistantRole: Boolean,
    /** Extension API version (ComputerControlExtensions.getVersion), 0 without the library. */
    val version: Int = 0,
    /** Versioned v5 availability; unknown/failure is denied, never treated as permission. */
    val sessionCreationAvailable: Boolean? = null,
) {
    /** The first missing precondition, in the order the owner would fix them; null when none. */
    val missing: CcReason?
        get() = when {
            !library -> CcReason.NoLibrary
            version < 5 -> CcReason.NeedsVersion5
            !assistantRole -> CcReason.NotAssistant
            !permission -> CcReason.NoPermission
            !notifications -> CcReason.NoNotifications
            sessionCreationAvailable != true -> CcReason.NotAllowed
            else -> null
        }

    companion object {
        val NONE = CcPlatform(library = false, permission = false, notifications = false, assistantRole = false)
    }
}

/**
 * The owner's rules for Computer Control. Screen control is a separate, explicit per-app switch
 * (AppPolicy.screenControl): an older policy file whose observe/act values were written before
 * Computer Control existed gains nothing until the owner turns it on for that app.
 */
object ScreenControlRules {
    /** Platform constant MAX_TARGET_PACKAGES (ComputerControlSessionParams, enforced for v5). */
    const val MAX_TARGETS = 6

    /** The session's target list: apps with Screen control on, never Ballistic itself, sorted. */
    fun targets(policy: PolicyFile, ownPackage: String): List<String> =
        policy.apps.filter { (pkg, p) -> pkg != ownPackage && p.screenControl == ScreenControl.On }.keys.sorted()

    /** Null when [targets] can start a session, else why not. */
    fun targetProblem(targets: List<String>): CcReason? = when {
        targets.isEmpty() -> CcReason.NoTargets
        targets.size > MAX_TARGETS -> CcReason.TooManyTargets
        else -> null
    }

    /**
     * The app's tree may be returned to core: Screen control on, screen-tree reading granted, and the tree
     * stays on the phone when the app is device-only and the planner is not (the same route rule
     * as notifications, AppAccess.canReadNotifications).
     */
    fun mayRead(policy: AppPolicy, localModel: Boolean): Boolean =
        policy.screenControl == ScreenControl.On && policy.observe in listOf(Observe.Tree, Observe.TreeScreenshot) &&
            (localModel || policy.route != Route.DeviceOnly)

    /** Navigation cannot type or activate an arbitrary control (which might submit data). */
    fun mayPerform(policy: AppPolicy, op: HandsOp): Boolean = mayAct(policy) && when (op) {
        is HandsOp.Tap, is HandsOp.LongPress, is HandsOp.Type -> policy.act == Act.Full
        else -> true
    }

    /** Input may go to the app: Screen control on, and the app may be acted in at all. */
    fun mayAct(policy: AppPolicy): Boolean =
        policy.screenControl == ScreenControl.On && AppAccess.canOpen(policy) && policy.act != Act.None
}
