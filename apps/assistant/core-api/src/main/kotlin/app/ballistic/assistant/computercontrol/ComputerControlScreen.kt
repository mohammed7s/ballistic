// Screen port on a Computer Control session (SPEC.md §5.1, "Node tree"): other apps'
// accessibility trees from the session's virtual display, as core's ScreenState.
//
// Platform API used (extension library com.android.extensions.computercontrol, API file
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/libs/computercontrol/api_extension/current.txt):
//   ComputerControlSession.getAccessibilityWindows(): List<AccessibilityWindowInfo>
//     "A11y information for all windows on the display associated with the session, or an empty
//     list if no information is currently available"; empty unless the lifecycle is Active.
//     Served by the framework's ComputerControlAccessibilityProxy (an AccessibilityDisplayProxy
//     for the virtual display), so AccessibilityWindowInfo.getRoot() and the usual
//     AccessibilityNodeInfo getters work (https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo).
//   ComputerControlSession.getDisplaySize(): the virtual display's logical pixels = width/height.
//
// What it returns: every window on the session display whose root package the owner lets
// Ballistic read (ScreenControlRules.mayRead, checked on every call), flattened exactly as the
// harness does (AxTree). label and text are filled separately; core's filter decides what the
// planner sees. Windows of other packages (the keyboard, anything unexpected) are left out.
// `package` is the front application window's package; `activity` is empty because the API
// reports no activity, so no foreground_component_v1 readback is claimed.
//
// Not used: requestScreenshot (SPEC §5.1 fallback). Core has no screenshot observation yet.

package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.AppEntry
import app.ballistic.assistant.core.HostCapabilities
import app.ballistic.assistant.core.Node
import app.ballistic.assistant.core.PortException
import app.ballistic.assistant.core.Screen
import app.ballistic.assistant.core.ScreenState

class ComputerControlScreen(
    private val session: () -> CcSessionView?,
    /** Owner rule for one package, read fresh on every observation. */
    private val mayRead: (String) -> Boolean,
    private val installedApps: () -> List<AppEntry>,
    private val capabilities: () -> HostCapabilities,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Set by ComputerControlHands when input may have reached the session since the last observation. */
    private val inputs: CcInputMarker = CcInputMarker(),
) : Screen {
    /** The last tree returned (all windows, with text): host-internal, never sent anywhere. */
    private var lastTree: List<Node>? = null

    override fun observe(): ScreenState {
        val s = session() ?: throw PortException.Host(CcReason.NotStarted.message)
        // onSessionCreated comes before LifecycleCallback.onActive; give it a moment.
        var waited = 0L
        while (s.state is CcState.Starting && waited < START_WAIT_MS) {
            sleep(POLL_MS)
            waited += POLL_MS
        }
        s.state.notActiveReason?.let { throw PortException.Host(it.message) }

        // An empty tree is retried here, before core sees it (core/README.md §4): right after an
        // input or a launch the virtual display may have no windows yet.
        var windows = s.windows()
        var attempt = 0
        while (AxTree.toNodes(windows).isEmpty() && attempt < EMPTY_RETRIES) {
            sleep(EMPTY_RETRY_MS)
            s.state.notActiveReason?.let { throw PortException.Host(it.message) }
            windows = s.windows()
            attempt++
        }
        s.state.notActiveReason?.let { throw PortException.Host(it.message) }
        if (AxTree.toNodes(windows).isNotEmpty()) windows = settled(s, windows)
        // After an input, a tree identical to the previous observation was often read before the
        // app redrew (harness/core_bridge.py observe). Read again a bounded number of times; a
        // screen that really stayed the same is returned as it is.
        val acted = inputs.consume()
        var again = 0
        while (acted && lastTree != null && AxTree.toNodes(windows) == lastTree && again < UNCHANGED_RETRIES) {
            sleep(UNCHANGED_RETRY_MS)
            s.state.notActiveReason?.let { throw PortException.Host(it.message) }
            windows = settled(s, s.windows())
            again++
        }
        lastTree = AxTree.toNodes(windows)
        s.state.notActiveReason?.let { throw PortException.Host(it.message) }
        val targets = (s.state as? CcState.Active)?.targets?.toSet()
            ?: throw PortException.Host(CcReason.Closed.message)
        fun permitted(pkg: String) = pkg in targets && mayRead(pkg)
        // Embedded nodes can name a different package from their window root. Apply the
        // read rule to each subtree, never just the outer window.
        fun prune(node: AxNode, inheritedPackage: String): AxNode? {
            val pkg = node.packageName?.takeIf { it.isNotEmpty() } ?: inheritedPackage
            if (!permitted(pkg)) return null
            return node.copy(children = node.children.mapNotNull { prune(it, pkg) })
        }
        val readable = windows.mapNotNull { w ->
            val root = w.root ?: return@mapNotNull null
            val pkg = root.packageName ?: return@mapNotNull null
            prune(root, pkg)?.let { w.copy(root = it) }
        }
        return ScreenState(
            `package` = AxTree.frontPackage(windows) ?: "",
            activity = "",
            width = s.width,
            height = s.height,
            nodes = AxTree.toNodes(readable),
            installedApps = installedApps(),
            capabilities = capabilities(),
        )
    }

    /**
     * Two consecutive reads with the same skeleton (front package, classes, ids, bounds, flags;
     * text ignored, since a running stopwatch changes its digits every frame) settle the screen,
     * as the harness's two-sample check does. Bounded; an unsettled screen is returned as read.
     */
    private fun settled(s: CcSessionView, first: List<AxWindow>): List<AxWindow> {
        var current = first
        var delay = SETTLE_MS
        repeat(SETTLE_SAMPLES) {
            sleep(delay)
            s.state.notActiveReason?.let { throw PortException.Host(it.message) }
            val next = s.windows()
            if (skeleton(next) == skeleton(current)) return next
            current = next
            delay = minOf(delay * 2, SETTLE_MAX_MS)
        }
        return current
    }

    companion object {
        const val SETTLE_MS = 250L
        const val SETTLE_MAX_MS = 1_000L
        const val SETTLE_SAMPLES = 5
        const val UNCHANGED_RETRIES = 3
        const val UNCHANGED_RETRY_MS = 800L

        /** What a settled screen must keep between two reads; no strings. */
        fun skeleton(windows: List<AxWindow>): Pair<String?, List<Any?>> =
            AxTree.frontPackage(windows) to AxTree.toNodes(windows).map {
                listOf(it.parent, it.role, it.className, it.resourceId, it.`package`, it.bounds, it.flags)
            }

        const val POLL_MS = 100L
        const val START_WAIT_MS = 2_000L
        const val EMPTY_RETRIES = 3
        const val EMPTY_RETRY_MS = 300L
    }
}
