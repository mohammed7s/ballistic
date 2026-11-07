// The prompt box: the Android counterpart of harness/chat.py. Type a request, watch each step
// (action, why, outcome) as core reports it, Stop at any time. Shows which engine and which
// model are in use and whether requests leave the device, and the command gate's state.

package app.ballistic.assistant.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.models.leavesDevice
import app.ballistic.assistant.session.Phase
import app.ballistic.assistant.session.RunView
import app.ballistic.assistant.session.PromptSession
import kotlinx.coroutines.delay
import app.ballistic.assistant.session.actionLabel
import app.ballistic.assistant.session.evidenceLabel
import app.ballistic.assistant.session.resultLabel
import app.ballistic.assistant.storage.SecretStore

@Composable
fun PromptScreen(graph: AppGraph, activity: Activity, onOpenLocalAi: () -> Unit = {}) {
    val state by graph.session.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    val localBusy by graph.localAi.busy.collectAsState()
    val localState by graph.localModels.state.collectAsState()
    val backend by graph.localAi.selection.collectAsState()
    val backgroundNotice by graph.backgroundNotice.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var gateMessage by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val localSelected = backend.backend == app.ballistic.assistant.models.ModelBackend.Local
    val modelReady = !localSelected || (localState.metadata != null && graph.engine.current is app.ballistic.assistant.core.UniffiCoreEngine)

    fun unlock() {
        graph.gate.authenticate(activity) { failure ->
            gateMessage = failure
            if (failure == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled()
        }
    }

    // Which engine takes the next run depends on Settings (model configured, library present).
    LaunchedEffect(Unit) { graph.session.refreshEngine() }
    // A request parked by a locked gate brings up the prompt at once.
    LaunchedEffect(state.phase) { if (state.phase == Phase.NeedsUnlock) unlock() }
    LaunchedEffect(state.runs.size, state.runs.lastOrNull()?.steps?.size) {
        if (state.runs.isNotEmpty()) list.animateScrollToItem(state.runs.size - 1)
    }

    Column(Modifier.padding(12.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if(graph.engine.current is app.ballistic.assistant.core.FakeCoreEngine) {
            Text(state.engine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Text(if (backend.backend == app.ballistic.assistant.models.ModelBackend.Local) "Local · ${graph.localAi.model.value?.name ?: "no model installed"} · ${localState.phase} · model requests stay on device" else connectedModelSummary(graph), style = MaterialTheme.typography.labelMedium)
        if(localSelected && !modelReady && state.runs.isNotEmpty()) {
            OutlinedButton(onClick = onOpenLocalAi) { Text("Set up local model") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (unlocked) "Requests enabled" else "Requests locked", fontWeight = FontWeight.Bold,
                color = if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (unlocked) OutlinedButton(onClick = { graph.gate.lock() }) { Text("Lock requests") }
            else OutlinedButton(onClick = { unlock() }) { Text("Enable requests") }
        }
        Text("Your phone unlock authorizes requests. Switching apps keeps access; sleeping or locking the phone blocks new requests. Sensitive actions still need approval.", style = MaterialTheme.typography.bodySmall)
        gateMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        backgroundNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(state.runs.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("What you can test", style = MaterialTheme.typography.titleLarge)
                        Text("Local AI → Check local speed tests model inference. Ask can request opening an app, but this starter model may choose incorrectly. Creating alarms, typing and tapping are not supported yet.")
                        if(localSelected && !modelReady) {
                            Text(if(graph.localAi.model.value == null) "Download the starter or import your own model to get started." else "Load your model before asking Ballistic.")
                            Button(onClick = onOpenLocalAi) {
                                Text(if(graph.localAi.model.value == null) "Set up local AI" else "Load local model")
                            }
                        } else Text("Try: Open Clock")
                    }
                }
            }
            items(state.runs, key = { it.id }) { RunCard(it, graph.session, state.canStop && it.id == state.runs.lastOrNull()?.id) }
        }

        if (state.canStop) {
            val notificationsEnabled = activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            Text("You can leave the app. Force-stopping it ends the task.", style = MaterialTheme.typography.bodySmall)
            if (!notificationsEnabled) {
                Text("Task notifications are disabled. Enable them for elapsed time and Stop outside the app.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                }) { Text("Task notification settings") }
            }
            OutlinedButton(onClick = { activity.moveTaskToBack(true) }) { Text("Continue in background") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                enabled = state.canSubmit && !localBusy,
                placeholder = { Text("Ask Ballistic") },
            )
            if (state.canStop) {
                Button(onClick = { graph.stop() }) { Text("Stop") }
            } else {
                Button(onClick = { graph.session.submit(text); text = "" }, enabled = text.isNotBlank() && !localBusy && !localState.busy && modelReady) { Text("Run") }
            }
        }
    }
}

@Composable
private fun RunCard(run: RunView, session: PromptSession, active: Boolean) {
    var expanded by rememberSaveable(run.id, active) { mutableStateOf(false) }
    var elapsed by remember(run.id) { mutableStateOf(session.elapsedMillis(run)) }
    LaunchedEffect(run.id, run.finishedElapsedMs) {
        do {
            elapsed = session.elapsedMillis(run)
            if (run.finishedElapsedMs != null) break
            delay(250)
        } while (true)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(run.request, fontWeight = FontWeight.Bold)
            val seconds = elapsed / 1000
            Text("Elapsed: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall)
            if (active) {
                Text("Progress", style = MaterialTheme.typography.labelMedium)
                for (line in run.progress.takeLast(6)) {
                    Text("${elapsedLabel(line.elapsedMs)}  ${line.text}", style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                run.progress.lastOrNull()?.let {
                    Text("Current update: ${elapsedLabel((elapsed - it.elapsedMs).coerceAtLeast(0))} ago",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show details · ${run.steps.size} steps")
            }
            if (expanded) {
                for (line in run.progress) Text("${elapsedLabel(line.elapsedMs)}  ${line.text}", style = MaterialTheme.typography.bodySmall)
                for (step in run.steps) StepLine(step)
            }
            run.result?.let { r ->
                Text(resultLabel(r), fontWeight = FontWeight.Bold)
                if (expanded) Text("Evidence: ${evidenceLabel(r)} · ${r.reason}", style = MaterialTheme.typography.bodySmall)
            }
            run.error?.let {
                if (run.stopRequested) Text("Stopped. Any earlier actions remain.")
                else Text("Error: $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

internal fun elapsedLabel(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun StepLine(step: StepEvent) {
    Column {
        Text("${step.step}. ${actionLabel(step.action, redact = false)}")
        if (step.why.isNotBlank()) Text(step.why, style = MaterialTheme.typography.bodySmall)
        Text("→ ${step.outcome}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

internal fun modelLine(graph: AppGraph): String {
    val cfg = graph.modelsConfig.load() ?: return "No model configured"
    val planner = cfg.endpoint(ModelRole.Planner)
    val where = if (ModelRole.entries.any { cfg.endpoint(it).leavesDevice() }) {
        "requests leave the device (${ModelRole.entries.map { cfg.endpoint(it) }.filter { it.leavesDevice() }.map { java.net.URI(it.baseUrl).host }.distinct().joinToString()})"
    } else {
        "on this device"
    }
    val key = if (graph.secrets.has(SecretStore.MODEL_API_KEY)) "key stored" else "no API key stored"
    return "Planner: ${planner.model}; $where; $key"
}

internal fun connectedModelSummary(graph: AppGraph): String {
    val cfg = graph.modelsConfig.load() ?: return "Connected model · not configured"
    val external = ModelRole.entries.any { cfg.endpoint(it).leavesDevice() }
    return "Connected · ${cfg.planner.model} · " + if(external) "model requests and context leave this device" else "model requests use a server on this device"
}
