// The prompt box: the Android counterpart of harness/chat.py. Type a request, watch each step
// (action, why, outcome) as core reports it, Stop at any time. Shows which engine and which
// model are in use and whether requests leave the device, and the command gate's state.

package app.ballistic.assistant.ui

import android.app.Activity
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
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.models.leavesDevice
import app.ballistic.assistant.session.Phase
import app.ballistic.assistant.session.RunView
import app.ballistic.assistant.session.actionLabel
import app.ballistic.assistant.session.evidenceLabel
import app.ballistic.assistant.session.resultLabel

@Composable
fun PromptScreen(graph: AppGraph, activity: Activity) {
    val state by graph.session.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var gateMessage by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    fun unlock() {
        graph.gate.authenticate(activity) { failure ->
            gateMessage = failure
            if (failure == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled()
        }
    }

    // A request parked by a locked gate brings up the prompt at once.
    LaunchedEffect(state.phase) { if (state.phase == Phase.NeedsUnlock) unlock() }
    LaunchedEffect(state.runs.size, state.runs.lastOrNull()?.steps?.size) {
        if (state.runs.isNotEmpty()) list.animateScrollToItem(state.runs.size - 1)
    }

    Column(Modifier.padding(12.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.engine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        Text(modelLine(graph), style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (unlocked) "Unlocked" else "Locked", fontWeight = FontWeight.Bold,
                color = if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (unlocked) OutlinedButton(onClick = { graph.gate.lock() }) { Text("Lock") }
            else OutlinedButton(onClick = { unlock() }) { Text("Unlock") }
        }
        gateMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.runs, key = { it.id }) { RunCard(it) }
        }

        state.activity?.let { Text("Now: $it", style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                enabled = state.canSubmit,
                placeholder = { Text("Ask Ballistic") },
            )
            if (state.canStop) {
                Button(onClick = { graph.stop() }) { Text("Stop") }
            } else {
                Button(onClick = { graph.session.submit(text); text = "" }, enabled = text.isNotBlank()) { Text("Run") }
            }
        }
    }
}

@Composable
private fun RunCard(run: RunView) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(run.request, fontWeight = FontWeight.Bold)
            for (step in run.steps) StepLine(step)
            run.result?.let { r ->
                Text(resultLabel(r), fontWeight = FontWeight.Bold)
                Text("Evidence: ${evidenceLabel(r)} · ${r.reason}", style = MaterialTheme.typography.bodySmall)
            }
            run.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun StepLine(step: StepEvent) {
    Column {
        Text("${step.step}. ${actionLabel(step.action, redact = false)}")
        if (step.why.isNotBlank()) Text(step.why, style = MaterialTheme.typography.bodySmall)
        Text("→ ${step.outcome}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

private fun modelLine(graph: AppGraph): String {
    val cfg = graph.modelsConfig.load() ?: return "No model configured"
    val planner = cfg.endpoint(ModelRole.Planner)
    val where = if (ModelRole.entries.any { cfg.endpoint(it).leavesDevice() }) {
        "requests leave the device (${ModelRole.entries.map { cfg.endpoint(it) }.filter { it.leavesDevice() }.map { java.net.URI(it.baseUrl).host }.distinct().joinToString()})"
    } else {
        "on this device"
    }
    return "Planner: ${planner.model}; $where"
}
