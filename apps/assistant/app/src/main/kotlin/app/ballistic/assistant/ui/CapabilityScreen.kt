// "What Ballistic can do here" (docs/UX_SYNTHESIS.md §4.7), generated each time from what the
// adapters advertise to core (AppGraph.capabilities(): IntentHands.OPERATIONS and the apps the
// owner's rules allow), the notification rules and access, and whether a real engine and model
// are ready. Nothing on this page is static marketing; "Never" lists SPEC §2 non-goals.

package app.ballistic.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.adapters.InstalledApps
import app.ballistic.assistant.adapters.NotificationScreenService
import app.ballistic.assistant.core.AppAccess
import app.ballistic.assistant.core.UniffiCoreEngine
import app.ballistic.assistant.models.ModelBackend

/** Operations core knows (core/src/capabilities.rs), in owner words. */
private val OPERATION_WORDS = linkedMapOf(
    "open_app" to "Open an app",
    "wait" to "Wait for an app to load",
    "tap" to "Tap",
    "long_press" to "Long-press",
    "type" to "Type",
    "scroll" to "Scroll",
    "swipe" to "Swipe",
    "back" to "Go back",
    "home" to "Go to the home screen",
)

@Composable
fun CapabilityScreen(graph: AppGraph) {
    val c = MaterialTheme.colorScheme
    val selection by graph.localAi.selection.collectAsState()
    val local by graph.localModels.state.collectAsState()
    val caps = remember { graph.capabilities() }
    val apps = remember { InstalledApps.list(graph.app) }
    val policy = remember { graph.policyStore.load() }
    val label = { pkg: String -> apps.firstOrNull { it.`package` == pkg }?.label ?: pkg }
    val opens = caps.appGrants.map(label)
    val onPhone = selection.backend == ModelBackend.Local
    val reads = apps.filter { AppAccess.canReadNotifications(policy.forApp(it.`package`), onPhone) }.map { it.label }
    val listener = NotificationScreenService.connected != null
    val realEngine = graph.engine.current is UniffiCoreEngine
    val modelReady = realEngine && (!onPhone || local.metadata != null)
    val missing = OPERATION_WORDS.keys.filter { it !in caps.operations }

    @Composable
    fun Entry(title: String, detail: String) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.s2)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Hairline()
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text("Built from this phone's permissions, your app rules and the AI you've set up.",
            Modifier.padding(top = Space.s2), style = MaterialTheme.typography.bodyMedium)

        SectionHeading("Can now")
        if ("open_app" in caps.operations) Entry("Open apps you allow",
            if (opens.isEmpty()) "No apps allowed yet. Allow one in Settings › App rules." else opens.joinToString())
        Entry("Read notifications from apps you allow", when {
            reads.isEmpty() -> "None allowed"
            !listener -> "Allowed: ${reads.joinToString()}. Notification access isn't granted, so nothing is read."
            else -> reads.joinToString()
        })
        Entry("Answer questions", if (modelReady) "With no phone actions; replies show where the AI ran."
            else if (!realEngine) "Not in this build: only the demo engine is available." else "After you load a model in Local AI.")
        Entry("Check local AI speed", if (local.metadata != null) "Short and ~2K checks in Local AI" else "After you load a model in Local AI")

        SectionHeading("Not on this phone yet")
        if (missing.isNotEmpty()) Entry(
            missing.joinToString { OPERATION_WORDS.getValue(it).lowercase() }.replaceFirstChar { it.uppercase() } + " inside other apps",
            "Needs platform support (Computer Control) that this phone setup doesn't provide.")
        Entry("Read another app's screen", "Only notifications from apps you allow can be read.")
        Entry("Set alarms, send messages, make calls, reply to notifications", "Not available to the assistant in this build.")

        SectionHeading("Never")
        Entry("Log in or make payments for you", "Not part of Ballistic's design.")
        Entry("Hide from apps or get around their restrictions", "Not part of Ballistic's design.")
        Text("Operations this build advertises to the core: ${caps.operations.joinToString()}.",
            Modifier.padding(vertical = Space.s3), style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
    }
}
