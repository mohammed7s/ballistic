// Activity: phone tasks, newest first, as a flat ruled list (docs/UX_SYNTHESIS.md §4.6). The
// request is shown; typed text, questions and answers inside steps stay hidden until the owner
// reveals them. Built from this process's runs and the action log (core's step events). Chat
// replies are not here; they stay in Ask. Read-only, apart from Stop on a running task.

package app.ballistic.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.session.LabelStyle
import app.ballistic.assistant.session.Phase
import app.ballistic.assistant.session.evidenceLabel
import app.ballistic.assistant.session.logOutcome
import app.ballistic.assistant.session.runOutcome
import app.ballistic.assistant.session.runStatusLine
import java.text.DateFormat
import java.util.Date

@Composable
fun ActivityScreen(graph: AppGraph) {
    val c = MaterialTheme.colorScheme
    var showContent by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val session by graph.session.state.collectAsState()
    val log = remember(reload, session.runs.sumOf { it.steps.size }, session.phase) { graph.actionLog.read() }
    val groups = remember(log) { log.lines.groupBy { it.run } }
    // Phone tasks only; chat replies stay in Ask (owner decision, UX_SYNTHESIS §8 question 9).
    val runIds = (session.runs.asReversed().filter { it.isTask && !it.restored }.map { it.id } + groups.keys.reversed()).distinct()
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Column(Modifier.padding(horizontal = Space.s3)) {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s1), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text("Show typed text:", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            TextLink(if (showContent) "on" else "off", { showContent = !showContent })
            Box(Modifier.weight(1f))
            TextLink("Refresh", { reload++ })
        }
        Hairline()
        if (log.skipped > 0) Text("${log.skipped} records couldn't be read", Modifier.padding(vertical = Space.s2),
            style = MaterialTheme.typography.bodySmall)
        if (runIds.isEmpty()) Text("No phone tasks yet. Chat messages are kept in Ask.", Modifier.padding(vertical = Space.s4),
            color = c.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(runIds, key = { it }) { id ->
                val lines = groups[id].orEmpty()
                val run = session.runs.firstOrNull { it.id == id }
                val running = run != null && !run.finished && session.canStop && session.runs.lastOrNull()?.id == id
                val awaiting = running && session.phase == Phase.AwaitingApproval
                val steps = run?.steps ?: lines.map { it.event }
                val outcome = when {
                    running -> null
                    run != null -> runOutcome(run)
                    else -> lines.lastOrNull()?.let { logOutcome(it.event, showContent) }
                }
                val request = run?.request ?: lines.lastOrNull()?.request.orEmpty()
                val whenText = lines.firstOrNull()?.let { time.format(Date(it.timeMillis)) } ?: "now"
                Column(Modifier.fillMaxWidth().padding(vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        when {
                            awaiting -> StatusLabel("Needs you", LabelStyle.Inverted)
                            running -> { LiveMarker(); Text("RUNNING", style = MaterialTheme.typography.labelSmall) }
                            outcome != null -> StatusLabel(outcome.label, outcome.style)
                        }
                        Box(Modifier.weight(1f))
                        Text(whenText, style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
                    }
                    Text(request, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val summary = listOfNotNull(
                        if (running && run != null) runStatusLine(run, awaiting) else outcome?.line,
                        "${steps.size} ${if (steps.size == 1) "step" else "steps"}",
                        run?.takeIf { !running }?.let { elapsedLabel(graph.session.elapsedMillis(it)) },
                        run?.route,
                    )
                    Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        maxLines = 6, overflow = TextOverflow.Ellipsis)
                    if (running) BButton(if (run?.stopRequested == true) "Stopping…" else "Stop", { graph.stop() }, enabled = run?.stopRequested != true)
                    Disclosure("What happened", key = "activity-$id") {
                        if (steps.isEmpty()) Text("No steps were recorded.", style = MaterialTheme.typography.bodySmall)
                        Text("Did", style = MaterialTheme.typography.labelLarge)
                        for (step in steps) StepLine(step, showContent)
                        run?.result?.let { KeyValue("Checked by", evidenceLabel(it), mono = false) }
                            ?: lines.lastOrNull()?.let { KeyValue("Checked by", it.event.verification.evidenceBasis.replace('_', ' '), mono = false) }
                        lines.lastOrNull()?.let { KeyValue("Engine", it.engine, mono = false) }
                        Disclosure("Raw record", key = "activity-raw-$id") {
                            run?.progress?.forEach { entry ->
                                Text("${elapsedLabel(entry.elapsedMs)}  ${entry.text}", style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
                            }
                            for (line in lines) {
                                val e = line.event
                                Text("step ${e.step} · task ${e.taskStatus.name} · execution ${e.executionStatus.name}" +
                                    (e.failureCategory?.let { " · $it" } ?: "") + " · ${e.verification.evidenceBasis}",
                                    style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
                Hairline()
            }
        }
    }
}
