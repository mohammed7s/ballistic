// Settings, behind the gear (docs/UX_SYNTHESIS.md §4.5): a plain grouped list. Requests, app
// rules, phone permissions, connected AI, what Ballistic can do here, about and diagnostics. The
// per-app policy table (observe / act / route / confirm; core/README.md §9) and model changes need
// the command gate unlocked (core/README.md §15: policy is written "behind the command gate");
// saving a rule needs a fresh strong biometric. The table can set any value the file allows;
// none of them lowers the code-constant hard-confirmation list (ConfirmFloor).

package app.ballistic.assistant.ui

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.net.Uri
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.adapters.InstalledApps
import app.ballistic.assistant.adapters.NotificationScreenService
import app.ballistic.assistant.core.Act
import app.ballistic.assistant.core.AppAccess
import app.ballistic.assistant.core.AppPolicy
import app.ballistic.assistant.core.ConfirmMode
import app.ballistic.assistant.core.Observe
import app.ballistic.assistant.core.Route
import app.ballistic.assistant.debug.DebugTools
import app.ballistic.assistant.models.Endpoint
import app.ballistic.assistant.models.ModelsConfig
import app.ballistic.assistant.session.LabelStyle
import app.ballistic.assistant.storage.SecretStore

private enum class Group { Requests, AppRules, Permissions, Connected, About }

