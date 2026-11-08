// Ask: one thread for chat and phone tasks (docs/UX_SYNTHESIS.md §4.2). Each message is one core
// turn: a reply shows as plain text with the route it used; a task shows as a ruled box with the
// request, one live line (or the result), Stop and a details disclosure. At most the three most
// recent items show here; tasks are also in Activity. Stop is also in the task bar on every tab.

package app.ballistic.assistant.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.adapters.InstalledApps
import app.ballistic.assistant.confirm.ConfirmActivity
import app.ballistic.assistant.core.AppAccess
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.UniffiCoreEngine
import app.ballistic.assistant.models.ModelBackend
import app.ballistic.assistant.models.leavesDevice
import app.ballistic.assistant.session.ExchangeKind
import app.ballistic.assistant.session.LabelStyle
import app.ballistic.assistant.session.Phase
import app.ballistic.assistant.session.PromptSession
import app.ballistic.assistant.session.RunView
import app.ballistic.assistant.session.actionLabel
import app.ballistic.assistant.session.errorLine
import app.ballistic.assistant.session.evidenceLabel
import app.ballistic.assistant.session.recentForAsk
import app.ballistic.assistant.session.runOutcome
import app.ballistic.assistant.session.runStatusLine
import app.ballistic.assistant.storage.SecretStore
import kotlinx.coroutines.delay

