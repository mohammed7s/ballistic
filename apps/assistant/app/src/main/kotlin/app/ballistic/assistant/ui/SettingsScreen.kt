// Settings: model endpoint and key, notification access, what this build can do, and the
// per-app policy table (observe / act / route / confirm; core/README.md §9). Policy and model
// changes need the command gate unlocked (core/README.md §15: policy is written "behind the
// command gate"). The table can set any value the file allows; none of them lowers the
// code-constant hard-confirmation list (ConfirmFloor).

package app.ballistic.assistant.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.adapters.InstalledApps
import app.ballistic.assistant.adapters.IntentHands
import app.ballistic.assistant.adapters.NotificationScreenService
import app.ballistic.assistant.core.Act
import app.ballistic.assistant.core.AppPolicy
import app.ballistic.assistant.core.ConfirmMode
import app.ballistic.assistant.core.Observe
import app.ballistic.assistant.core.Route
import app.ballistic.assistant.debug.DebugTools
import app.ballistic.assistant.models.Endpoint
import app.ballistic.assistant.models.ModelsConfig
import app.ballistic.assistant.storage.SecretStore

@Composable
fun SettingsScreen(graph: AppGraph, activity: Activity) {
    val unlocked by graph.gate.unlocked.collectAsState()
    val run by graph.session.state.collectAsState()
    val localBusy by graph.localAi.busy.collectAsState()
    var policy by remember { mutableStateOf(graph.policyStore.load()) }
    val apps = remember { InstalledApps.list(activity) }

    LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SessionAccess(graph, activity) }
        item {
            Text("Phone permissions are separate. Notification access allows reading notifications; it does not enable taps or screen control. This build has no Computer Control integration.",
                style = MaterialTheme.typography.bodySmall)
        }
        item { Section("Engine") }
        item { Text(graph.engine.description) }
        if (DebugTools.AVAILABLE) {
            item { DebugImport(graph, enabled = run.canSubmit && run.pendingRequest == null && !localBusy) }
        }

        item { Section("Connected model") }
        item { ModelSettings(graph, enabled = unlocked && run.canSubmit && run.pendingRequest == null && !localBusy) }

        item { Section("Notification access") }
        item {
            Column {
                Text(if (NotificationScreenService.connected != null) "Granted: Ballistic can read notifications." else "Not granted: Ballistic cannot read notifications. Local model setup does not need this permission.")
                OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open system settings") }
            }
        }

        item { Section("What this build can do") }
        item {
            Column {
                Text("Core operations: ${IntentHands.OPERATIONS.joinToString()}.")
                Text("Tap, type, scroll, back and home need Computer Control, which this build does not have.", style = MaterialTheme.typography.bodySmall)
            }
        }

        item { Section("Apps") }
        item {
            Column {
                if (!unlocked) Text("Authenticate above to change these.", color = MaterialTheme.colorScheme.error)
                Text("Send, pay, delete, post, install and grant always need your fingerprint, whatever is set here.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(apps, key = { it.`package` }) { app ->
            val p = policy.forApp(app.`package`)
            fun save(changed: AppPolicy) {
                if (!graph.gate.unlocked.value) return
                policy = policy.with(app.`package`, changed)
                graph.policyStore.save(policy)
            }
            Column {
                Text(app.label, fontWeight = FontWeight.Bold)
                Text(app.`package`, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Choice("observe", p.observe, Observe.entries, unlocked) { save(p.copy(observe = it)) }
                    Choice("act", p.act, Act.entries, unlocked) { save(p.copy(act = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Choice("route", p.route, Route.entries, unlocked) { save(p.copy(route = it)) }
                    Choice("confirm", p.confirm, ConfirmMode.entries, unlocked) { save(p.copy(confirm = it)) }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Debug builds: re-run the adb file import (DebugTools) and show what it did. */
@Composable
private fun DebugImport(graph: AppGraph, enabled: Boolean) {
    var notes by remember { mutableStateOf(graph.importNotes) }
    Column {
        OutlinedButton(enabled = enabled, onClick = { notes = graph.importDebugFiles().ifEmpty { listOf("Nothing to import.") } }) {
            Text("Import files/models_key and files/models_import.json (debug)")
        }
        for (note in notes) Text(note, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun <T : Enum<T>> Choice(name: String, value: T, options: List<T>, enabled: Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("$name: ${value.name}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(text = { Text(option.name) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

/** Planner endpoint and model; other roles fall back to it (ModelsConfig). The key is write-only. */
@Composable
private fun ModelSettings(graph: AppGraph, enabled: Boolean) {
    val current = remember { graph.modelsConfig.load() }
    var baseUrl by remember { mutableStateOf(current?.planner?.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta/openai/") }
    var model by remember { mutableStateOf(current?.planner?.model ?: "") }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(graph.secrets.has(SecretStore.MODEL_API_KEY)) }
    var saved by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Server address (OpenAI-compatible)") })
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Model") })
        OutlinedTextField(
            key, { key = it }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(if (hasKey) "API key (stored; type to replace)" else "API key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = enabled && baseUrl.isNotBlank() && model.isNotBlank(),
                onClick = {
                    if (!graph.gate.unlocked.value || !graph.session.state.value.canSubmit || graph.localAi.busy.value) return@Button
                    graph.modelsConfig.save((graph.modelsConfig.load() ?: ModelsConfig(Endpoint(baseUrl, model))).let { it.copy(planner = it.planner.copy(baseUrl = baseUrl.trim(), model = model.trim())) })
                    if (key.isNotEmpty()) {
                        graph.secrets.put(SecretStore.MODEL_API_KEY, key)
                        key = ""
                        hasKey = true
                    }
                    saved = "Connection saved. Choose Connected on Local AI to use it."
                },
            ) { Text("Save") }
            if (hasKey) OutlinedButton(enabled = enabled, onClick = { graph.secrets.remove(SecretStore.MODEL_API_KEY); hasKey = false }) { Text("Forget key") }
        }
        saved?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
