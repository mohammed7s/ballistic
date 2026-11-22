// "What Ballistic can do here" (docs/UX_SYNTHESIS.md §4.7), generated each time from what the
// adapters advertise to core (AppGraph.capabilities(): IntentHands.OPERATIONS and the apps the
// owner's rules allow), the notification rules and access, and whether a real engine and model
// are ready. Nothing on this page is static marketing; "Never" lists SPEC §2 non-goals.

package app.ballistic.assistant.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import app.ballistic.assistant.MainActivity
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.computercontrol.CcPlatform
import app.ballistic.assistant.computercontrol.CcReason
import app.ballistic.assistant.computercontrol.CcState
import app.ballistic.assistant.computercontrol.ScreenControlRules
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

/** One line for the platform's Computer Control preconditions, in the order the owner fixes them. */
private fun platformLine(p: CcPlatform): String = when (p.missing) {
    null -> "Available (extension version ${p.version})."
    CcReason.NoLibrary -> "Not on this phone: the OS has no Computer Control library."
    CcReason.NoPermission -> "The OS has not granted Ballistic Computer Control access."
    CcReason.NoNotifications -> "Allow Ballistic's notifications first; Computer Control requires them."
    CcReason.NotAssistant -> "Make Ballistic the digital assistant app: Settings › Apps › Default apps."
    CcReason.NotAllowed -> "The OS does not allow Ballistic to start a session."
    else -> p.missing!!.message
}

private fun sessionLine(s: CcState): String = when (s) {
    is CcState.Idle -> s.reason?.takeIf { it != CcReason.NotStarted }?.let { "Not running. Last: ${it.message}." } ?: "Not running."
    CcState.Requesting -> "Asking the system…"
    is CcState.ConsentNeeded -> "Waiting for your consent for these apps."
    is CcState.Starting -> "Starting."
    is CcState.Active -> "Running. Apps open on a hidden screen; the notification shows it is on."
    is CcState.Blocked -> s.reason.message + "."
}

@Composable
fun CapabilityScreen(graph: AppGraph) {
    val c = MaterialTheme.colorScheme
    val selection by graph.localAi.selection.collectAsState()
    val local by graph.localModels.state.collectAsState()
    val cc by graph.computerControl.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    val activity = LocalContext.current as? Activity
    var ccMessage by remember { mutableStateOf<String?>(null) }
    var resumeCount by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumeCount++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val platform = remember(cc, resumeCount) { graph.computerControl.platform() }
    val caps = remember(cc, resumeCount) { graph.capabilities() }
    val apps = remember { InstalledApps.list(graph.app) }
    val policy = remember(resumeCount) { graph.policyStore.load() }
    val label = { pkg: String -> apps.firstOrNull { it.`package` == pkg }?.label ?: pkg }
    val opens = caps.appGrants.map(label)
    val onPhone = selection.backend == ModelBackend.Local
    val reads = apps.filter { AppAccess.canReadNotifications(policy.forApp(it.`package`), onPhone) }.map { it.label }
    val listener = NotificationScreenService.connected != null
    val realEngine = graph.engine.current is UniffiCoreEngine
    val modelReady = realEngine && (!onPhone || local.metadata != null)
    val missing = OPERATION_WORDS.keys.filter { it !in caps.operations }
    val screenTargets = ScreenControlRules.targets(policy, graph.app.packageName)
    val targetProblem = ScreenControlRules.targetProblem(screenTargets)

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

        SectionHeading("Screen control (Computer Control)")
        Entry("This phone", platformLine(platform))
        BButton("Choose digital assistant", {
            activity?.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }, Modifier.fillMaxWidth())
        Entry("System Screen automation", "In Android Settings › Apps › Special app access › Screen automation, enable Ballistic and each target. These system switches are separate from Ballistic's App rules and the target consent dialog.")
        BButton("Open Android Apps settings", {
            activity?.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }, Modifier.fillMaxWidth())
        if (!platform.notifications) BButton("Allow notifications", {
            (activity as? MainActivity)?.requestNotificationPermission()
        }, Modifier.fillMaxWidth())
        Entry("Apps with Screen control on", when (targetProblem) {
            CcReason.NoTargets -> "None. Turn it on for up to six apps in Settings › App rules."
            CcReason.TooManyTargets -> "More than six apps; the system allows six. Turn some off in Settings › App rules."
            else -> screenTargets.map(label).joinToString()
        })
        Entry("Session", sessionLine(cc))
        ccMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when (cc) {
            is CcState.Idle -> BButton("Start screen control", {
                ccMessage = if (activity == null) "Keep Ballistic visible to start." else graph.startComputerControl(activity)?.message
            }, Modifier.fillMaxWidth(), enabled = activity != null && unlocked && platform.missing == null && targetProblem == null)
            is CcState.ConsentNeeded -> BButton("Review app consent", {
                if (activity == null || !graph.computerControl.reviewConsent(activity)) ccMessage = "The consent screen could not be opened."
            }, Modifier.fillMaxWidth(), emphasis = true)
            else -> BButton("End screen control", { graph.stop() }, Modifier.fillMaxWidth())
        }
        if (cc is CcState.ConsentNeeded) BButton("Cancel screen control", { graph.stop() }, Modifier.fillMaxWidth())
        if (cc is CcState.Active) Entry("Read and act in these apps", "On the hidden screen only, within each app's rules; ${screenTargets.map(label).joinToString()}.")

        SectionHeading("Not on this phone yet")
        if (missing.isNotEmpty()) Entry(
            missing.joinToString { OPERATION_WORDS.getValue(it).lowercase() }.replaceFirstChar { it.uppercase() } + " inside other apps",
            if (cc is CcState.Active) "Computer Control offers no home action."
            else "Needs a running Computer Control session (Screen control above).")
        if (cc !is CcState.Active) Entry("Read another app's screen", "Only notifications from apps you allow can be read, until screen control runs.")
        Entry("Typed app operations", if (cc !is CcState.Idle)
            "Unavailable during screen control: ordinary intents open on your screen, outside the controlled display. End screen control to use them."
            else if (caps.intents.isEmpty()) "No handlers allowed by App rules. Supported handlers can open composers, the dialer and other pages; opening is not completion."
            else caps.intents.map { it.operation }.distinct().joinToString())

        SectionHeading("Never")
        Entry("Log in or make payments for you", "Not part of Ballistic's design.")
        Entry("Hide from apps or get around their restrictions", "Not part of Ballistic's design.")
        Text("Operations this build advertises to the core: ${caps.operations.joinToString()}.",
            Modifier.padding(vertical = Space.s3), style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
    }
}