@Composable
fun PromptScreen(graph: AppGraph, activity: Activity, onOpen: (Screen) -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val state by graph.session.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    val localBusy by graph.localAi.busy.collectAsState()
    val localState by graph.localModels.state.collectAsState()
    val selection by graph.localAi.selection.collectAsState()
    val installedModel by graph.localAi.model.collectAsState()
    val backgroundNotice by graph.backgroundNotice.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var gateMessage by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val localSelected = selection.backend == ModelBackend.Local
    val realEngine = graph.engine.current is UniffiCoreEngine
    val modelLoaded = localState.metadata != null
    val modelReady = !localSelected || (modelLoaded && realEngine)
    val shown = recentForAsk(state.runs)
    val older = state.runs.size - shown.size
    val suggestions = remember(state.runs.isEmpty()) { suggestions(graph, activity) }

    fun unlock() {
        graph.gate.authenticate(activity) { failure ->
            gateMessage = failure
            if (failure == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled()
        }
    }
    fun send() {
        if (text.isBlank() || !state.canSubmit || localBusy || localState.busy || !modelReady) return
        graph.session.submit(text); text = ""
    }

    // Which engine takes the next turn depends on Settings (model configured, library present).
    LaunchedEffect(Unit) { graph.session.refreshEngine() }
    // A request parked by a locked gate brings up the prompt at once.
    LaunchedEffect(state.phase) { if (state.phase == Phase.NeedsUnlock) unlock() }
    LaunchedEffect(state.runs.size, state.runs.lastOrNull()?.steps?.size, state.runs.lastOrNull()?.kind) {
        if (state.runs.isNotEmpty()) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }

    Column(Modifier.imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.s3), state = list,
            verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (older > 0) "$older earlier · tasks are in Activity" else "", Modifier.weight(1f),
                        style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
                    Box {
                        Box(Modifier.heightIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = "More") { menu = true }
                            .padding(horizontal = Space.s3, vertical = Space.s2).semantics { contentDescription = "More" }) {
                            Text("⋮", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
                        }
                        DropdownMenu(menu, { menu = false }, shape = androidx.compose.ui.graphics.RectangleShape) {
                            DropdownMenuItem(text = { Text("Clear conversation") }, enabled = state.canSubmit && state.runs.isNotEmpty(),
                                onClick = { menu = false; graph.session.clearConversation() })
                            DropdownMenuItem(text = { Text(if (unlocked) "Lock requests" else "Enable requests") },
                                onClick = { menu = false; if (unlocked) graph.gate.lock() else unlock() })
                            DropdownMenuItem(text = { Text("What I can do here") }, onClick = { menu = false; onOpen(Screen.Capabilities) })
                        }
                    }
                }
            }
            gateMessage?.let { m -> item { WarnBlock("Requests locked", m) } }
            backgroundNotice?.let { m -> item { WarnBlock("Interrupted", m) } }
            if (localSelected && !modelReady) item {
                if (installedModel == null) StateBox("Set up AI first", LabelStyle.Outlined, "No model on this phone",
                    "Download the starter (${graph.localAi.starter.name}) or pick another model in Local AI.") {
                    BButton("Set up AI", { onOpen(Screen.LocalAi) }, emphasis = true)
                } else if (!realEngine) StateBox("Not available", LabelStyle.Inverted, "Ballistic's core isn't in this build",
                    "This build can only replay a demo script.") {}
                else Row(Modifier.fillMaxWidth().ruledBox(c.outlineVariant), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    StatusLabel(if (localState.busy) "Loading" else "Not loaded")
                    Text(localState.error?.let { "Model didn't load" } ?: "Model isn't loaded", Modifier.weight(1f))
                    BButton("Load", { graph.localAi.load() }, enabled = !localBusy && !localState.busy && state.canSubmit)
                }
            }
            if (state.runs.isEmpty()) item {
                Column(Modifier.padding(top = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Text("Ask a question, or ask me to do something on this phone.", style = MaterialTheme.typography.bodyLarge)
                    TextLink("What I can do here", { onOpen(Screen.Capabilities) })
                    if (suggestions.isNotEmpty()) {
                        SectionHeading("Try")
                        suggestions.forEach { s ->
                            Text("› ${s.label}", Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .clickable(role = Role.Button) { if (s.screen != null) onOpen(s.screen) else text = s.label }
                                .padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            Hairline()
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { run ->
                val active = state.canStop && run.id == state.runs.lastOrNull()?.id
                Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Text("› ${run.request}", style = MaterialTheme.typography.bodyLarge)
                    when {
                        run.isTask -> TaskBox(run, graph.session, active, awaitingApproval = active && state.phase == Phase.AwaitingApproval,
                            notificationsOff = active && !activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                            onReview = { activity.startActivity(Intent(activity, ConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
                            onStop = { graph.stop() },
                            onBackground = { activity.moveTaskToBack(true) },
                            onNotificationSettings = {
                                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                            },
                            onCapabilities = { onOpen(Screen.Capabilities) })
                        active -> Text(if (run.stopRequested) "Stopping…" else "Thinking…", Modifier.padding(start = Space.s3),
                            style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                        else -> ReplyLines(run)
                    }
                }
            }
            item { Box(Modifier.heightIn(min = Space.s2)) }
        }

        // Composer.
        Hairline()
        Row(Modifier.fillMaxWidth().background(c.surfaceContainer).padding(horizontal = Space.s3, vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            val placeholder = when {
                state.canStop -> "Task running. Stop it to send another."
                !unlocked -> "Requests are locked"
                localSelected && !modelReady -> "Set up AI to start"
                else -> "Message Ballistic"
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                enabled = state.canSubmit && !localBusy,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Text("›", color = c.onSurfaceVariant) },
                shape = MaterialTheme.shapes.extraSmall, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = c.surfaceContainerHigh, unfocusedContainerColor = c.surfaceContainerHigh,
                    disabledContainerColor = c.surfaceContainer, focusedBorderColor = c.onSurface, unfocusedBorderColor = c.outline),
            )
            if (!unlocked && state.canSubmit && text.isBlank()) BButton("Enable", { unlock() })
            else BButton("Send", { send() }, enabled = text.isNotBlank() && state.canSubmit && !localBusy && !localState.busy && modelReady)
        }
    }
}

private data class Suggestion(val label: String, val screen: Screen? = null)

/** Only what works now: open apps the owner allowed, and the local speed check. */
private fun suggestions(graph: AppGraph, activity: Activity): List<Suggestion> {
    val policy = graph.policyStore.load()
    val apps = runCatching { InstalledApps.list(activity) }.getOrDefault(emptyList())
        .filter { AppAccess.canOpen(policy.forApp(it.`package`)) }.take(2)
        .map { Suggestion("Open ${it.label}") }
    val speed = if (graph.localModels.state.value.metadata != null) listOf(Suggestion("Check local AI speed", Screen.LocalAi)) else emptyList()
    return (apps + speed).take(3)
}

/** A state that needs the owner, as one ruled unit with a label, a line and an action. */
@Composable
private fun StateBox(label: String, style: LabelStyle, title: String, body: String, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().ruledBox(MaterialTheme.colorScheme.outlineVariant), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        StatusLabel(label, style)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

/** A chat reply, question or fixed turn: plain text, then where the model ran (no phone actions). */
@Composable
private fun ReplyLines(run: RunView) {
    Column(Modifier.padding(start = Space.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val text = when (run.kind) {
            ExchangeKind.Error -> errorLine(run.error.orEmpty())
            else -> run.reply ?: "No reply"
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
        val meta = listOfNotNull(run.route, if (run.kind == ExchangeKind.Clarify) "question for you" else null, "no phone actions")
        Text(meta.joinToString(" · "), style = LocalBallisticExtras.current.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The task box: label and elapsed time, line 1 the owner's request, line 2 what is happening or
 * the plain result (an answer in full), controls while running, then the details disclosure.
 * Finished boxes show their result without expanding; details never open on their own.
 */
@Composable
private fun TaskBox(
    run: RunView, session: PromptSession, active: Boolean, awaitingApproval: Boolean, notificationsOff: Boolean,
    onReview: () -> Unit, onStop: () -> Unit, onBackground: () -> Unit, onNotificationSettings: () -> Unit, onCapabilities: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var elapsed by remember(run.id) { mutableLongStateOf(session.elapsedMillis(run)) }
    LaunchedEffect(run.id, run.finishedElapsedMs) {
        do {
            elapsed = session.elapsedMillis(run)
            if (run.finishedElapsedMs != null) break
            delay(250)
        } while (true)
    }
    val outcome = if (run.finished) runOutcome(run) else null
    Column(Modifier.fillMaxWidth().ruledBox(if (active) c.outline else c.outlineVariant), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            when {
                awaitingApproval -> StatusLabel("Needs you", LabelStyle.Inverted)
                outcome != null -> StatusLabel(outcome.label, outcome.style)
                else -> Text("PHONE TASK", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            Box(Modifier.weight(1f))
            if (active && !awaitingApproval) LiveMarker()
            if (!run.restored) Text(elapsedLabel(elapsed), style = LocalBallisticExtras.current.mono)
        }
        Text(run.request, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (awaitingApproval) "Waiting for your approval" else runStatusLine(run, awaitingApproval),
            style = if (run.finished) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            maxLines = if (active) 1 else 12, overflow = TextOverflow.Ellipsis,
            color = if (run.finished) c.onSurface else c.onSurfaceVariant)
        if (outcome?.label == "NOT SUPPORTED") TextLink("See what I can do", onCapabilities)
        if (active) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                if (awaitingApproval) BButton("Review", onReview, emphasis = true)
                BButton(if (run.stopRequested) "Stopping…" else "Stop", onStop, enabled = !run.stopRequested)
                if (!awaitingApproval) BButton("Continue in background", onBackground)
            }
            if (notificationsOff) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("Task notifications are off.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                TextLink("Turn on", onNotificationSettings)
            }
        }
        if (!run.restored) {
            Hairline()
            Disclosure("Details" + if (run.steps.isNotEmpty()) " · ${run.steps.size} ${if (run.steps.size == 1) "step" else "steps"}" else "", key = "details-${run.id}") {
                TaskDetails(run, elapsed, showContent = true)
            }
        }
    }
}

/** Result, evidence, the steps (what, why, outcome) and the raw progress feed. */
@Composable
internal fun TaskDetails(run: RunView, elapsedMs: Long?, showContent: Boolean) {
    val c = MaterialTheme.colorScheme
    val mono = LocalBallisticExtras.current.monoSmall
    if (run.finished) KeyValue("Result", runOutcome(run).label.lowercase().replaceFirstChar { it.uppercase() }, mono = false)
    elapsedMs?.let { KeyValue("Elapsed", elapsedLabel(it)) }
    run.result?.let { KeyValue("Checked by", evidenceLabel(it), mono = false) }
    run.route?.let { KeyValue("AI", it) }
    run.ranAs?.let { KeyValue("Ran as", if (showContent) it else "hidden", mono = false) }
    for (step in run.steps) StepLine(step, showContent)
    run.error?.let {
        if (run.stopRequested) Text("Stopped. Any earlier actions remain.", style = MaterialTheme.typography.bodySmall)
        else Text("Error: $it", style = mono, color = c.onSurfaceVariant)
    }
    if (run.progress.isNotEmpty()) Disclosure("Raw record", key = "raw-${run.id}") {
        for (line in run.progress) Text("${elapsedLabel(line.elapsedMs)}  ${line.text}", style = mono, color = c.onSurfaceVariant)
        run.result?.let { Text("reason: ${it.reason}", style = mono, color = c.onSurfaceVariant) }
    }
}

internal fun elapsedLabel(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun StepLine(step: StepEvent, showContent: Boolean) {
    Column(Modifier.padding(top = Space.s1)) {
        Text("${step.step}  ${actionLabel(step.action, redact = !showContent)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (step.why.isNotBlank()) Text("Why: ${step.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Outcome: ${step.outcome}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%.1f s".format(step.seconds) + (step.promptTokens?.let { " · $it prompt tokens" } ?: ""),
            style = LocalBallisticExtras.current.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
