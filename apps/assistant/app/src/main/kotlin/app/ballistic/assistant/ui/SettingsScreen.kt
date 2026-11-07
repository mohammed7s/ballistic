// Settings: model endpoint and key, notification access, what this build can do, and the
// per-app policy table (observe / act / route / confirm; core/README.md §9). Policy and model
// changes need the command gate unlocked (core/README.md §15: policy is written "behind the
// command gate"). The table can set any value the file allows; none of them lowers the
// code-constant hard-confirmation list (ConfirmFloor).

package app.ballistic.assistant.ui

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.net.Uri
import android.app.Activity
import android.app.NotificationManager
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
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var approving by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    val canEdit = unlocked && run.canSubmit && run.pendingRequest == null && !localBusy && !approving
    fun idleAndUnlocked() = graph.gate.canAcceptCommands() && graph.session.state.value.canSubmit &&
        graph.session.state.value.pendingRequest == null && !graph.localAi.busy.value
    fun saveRule(pkg: String, label: String, before: AppPolicy, changed: AppPolicy) {
        if (!idleAndUnlocked() || approving) return
        val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (activity.getSystemService(BiometricManager::class.java).canAuthenticate(strong) != BiometricManager.BIOMETRIC_SUCCESS) {
            accessMessage = "Changing app access requires an enrolled strong biometric. Set this up in phone security settings."
            return
        }
        approving = true
        accessMessage = null
        val description = "Read: ${policyLabel(changed.observe)}\nActions: ${policyLabel(changed.act)}\nModel: ${policyLabel(changed.route)}\nApproval: ${policyLabel(changed.confirm)}"
        BiometricPrompt.Builder(activity).setTitle("Save access rules")
            .setSubtitle("$label · $pkg").setDescription(description)
            .setAllowedAuthenticators(strong).setConfirmationRequired(true)
            .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> approving = false; accessMessage = "Rules unchanged." }
            .build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    approving = false; accessMessage = "Rules unchanged: $message"
                }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    approving = false
                    val latest = graph.policyStore.load()
                    if (!idleAndUnlocked() || latest.forApp(pkg) != before) {
                        accessMessage = "Rules unchanged. Finish current work and review the rules again."
                        return
                    }
                    try {
                        val next = latest.with(pkg, changed)
                        graph.policyStore.save(next)
                        policy = next
                        accessMessage = "Rules saved for $label."
                    } catch (_: Exception) { accessMessage = "Could not save rules; access was not changed." }
                }
            })
    }

    LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SessionAccess(graph, activity) }
        item { Section("Phone permissions") }
        item {
            Text("Android controls Ballistic’s access to phone resources. App access rules below restrict what Ballistic may do within those permissions; they cannot grant system access.")
            OutlinedButton(onClick = {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            }) { Text("Ballistic in phone settings") }
            Text("For an OS-enforced offline boundary, disable Ballistic’s Network permission in GrapheneOS after model setup. Downloads and connected models then need it enabled again.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Task notifications", style = MaterialTheme.typography.titleMedium)
            Text(if (activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                "Enabled: background progress and Stop appear in a notification."
                else "Disabled: enable notifications to see background progress and Stop outside Ballistic.")
            OutlinedButton(onClick = {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }) { Text("Task notification settings") }
        }
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
                Text(if (NotificationScreenService.connected != null) "Connected: Android makes notifications available to Ballistic. Only apps you allow below are passed to the assistant." else "Not granted: Ballistic cannot read notifications. Local model setup does not need this permission.")
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

        item { Section("App access rules") }
        item {
            Text("New apps start blocked. Reading notifications and opening apps are separate choices. On-device-only notifications are excluded when a connected model is selected.")
            Text("Saving rules requires a fresh strong biometric. Finish or stop any task before editing. Sensitive-action approval remains separate.", style = MaterialTheme.typography.bodySmall)
            Text("This build can read allowed notifications and open allowed apps. Screen reading, tapping, typing and full control are unavailable.", style = MaterialTheme.typography.bodySmall)
            accessMessage?.let { Text(it) }
            OutlinedTextField(appSearch, { appSearch = it }, label = { Text("Find an app") }, modifier = Modifier.fillMaxWidth())
        }
        items(apps.filter { it.label.contains(appSearch, ignoreCase = true) || it.`package`.contains(appSearch, ignoreCase = true) }, key = { it.`package` }) { app ->
            val p = policy.forApp(app.`package`)
            var expanded by remember { mutableStateOf(false) }
            var draft by remember(p) { mutableStateOf(p) }
            Column {
                Text(app.label, fontWeight = FontWeight.Bold)
                Text(app.`package`, style = MaterialTheme.typography.labelSmall)
                Text("Read: ${policyLabel(p.observe)} · Actions: ${policyLabel(p.act)}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide rules" else "Edit rules") }
                if (expanded) {
                    Choice("Read", draft.observe, listOf(Observe.None, Observe.Notifications), canEdit) { draft = draft.copy(observe = it) }
                    Choice("Actions", draft.act, listOf(Act.None, Act.Navigate), canEdit) { draft = draft.copy(act = it) }
                    Choice("Model", draft.route, Route.entries, canEdit) { draft = draft.copy(route = it) }
                    Choice("Approval", draft.confirm, ConfirmMode.entries, canEdit) { draft = draft.copy(confirm = it) }
                    Button(enabled = canEdit && draft != p, onClick = { saveRule(app.`package`, app.label, p, draft) }) { Text("Save with biometric") }
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
        TextButton(onClick = { open = true }, enabled = enabled) { Text("$name: ${policyLabel(value)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(text = { Text(policyLabel(option)) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

private fun policyLabel(value: Enum<*>): String = when (value) {
    Observe.None -> "Blocked"
    Observe.Notifications -> "Notifications"
    Observe.Tree, Observe.TreeScreenshot -> "Notifications (legacy screen rule)"
    Act.None -> "Blocked"
    Act.Navigate -> "Open app"
    Act.Full -> "Open app (legacy full rule)"
    Route.DeviceOnly -> "On device only"
    Route.Default -> "Selected backend; may leave device"
    ConfirmMode.DefaultList -> "Sensitive actions"
    ConfirmMode.Always -> "Every action"
    ConfirmMode.NeverAutomate -> "Block all actions"
    else -> value.name
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
                    if (!graph.gate.canAcceptCommands() || !graph.session.state.value.canSubmit || graph.localAi.busy.value) return@Button
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
