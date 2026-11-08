// The frame around every screen (docs/UX_SYNTHESIS.md §4.1): top bar with the greyscale mark,
// the route label (the SPEC §6 visible indicator: outlined ON THIS PHONE, inverted LEAVES PHONE)
// and the gear; the demo-engine bar; the task bar with Stop on every tab while a turn is active;
// three text tabs at the bottom. No decision is made here.

package app.ballistic.assistant.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.core.FakeCoreEngine
import app.ballistic.assistant.session.ExchangeKind
import app.ballistic.assistant.session.LabelStyle
import app.ballistic.assistant.session.Phase
import app.ballistic.assistant.session.runStatusLine
import kotlinx.coroutines.delay

enum class Screen(val title: String, val isTab: Boolean) {
    Ask("Ask", true), Activity("Activity", true), LocalAi("Local AI", true),
    Settings("Settings", false), Capabilities("What Ballistic can do here", false),
}

@Composable
fun AppShell(
    graph: AppGraph, activity: Activity, screen: Screen,
    onOpen: (Screen) -> Unit, onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val unlocked by graph.gate.unlocked.collectAsState()
    val selection by graph.localAi.selection.collectAsState()
    val state by graph.session.state.collectAsState()
    var routeSheet by remember { mutableStateOf(false) }
    val leaves = remember(selection) { graph.leavesPhone() }
    val demo = graph.engine.current is FakeCoreEngine

    if (routeSheet) RouteSheet(graph, leaves, onDismiss = { routeSheet = false }, onOpenLocalAi = { routeSheet = false; onOpen(Screen.LocalAi) })

    Column(Modifier.fillMaxSize()) {
        // Top bar.
        Row(
            Modifier.fillMaxWidth().background(c.surfaceContainer).heightIn(min = 52.dp).padding(horizontal = Space.s3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            if (screen.isTab) {
                BallisticMark(18.dp)
                Text(if (screen == Screen.Ask) "Ballistic" else screen.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            } else {
                Text("←", Modifier.clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack).padding(end = Space.s1)
                    .semantics { contentDescription = "Back" }, style = MaterialTheme.typography.titleMedium)
                Text(screen.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!unlocked) StatusLabel("Requests locked", LabelStyle.Inverted,
                Modifier.clickable(role = Role.Button, onClickLabel = "Enable requests") {
                    graph.gate.authenticate(activity) { failure -> if (failure == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled() }
                })
            StatusLabel(if (leaves) "Leaves phone ›" else "On this phone", if (leaves) LabelStyle.Inverted else LabelStyle.Outlined,
                Modifier.clickable(role = Role.Button, onClickLabel = "Where requests go") { routeSheet = true })
            if (screen.isTab) Box(
                Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "Settings") { onOpen(Screen.Settings) }
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { Text("⚙", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant) }
        }
        Hairline()
        if (demo) Text("DEMO ENGINE · NOT REAL", Modifier.fillMaxWidth().background(c.inverseSurface).padding(horizontal = Space.s3, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = c.inverseOnSurface)

        Column(Modifier.weight(1f).fillMaxWidth(), content = content)

        // Task bar: on every screen while a turn is active; tap to go to it in Ask.
        if (state.canStop) TaskBar(graph, onOpenAsk = { onOpen(Screen.Ask) })

        Hairline()
        Row(Modifier.fillMaxWidth().background(c.surfaceContainer)) {
            for (t in listOf(Screen.Ask, Screen.Activity, Screen.LocalAi)) {
                val active = screen == t
                Column(
                    Modifier.weight(1f).heightIn(min = Space.touch)
                        .clickable(role = Role.Tab) { onOpen(t) }.semantics { selected = active },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(Space.emphasis).background(if (active) c.onSurface else c.surfaceContainer))
                    Text(t.title, Modifier.padding(vertical = 13.dp), style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                        color = if (active) c.onSurface else c.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TaskBar(graph: AppGraph, onOpenAsk: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val state by graph.session.state.collectAsState()
    val run = state.runs.lastOrNull() ?: return
    val awaiting = state.phase == Phase.AwaitingApproval
    var elapsed by remember(run.id) { mutableLongStateOf(graph.session.elapsedMillis(run)) }
    LaunchedEffect(run.id) { while (true) { elapsed = graph.session.elapsedMillis(graph.session.state.value.runs.lastOrNull() ?: run); delay(250) } }
    val line = when {
        run.kind == ExchangeKind.Pending && !run.stopRequested && !awaiting -> "Thinking…"
        else -> runStatusLine(run, awaiting)
    }
    Hairline()
    Row(
        Modifier.fillMaxWidth().background(c.surfaceContainer).clickable(onClickLabel = "Show in Ask", onClick = onOpenAsk)
            .heightIn(min = Space.touch).padding(horizontal = Space.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        if (awaiting) StatusLabel("Needs you", LabelStyle.Inverted) else LiveMarker()
        Text(line, Modifier.weight(1f), style = LocalBallisticExtras.current.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(elapsedLabel(elapsed), style = LocalBallisticExtras.current.mono, color = c.onSurfaceVariant)
        BButton(if (run.stopRequested) "Stopping…" else "Stop", onClick = { graph.stop() }, enabled = !run.stopRequested)
    }
}

/** What the route label means, and where to change it. */
@Composable
private fun RouteSheet(graph: AppGraph, leaves: Boolean, onDismiss: () -> Unit, onOpenLocalAi: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.ui.graphics.RectangleShape,
        title = { Text(if (leaves) "Requests leave this phone" else "AI runs on this phone", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                if (leaves) {
                    Text("Your messages, and the screen information a task needs, are sent to: ${graph.routeLabel().removePrefix("left the phone: ")}.")
                    Text("Apps you set to \"this phone only\" are left out. Ballistic never switches AI by itself.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Your messages and the screen information a task needs stay on this phone.")
                    Text("Other networking by the app (model downloads) is unchanged. Ballistic never switches to a connected server by itself.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { BButton("Change in Local AI", onOpenLocalAi) },
        dismissButton = { BButton("Close", onDismiss) },
    )
}
