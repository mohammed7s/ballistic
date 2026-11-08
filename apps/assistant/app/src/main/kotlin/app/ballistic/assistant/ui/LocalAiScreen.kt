// Local AI, simplified (owner, 9 Nov 2026: "too complicated, too much info"). The page shows only:
// the current model with its state, where it runs and a plain speed line, with Change model and
// Unload; where AI runs (this phone or a connected server); one speed check; and Advanced ›.
// The model picker (ModelPickerScreen) and everything technical (LocalAiAdvancedScreen) are
// separate pages. Every action is the same LocalAiController call as before.

package app.ballistic.assistant.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.models.*
import app.ballistic.assistant.session.LabelStyle

@Composable
fun LocalAiScreen(graph: AppGraph, activity: Activity, onOpen: (Screen) -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val ai = graph.localAi
    val selected by ai.selection.collectAsState()
    val model by ai.model.collectAsState()
    val engine by graph.localModels.state.collectAsState()
    val busy by ai.busy.collectAsState()
    val notice by ai.notice.collectAsState()
    val download by ai.downloadProgress.collectAsState()
    val downloading by ai.downloading.collectAsState()
    val speedVersion by ai.speedVersion.collectAsState()
    val run by graph.session.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    val backgroundNotice by graph.backgroundNotice.collectAsState()
    val allowed = localAiAllowed(graph)
    var gateError by remember { mutableStateOf<String?>(null) }
    val connectedConfigured = remember(selected) { graph.modelsConfig.load() != null }
    val leaves = remember(selected) { graph.leavesPhone() }

    val activeId = ai.activeId
    val entry = ai.entry(activeId)
    val loaded = engine.metadata != null && engine.target?.modelId == activeId
    val backend = engine.target?.takeIf { loaded }?.backend ?: entry.backendFor(selected)
    val last = remember(activeId, backend, speedVersion) { ai.lastSpeed(activeId, backend) }

    DownloadConfirm(graph)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        backgroundNotice?.let { WarnBlock("Interrupted", it) }
        if (!unlocked) Row(Modifier.fillMaxWidth().padding(top = Space.s2).ruledBox(c.outlineVariant), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            StatusLabel("Locked", LabelStyle.Inverted)
            Text("Enable requests to change models.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            BButton("Enable", { graph.gate.authenticate(activity) { e -> gateError = e; if (e == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled() } })
        }
        gateError?.let { WarnBlock("Not unlocked", it) }
        if (!run.canSubmit || run.pendingRequest != null) Text("Finish or stop the task before changing models.", style = MaterialTheme.typography.bodySmall)

        // (a) Current model.
        SectionHeading("Current model")
        if (model == null) {
            Text("No model on this phone yet", style = MaterialTheme.typography.titleMedium)
            Text("Choose one to download. The small starter is ${ai.starter.name}, ${ModelPicker.size(ai.starter.bytes)}.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            BButton("Choose a model", { onOpen(Screen.Models) }, emphasis = true)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(entry?.name ?: model?.name?.let { "Imported: $it" } ?: "Imported GGUF", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                val phase = if (loaded || engine.target?.modelId == activeId) engine.phase else LocalPhase.Unloaded
                StatusLabel(phase.label(), if (phase == LocalPhase.Error) LabelStyle.Inverted else LabelStyle.Outlined)
            }
            Text("Runs on this phone · ${chipWord(backend)}", style = MaterialTheme.typography.bodyMedium)
            Text(SpeedWords.stepLine(last) ?: "Speed not tested yet", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            if (selected.backend == ModelBackend.Http) Text("Not in use now: AI runs on the connected server.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                BButton("Change model", { onOpen(Screen.Models) })
                if (loaded) BButton("Unload", ai::unload, enabled = allowed.change)
                else BButton("Load", ai::load, enabled = allowed.change, emphasis = true)
            }
        }
        if (busy || engine.busy) {
            FlatProgress(download?.fraction)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(download?.let { "Downloading ${ai.entry(downloading)?.name ?: "model"} · ${transferSize(it.received)} / ${transferSize(it.total)}" }
                    ?: if (engine.phase == LocalPhase.Loading) "Loading…" else "Working…",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                BButton("Stop", { if (run.canStop) graph.stop() else ai.stop() })
            }
        }
        engine.error?.takeIf { !loaded }?.let { WarnBlock("Didn't load", it) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // (b) Where AI runs.
        SectionHeading("Where AI runs")
        Column(verticalArrangement = Arrangement.spacedBy((-1).dp)) {
            RadioRow(selected.backend == ModelBackend.Local, "On this phone", "Prompts and screen information stay on this phone.", allowed.change) {
                ai.choose(ModelBackend.Local)
            }
            RadioRow(selected.backend == ModelBackend.Http, "Connected server",
                if (connectedConfigured) connectedModelSummary(graph).removePrefix("Connected · ") else "Set up in Settings · prompts leave the phone",
                allowed.change) { ai.choose(ModelBackend.Http) }
        }
        if (selected.backend == ModelBackend.Http) {
            if (leaves) WarnBlock("Leaves phone", "Prompts and screen information are sent to ${graph.routeLabel().removePrefix("left the phone: ")}. Apps set to \"this phone only\" are left out.")
            else if (!connectedConfigured) WarnBlock("Not set up", "Add a server in Settings first.")
            BButton("Connection settings", onOpenSettings)
        }

        // (c) Speed check: the realistic one (about two thousand prompt tokens, like a phone-task step).
        SectionHeading("Speed check")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            BButton("Test", { ai.speedCheck(SpeedCheck.LongPrefill) }, enabled = allowed.change && loaded)
            Text(last?.let { "Last: " + SpeedWords.testLine(it) } ?: if (loaded) "Not tested yet" else "Load the model to test",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }

        // (d) Advanced.
        Hairline(Modifier.padding(top = Space.s2))
        Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClickLabel = "Open Advanced") { onOpen(Screen.LocalAiAdvanced) },
            verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("›", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.height(Space.s4))
    }
}