@Composable
fun SettingsScreen(graph: AppGraph, activity: Activity, onOpen: (Screen) -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val unlocked by graph.gate.unlocked.collectAsState()
    val run by graph.session.state.collectAsState()
    val localBusy by graph.localAi.busy.collectAsState()
    var policy by remember { mutableStateOf(graph.policyStore.load()) }
    val apps = remember { InstalledApps.list(activity) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var approving by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Group?>(null) }
    var gateFailure by remember { mutableStateOf<String?>(null) }
    val canEdit = unlocked && run.canSubmit && run.pendingRequest == null && !localBusy && !approving
    val notificationsOn = activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    val allowed = apps.count { AppAccess.canOpen(policy.forApp(it.`package`)) || policy.forApp(it.`package`).observe != Observe.None }
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
        val description = "Read: ${policyLabel(changed.observe)}\nAct: ${policyLabel(changed.act)}\nAI route: ${policyLabel(changed.route)}\nApproval: ${policyLabel(changed.confirm)}"
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

    @Composable
    fun GroupRow(group: Group?, title: String, summary: String, onClick: (() -> Unit)? = null) {
        val isOpen = group != null && open == group
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button) { if (onClick != null) onClick() else open = if (isOpen) null else group }
            .padding(vertical = Space.s2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (onClick != null) "›" else if (isOpen) "▾" else "▸", color = c.onSurfaceVariant)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }

    LazyColumn(Modifier.padding(horizontal = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        item {
            GroupRow(Group.Requests, "Requests", if (unlocked) "Allowed while your phone is unlocked" else "Locked")
            if (open == Group.Requests) Column(Modifier.padding(start = Space.s3, bottom = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    StatusLabel(if (unlocked) "Allowed" else "Requests locked", if (unlocked) LabelStyle.Outlined else LabelStyle.Inverted)
                    if (unlocked) BButton("Lock requests", { graph.gate.lock() })
                    else BButton("Enable requests", {
                        graph.gate.authenticate(activity) { e -> gateFailure = e; if (e == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled() }
                    }, emphasis = true)
                }
                Text("Your phone unlock lets you give requests. Switching apps keeps access; sleeping or locking the phone blocks new requests. Sensitive actions still need your fingerprint in the approval window.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                gateFailure?.let { WarnBlock("Not unlocked", it) }
            }
            Hairline()
        }

        item {
            GroupRow(Group.AppRules, "App rules", "$allowed ${if (allowed == 1) "app" else "apps"} allowed · new apps start blocked")
        }
        if (open == Group.AppRules) {
            item {
                Column(Modifier.padding(start = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text("These rules work inside Android's permissions. They can't grant access Android hasn't given. Reading notifications and opening apps are separate choices; apps set to \"this phone only\" are left out when a connected server is selected.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Text("Saving needs your fingerprint. Finish or stop any task before editing.", style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
                    accessMessage?.let { Text(it, fontWeight = FontWeight.Bold) }
                    OutlinedTextField(appSearch, { appSearch = it }, label = { Text("Find an app") }, modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraSmall, singleLine = true)
                }
            }
            items(apps.filter { it.label.contains(appSearch, ignoreCase = true) || it.`package`.contains(appSearch, ignoreCase = true) }, key = { it.`package` }) { app ->
                val p = policy.forApp(app.`package`)
                var expanded by remember { mutableStateOf(false) }
                var draft by remember(p) { mutableStateOf(p) }
                Column(Modifier.padding(start = Space.s3)) {
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = Space.s2)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (expanded) "▾" else "▸", color = c.onSurfaceVariant)
                        }
                        Text(ruleSummary(p), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    if (expanded) Column(Modifier.padding(bottom = Space.s2)) {
                        Text(app.`package`, style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
                        Choice("Read", draft.observe, listOf(Observe.None, Observe.Notifications), canEdit) { draft = draft.copy(observe = it) }
                        Choice("Act", draft.act, listOf(Act.None, Act.Navigate), canEdit) { draft = draft.copy(act = it) }
                        Choice("AI route", draft.route, Route.entries, canEdit) { draft = draft.copy(route = it) }
                        Choice("Approval", draft.confirm, ConfirmMode.entries, canEdit) { draft = draft.copy(confirm = it) }
                        BButton("Save with fingerprint", { saveRule(app.`package`, app.label, p, draft) }, Modifier.fillMaxWidth(),
                            enabled = canEdit && draft != p, emphasis = true)
                    }
                    Hairline()
                }
            }
        }
        item { Hairline() }

        item {
            GroupRow(Group.Permissions, "Phone permissions", "Android settings · notification access · task notifications")
            if (open == Group.Permissions) Column(Modifier.padding(start = Space.s3, bottom = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("Android controls Ballistic's access to phone resources. App rules restrict what Ballistic may do within those permissions; they cannot grant system access.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                BButton("Open Android settings for Ballistic", {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                })
                Text("If your phone offers a Network permission per app, turning it off for Ballistic after model setup keeps it offline at the OS level. Downloads and a connected server then need it on again.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Hairline()
                KeyValue("Notification access", if (NotificationScreenService.connected != null) "Granted" else "Not granted", mono = false)
                Text(if (NotificationScreenService.connected != null) "Android makes notifications available to Ballistic. Only apps you allow in App rules are passed to the assistant."
                    else "Ballistic cannot read notifications. Local model setup does not need this permission. It does not enable taps or screen control.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                BButton("Open notification access", { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                Hairline()
                KeyValue("Task notifications", if (notificationsOn) "On" else "Off", mono = false)
                Text(if (notificationsOn) "Background progress and Stop appear in a notification." else "Turn them on to see background progress and Stop outside Ballistic.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                BButton("Task notification settings", {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                })
            }
            Hairline()
        }

        item {
            GroupRow(Group.Connected, "Connected AI", "Server, model, API key · used only when you choose it in Local AI")
            if (open == Group.Connected) Column(Modifier.padding(start = Space.s3, bottom = Space.s2)) {
                ModelSettings(graph, enabled = unlocked && run.canSubmit && run.pendingRequest == null && !localBusy)
            }
            Hairline()
        }

        item {
            GroupRow(null, "What Ballistic can do here", "What works now, what doesn't yet, and what never will", onClick = { onOpen(Screen.Capabilities) })
            Hairline()
        }

        item {
            GroupRow(Group.About, "About & diagnostics", if (graph.engine.current is app.ballistic.assistant.core.FakeCoreEngine) "Engine: demo (not real)" else "Engine: Ballistic core")
            if (open == Group.About) Column(Modifier.padding(start = Space.s3, bottom = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                KeyValue("Engine", graph.engine.description, mono = false)
                KeyValue("Build", "${app.ballistic.assistant.BuildConfig.VERSION_NAME} (${if (app.ballistic.assistant.BuildConfig.DEBUG) "debug" else "release"})")
                KeyValue("Runtimes", "llama.cpp CPU · LiteRT-LM ${app.ballistic.assistant.BuildConfig.LITERTLM_VERSION}")
                Text("Licences: llama.cpp MIT (notices in the app's assets); LiteRT-LM and the LiteRT NPU runtime Apache-2.0. Model licences are separate.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                if (DebugTools.AVAILABLE) DebugImport(graph, enabled = run.canSubmit && run.pendingRequest == null && !localBusy)
            }
            Hairline()
        }
    }
}

/** One plain line per app: what it can do, what is read, where its content may go. */
private fun ruleSummary(p: AppPolicy): String {
    if (p.confirm == ConfirmMode.NeverAutomate) return "Blocked · never act"
    val open = if (p.act == Act.None) "Can't open" else "Can open"
    val read = if (p.observe == Observe.None) "reads nothing" else "reads notifications"
    val route = if (p.route == Route.DeviceOnly) "this phone only" else "selected AI"
    return "$open · $read · $route"
}


/** Debug builds: re-run the adb file import (DebugTools) and show what it did. */
@Composable
private fun DebugImport(graph: AppGraph, enabled: Boolean) {
    var notes by remember { mutableStateOf(graph.importNotes) }
    Column {
        BButton("Import files/models_key and files/models_import.json (debug)", { notes = graph.importDebugFiles().ifEmpty { listOf("Nothing to import.") } }, enabled = enabled)
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
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.DropdownList) { open = true },
            verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${policyLabel(value)} ▾", Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(text = { Text(policyLabel(option)) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

private fun policyLabel(value: Enum<*>): String = when (value) {
    Observe.None -> "Nothing"
    Observe.Notifications -> "Notifications"
    Observe.Tree, Observe.TreeScreenshot -> "Notifications (old rule; no extra access)"
    Act.None -> "Nothing"
    Act.Navigate -> "Open the app"
    Act.Full -> "Open the app (old rule; no extra access)"
    Route.DeviceOnly -> "This phone only"
    Route.Default -> "Selected AI (may leave the phone)"
    ConfirmMode.DefaultList -> "Sensitive actions"
    ConfirmMode.Always -> "Every action"
    ConfirmMode.NeverAutomate -> "Never act"
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
            OutlinedButton(
                enabled = enabled && baseUrl.isNotBlank() && model.isNotBlank(),
                onClick = {
                    if (!graph.gate.canAcceptCommands() || !graph.session.state.value.canSubmit || graph.localAi.busy.value) return@OutlinedButton
                    graph.modelsConfig.save((graph.modelsConfig.load() ?: ModelsConfig(Endpoint(baseUrl, model))).let { it.copy(planner = it.planner.copy(baseUrl = baseUrl.trim(), model = model.trim())) })
                    if (key.isNotEmpty()) {
                        graph.secrets.put(SecretStore.MODEL_API_KEY, key)
                        key = ""
                        hasKey = true
                    }
                    saved = "Connection saved. Choose Connected on Local AI to use it."
                },
                shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
            ) { Text("Save") }
            if (hasKey) BButton("Forget key", { graph.secrets.remove(SecretStore.MODEL_API_KEY); hasKey = false }, enabled = enabled)
        }
        saved?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
