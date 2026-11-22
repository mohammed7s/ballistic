// Version-5 client for the OS-provided optional shared library. Compile-only declarations
// are checked against GrapheneOS 2026110200; see docs/PLATFORM.md. All state transitions and
// individual input calls share a lock; callbacks from older/cancelled requests are ignored.
package app.ballistic.assistant.adapters

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.AppInteractionAttribution
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.ballistic.assistant.MainActivity
import app.ballistic.assistant.R
import app.ballistic.assistant.computercontrol.AxNode
import app.ballistic.assistant.computercontrol.AxTree
import app.ballistic.assistant.computercontrol.AxWindow
import app.ballistic.assistant.computercontrol.AxWindowKind
import app.ballistic.assistant.computercontrol.CcInput
import app.ballistic.assistant.computercontrol.CcPlatform
import app.ballistic.assistant.computercontrol.CcReason
import app.ballistic.assistant.computercontrol.CcSessionView
import app.ballistic.assistant.computercontrol.CcState
import app.ballistic.assistant.computercontrol.ScreenControlRules
import app.ballistic.assistant.computercontrol.ownsScreen
import app.ballistic.assistant.core.Bounds
import com.android.extensions.computercontrol.ComputerControlExtensions
import com.android.extensions.computercontrol.ComputerControlSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import app.ballistic.assistant.computercontrol.CcGeneration
import app.ballistic.assistant.ScreenControlStopReceiver

