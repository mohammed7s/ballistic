// Screen port from active notifications: the unprivileged eyes (SPEC.md §5.1, "Notifications").
//
// Can: list every active notification of other apps as nodes: one container per notification
// (label = the posting app's name, package = the posting app), its title, body and
// MessagingStyle messages as text nodes, and its action buttons (label = the action's caption).
// Content is in `text` and never in `label` (Types.kt Node: label is what a control IS, text is
// what it SHOWS), so core's filter keeps it away from the planner (core/README.md §5).
// Cannot: see any app's screen, know the foreground app, or give real geometry. Bounds are a
// synthetic list layout (one row per notification) because core's filter drops zero-area nodes;
// they are not screen coordinates, and IntentHands advertises no coordinate operation, so core
// denies any tap on them before dispatch.
//
// Needs the owner to grant notification access (Settings > Notifications > Device & app
// notifications). An empty list is a real answer (nothing pending), so unlike a tree port it is
// not retried.

package app.ballistic.assistant.adapters

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.ballistic.assistant.core.AppEntry
import app.ballistic.assistant.core.Bounds
import app.ballistic.assistant.core.Flags
import app.ballistic.assistant.core.HostCapabilities
import app.ballistic.assistant.core.Node
import app.ballistic.assistant.core.PortException
import app.ballistic.assistant.core.Role
import app.ballistic.assistant.core.Screen
import app.ballistic.assistant.core.ScreenState

/** Bound by the system once the owner grants notification access. */
class NotificationScreenService : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var connected: NotificationScreenService? = null
            private set
    }
}

class NotificationScreen(
    private val context: Context,
    private val installedApps: () -> List<AppEntry>,
    private val capabilities: () -> HostCapabilities,
    private val mayRead: (String) -> Boolean,
    private val needsNotificationAccess: () -> Boolean,
) : Screen {
    override fun observe(): ScreenState {
        // App launch discovery does not require broad notification access. Do not even fetch
        // notifications unless an explicit rule permits reading them with the selected backend.
        val active = if (!needsNotificationAccess()) emptyArray() else {
            val service = NotificationScreenService.connected
                ?: throw PortException.Host("notification access is not granted to Ballistic")
            try { service.activeNotifications.orEmpty() }
            catch (_: SecurityException) { throw PortException.Host("notification access was revoked") }
        }
        val metrics = context.resources.displayMetrics
        val nodes = buildNodes(
            active.filter { it.packageName != context.packageName && mayRead(it.packageName) }.sortedByDescending { it.postTime },
            metrics.widthPixels,
        )
        // Sent on every observation: core keeps the list per run, and this port does not know
        // where one run ends. The list is small and core keeps only the last non-empty one.
        val apps = installedApps()
        return ScreenState(
            `package` = PACKAGE,
            activity = ACTIVITY,
            width = metrics.widthPixels,
            height = maxOf(metrics.heightPixels, (nodes.lastOrNull()?.bounds?.y2 ?: 0)),
            nodes = nodes,
            installedApps = apps,
            capabilities = capabilities(),
        )
    }

    private fun buildNodes(notifications: List<StatusBarNotification>, width: Int): List<Node> {
        val nodes = mutableListOf<Node>()
        var id = 1
        var y = 0
        for (sbn in notifications) {
            val n = sbn.notification
            val extras = n.extras
            val lines = buildList {
                extras.getCharSequence(Notification.EXTRA_TITLE)?.let { add(it.toString()) }
                (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                    ?.let { add(it.toString()) }
                messages(extras).forEach { add(it) }
            }
            val actions = n.actions.orEmpty()
            val rows = 1 + lines.size + actions.size
            val top = y
            val containerId = id++
            nodes += node(containerId, null, Role.Container, appLabel(sbn.packageName), null, sbn, Bounds(0, top, width, top + rows * ROW), clickable = false)
            y += ROW
            for (line in lines) {
                nodes += node(id++, containerId, Role.Text, null, line, sbn, Bounds(0, y, width, y + ROW), clickable = false)
                y += ROW
            }
            actions.forEachIndexed { index, action ->
                nodes += node(id++, containerId, Role.Button, action.title?.toString(), null, sbn, Bounds(0, y, width, y + ROW), clickable = true, resourceId = "notification:${sbn.key}:action:$index")
                y += ROW
            }
        }
        return nodes
    }

    private fun node(id: Int, parent: Int?, role: Role, label: String?, text: String?, sbn: StatusBarNotification, bounds: Bounds, clickable: Boolean, resourceId: String? = null) =
        Node(
            id = id, parent = parent, role = role, className = null, label = label, text = text,
            resourceId = resourceId, `package` = sbn.packageName, bounds = bounds,
            flags = Flags(clickable = clickable, enabled = true, visible = true),
        )

    /** MessagingStyle history: "sender: text" per message, oldest first. */
    private fun messages(extras: Bundle): List<String> {
        val parcels = extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java) ?: return emptyList()
        return parcels.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text") ?: return@mapNotNull null
            val sender = b.getCharSequence("sender")
            if (sender != null) "$sender: $text" else text.toString()
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        /** Notifications are drawn by SystemUI; this names the observation, not a foreground app. */
        const val PACKAGE = "com.android.systemui"
        const val ACTIVITY = "notification_listener"
        private const val ROW = 120
    }
}