class ComputerControlPlatform(
    private val context: Context,
    private val onEnded: () -> Unit,
    private val mayInput: (String, Boolean) -> Boolean,
) {
    private val _state = MutableStateFlow<CcState>(CcState.Idle())
    val state: StateFlow<CcState> = _state.asStateFlow()

    /** Callbacks from the platform arrive here, one at a time. */
    private val executor = context.mainExecutor
    private val requests = CcGeneration()
    private var owner: WeakReference<Activity>? = null

    @Volatile private var live: Live? = null
    @Volatile private var consent: IntentSender? = null

    /** The running session for the adapters; null when none owns the screen. */
    val view: CcSessionView? get() = live?.takeIf { _state.value.ownsScreen }

    /** What the platform offers now. Cheap; read on demand by the UI and before a request. */
    @Synchronized fun platform(): CcPlatform {
        // Computer Control is Android 17 (API 37); the APK also installs on API 33.
        val library = Build.VERSION.SDK_INT >= 37 && context.packageManager.hasSystemFeature(LIBRARY) &&
            runCatching { Class.forName(EXTENSIONS_CLASS) }.isSuccess
        val version = if (library) runCatching { ComputerControlExtensions.getVersion() }.getOrDefault(0) else 0
        // Require the versioned v5 contract; never fall back to the deprecated v4 factory.
        val available = if (library && version >= 5)
            runCatching { ComputerControlExtensions.isSessionCreationAvailable(context, VERSION) }.getOrNull() else null
        return CcPlatform(
            library = library,
            permission = granted(ACCESS_COMPUTER_CONTROL),
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            assistantRole = AssistantRole.held(context),
            version = version,
            sessionCreationAvailable = available,
        )
    }

    /**
     * Requests a session for exactly [targets] (the apps the owner turned Screen control on for).
     * Returns the reason when nothing was requested; null when the request went to the platform
     * (the outcome arrives in [state]). Call from a visible Ballistic activity.
     */
    @Synchronized fun start(activity: Activity, targets: List<String>): CcReason? {
        if (Build.VERSION.SDK_INT < 37) return fail(CcReason.NoLibrary)
        if (activity.isDestroyed || activity.isFinishing || !activity.hasWindowFocus()) return fail(CcReason.NotVisible)
        val current = _state.value
        if (current is CcState.Requesting || current is CcState.ConsentNeeded || current.ownsScreen) return null
        val problem = platform().missing ?: ScreenControlRules.targetProblem(targets)
            ?: if (context.packageName in targets) CcReason.NotAutomatable else null
        if (problem != null) {
            _state.value = CcState.Idle(problem)
            return problem
        }
        val extensions = runCatching {
            ComputerControlExtensions.getInstance(activity, VERSION)
        }.getOrNull() ?: return fail(CcReason.NoLibrary)
        val params = try {
            ComputerControlSession.Params.Builder(activity)
                .setName(SESSION_NAME)
                .setTargetPackageNames(targets)
                .setNotificationParams(ComputerControlSession.NotificationParams.Builder(notification(), NOTIFICATION_ID).build())
                .setAppInteractionAttribution(AppInteractionAttribution.Builder(AppInteractionAttribution.INTERACTION_TYPE_USER_QUERY).build())
                .build()
        } catch (e: RuntimeException) {
            return fail(CcReason.Failed, e)
        }
        owner = WeakReference(activity)
        val request = requests.next()
        _state.value = CcState.Requesting
        return try {
            extensions.requestSession(params, executor, Callback(targets.toList(), request))
            null
        } catch (e: SecurityException) {
            fail(CcReason.NotAllowed, e)
        } catch (e: IllegalArgumentException) {
            fail(CcReason.NotAutomatable, e)
        } catch (e: RuntimeException) {
            fail(CcReason.Failed, e)
        } catch (e: LinkageError) {
            fail(CcReason.NoLibrary, e)
        }
    }

    /** Opens the platform's consent screen when the owner taps Review. False if none is waiting. */
    @Synchronized fun reviewConsent(activity: Activity): Boolean {
        if (owner?.get() !== activity || activity.isDestroyed || !activity.hasWindowFocus()) return false
        val sender = consent ?: return false
        if (Build.VERSION.SDK_INT < 37) return false
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            .toBundle()
        return try {
            activity.startIntentSender(sender, null, 0, 0, 0, options)
            true
        } catch (e: Exception) {
            fail(CcReason.Failed, e)
            false
        }
    }

    /** Destruction (including rotation) ends this activity's session. No automatic restart. */
    @Synchronized fun ownerDestroyed(activity: Activity) {
        if (owner?.get() === activity) close()
    }

    @Synchronized fun close() {
        if (live == null && _state.value is CcState.Idle) return
        finish(CcReason.Closed)
    }

    private fun finish(reason: CcReason) {
        Log.i(TAG, "computer control: ended (${reason.name})")
        requests.invalidate()
        val old = live
        // Stop the in-flight task before removing its session. Routing also stays pinned for
        // that run, so it cannot fall through to an ordinary intent on the owner's display.
        onEnded()
        live = null
        consent = null
        owner = null
        _state.value = CcState.Idle(reason)
        old?.let { runCatching { it.session.close() } }
    }

    private fun fail(reason: CcReason, e: Throwable? = null): CcReason {
        Log.i(TAG, "computer control: ${reason.name}${e?.let { " (${it::class.java.simpleName})" } ?: ""}")
        finish(reason)
        return reason
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun current(request: Long) = requests.isCurrent(request) && owner?.get()?.let { !it.isDestroyed && !it.isFinishing } == true

    private inner class Callback(private val targets: List<String>, private val request: Long) : ComputerControlSession.Callback {
        override fun onSessionPending(intentSender: IntentSender) = synchronized(this@ComputerControlPlatform) {
            if (!current(request) || _state.value != CcState.Requesting) return@synchronized
            consent = intentSender
            _state.value = CcState.ConsentNeeded(targets)
            Log.i(TAG, "computer control: consent pending")
        }

        override fun onSessionCreated(session: ComputerControlSession) = synchronized(this@ComputerControlPlatform) {
            if (!current(request) || live != null) {
                runCatching { session.close() }
                return@synchronized
            }
            consent = null
            try {
                val size = session.displaySize
                live = Live(session, size.width, size.height, request, targets)
                _state.value = CcState.Starting(targets)
                Log.i(TAG, "computer control: session created")
                session.setLifecycleCallback(executor, Lifecycle(targets, request))
            } catch (e: RuntimeException) {
                runCatching { session.close() }
                fail(CcReason.Failed, e)
            }
            Unit
        }

        override fun onSessionCreationFailed(errorCode: Int) = synchronized(this@ComputerControlPlatform) {
            if (!current(request)) return@synchronized
            fail(when (errorCode) {
                ComputerControlSession.ERROR_SESSION_LIMIT_REACHED -> CcReason.Busy
                ComputerControlSession.ERROR_DEVICE_LOCKED -> CcReason.Locked
                ComputerControlSession.ERROR_PERMISSION_DENIED -> if (consent != null) CcReason.ConsentRefused else CcReason.NotAllowed
                else -> CcReason.Failed
            })
            Unit
        }

        @Deprecated("Replaced by LifecycleCallback.onClosed")
        override fun onSessionClosed() = synchronized(this@ComputerControlPlatform) {
            if (current(request)) finish(CcReason.Closed)
        }
    }

    private inner class Lifecycle(private val targets: List<String>, private val request: Long) : ComputerControlSession.LifecycleCallback {
        override fun onActive() = synchronized(this@ComputerControlPlatform) {
            if (current(request) && live != null) {
                _state.value = CcState.Active(targets)
                Log.i(TAG, "computer control: active")
            }
        }

        override fun onBlocked(reason: Int, blockingPackage: String?) = synchronized(this@ComputerControlPlatform) {
            if (!current(request) || live == null) return@synchronized
            _state.value = CcState.Blocked(targets, when (reason) {
                ComputerControlSession.BLOCK_REASON_SECURE_CONTENT -> CcReason.BlockedSecure
                ComputerControlSession.BLOCK_REASON_DISALLOWED_ACTIVITY_LAUNCH -> CcReason.BlockedLaunch
                BLOCK_REASON_AUTHENTICATION_PROMPT_REQUESTED -> CcReason.BlockedAuth
                else -> CcReason.Blocked
            }, blockingPackage)
            // A blocked session requires a new deliberate request; do not resume input later.
            finish((_state.value as CcState.Blocked).reason)
        }

        override fun onClosed(reason: Int) = synchronized(this@ComputerControlPlatform) {
            if (current(request)) {
                Log.i(TAG, "computer control: platform closed ($reason)")
                finish(CcReason.Closed)
            }
        }
    }

    /** The session as core's adapters see it. */
    private inner class Live(val session: ComputerControlSession, override val width: Int, override val height: Int, private val request: Long, private val targets: List<String>) : CcSessionView {
        override val state: CcState get() = synchronized(this@ComputerControlPlatform) {
            if (current(request) && live === this) _state.value else CcState.Idle(CcReason.Closed)
        }

        private fun inputTo(target: String? = null, full: Boolean = false, action: () -> Unit) = synchronized(this@ComputerControlPlatform) {
            check(state is CcState.Active) { "session is not active" }
            val pkg = target ?: frontPackage()
            check(pkg != null && pkg in targets && mayInput(pkg, full)) { "task stopped or app access changed" }
            action()
        }

        override val input: CcInput = object : CcInput {
            override fun tap(x: Int, y: Int) = inputTo(full = true) { session.tap(x, y) }
            override fun longPress(x: Int, y: Int) = inputTo(full = true) { session.longPress(x, y) }
            override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = inputTo { session.swipe(fromX, fromY, toX, toY) }
            override fun insertText(text: String, replaceExisting: Boolean, commit: Boolean) = inputTo(full = true) { session.insertText(text, replaceExisting, commit) }
            override fun back() = inputTo { session.performAction(ComputerControlSession.ACTION_GO_BACK) }
            override fun launch(`package`: String, activity: String?) = inputTo(`package`) {
                if (activity != null) session.launchApplication(ComponentName(`package`, activity)) else session.launchApplication(`package`)
            }
        }

        override fun windows(): List<AxWindow> = synchronized(this@ComputerControlPlatform) {
            check(state is CcState.Active) { "session is not active" }
            session.accessibilityWindows.map { w ->
                AxWindow(kind = kind(w.type), active = w.isActive, focused = w.isFocused, root = w.root?.let { copy(it, 0, intArrayOf(0)) })
            }
        }

        /** Window roots only (no children), for the dispatch-time front-app check. */
        override fun frontPackage(): String? = synchronized(this@ComputerControlPlatform) {
            if (state !is CcState.Active) return@synchronized null
            AxTree.frontPackage(session.accessibilityWindows.map { w ->
                AxWindow(kind = kind(w.type), active = w.isActive, focused = w.isFocused,
                    root = w.root?.let { AxNode(packageName = it.packageName?.toString()) })
            })
        }
    }

    private fun kind(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> AxWindowKind.Application
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> AxWindowKind.InputMethod
        AccessibilityWindowInfo.TYPE_SYSTEM -> AxWindowKind.System
        else -> AxWindowKind.Other
    }

    /** Field-by-field copy; bounded so a pathological tree cannot exhaust memory or the stack. */
    private fun copy(n: AccessibilityNodeInfo, depth: Int, count: IntArray): AxNode {
        count[0]++
        val r = Rect().also(n::getBoundsInScreen)
        val children = if (depth >= MAX_DEPTH) emptyList() else (0 until n.childCount).mapNotNull { i ->
            if (count[0] >= MAX_NODES) null else n.getChild(i)?.let { copy(it, depth + 1, count) }
        }
        return AxNode(
            className = n.className?.toString(),
            text = n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            hintText = n.hintText?.toString(),
            tooltipText = n.tooltipText?.toString(),
            viewId = n.viewIdResourceName,
            packageName = n.packageName?.toString(),
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable,
            scrollable = n.isScrollable, checkable = n.isCheckable, checked = n.isChecked,
            selected = n.isSelected, enabled = n.isEnabled, focused = n.isFocused,
            visibleToUser = n.isVisibleToUser,
            children = children,
        )
    }

    /** Required by extension v5 (Params.setNotificationParams); the system posts it while the session runs. */
    private fun notification(): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen control", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(context, 0, Intent(context, ScreenControlStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Ballistic screen control is on")
            .setContentText("Apps you allowed run on a hidden screen")
            .setContentIntent(open)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build().also { require(it.hasPromotableCharacteristics()) { "Screen control notification cannot be promoted" } }
    }

    companion object {
        private const val TAG = "Ballistic"
        private const val VERSION = 5
        const val ACCESS_COMPUTER_CONTROL = "android.permission.ACCESS_COMPUTER_CONTROL"
        /** Library and feature name (computercontrol.extension.xml). */
        const val LIBRARY = "com.android.extensions.computercontrol"
        private const val EXTENSIONS_CLASS = "com.android.extensions.computercontrol.ComputerControlExtensions"
        private const val SESSION_NAME = "ballistic"
        private const val CHANNEL = "screen_control"
        private const val NOTIFICATION_ID = 3
        /** Framework value; the extension's current.txt does not expose this block reason yet. */
        private const val BLOCK_REASON_AUTHENTICATION_PROMPT_REQUESTED = 4
        private const val MAX_DEPTH = 64
        private const val MAX_NODES = 4_000
    }
}
