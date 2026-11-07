// The action log: the JSON lines core reported, newest first. Typed text, questions and answers
// are hidden until the owner asks to see them (docs/ANDROID_APP.md, Activity). Read-only.

package app.ballistic.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.session.actionLabel
import app.ballistic.assistant.session.resultLabel
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(graph: AppGraph) {
    var showContent by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val session by graph.session.state.collectAsState()
    val log = remember(reload, session.runs.sumOf { it.steps.size }) { graph.actionLog.read() }
    val groups = remember(log) { log.lines.groupBy { it.run } }
    val runIds = (session.runs.asReversed().map { it.id } + groups.keys.reversed()).distinct()
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = showContent, onCheckedChange = { showContent = it })
            Text("Show typed text")
            OutlinedButton(onClick = { reload++ }) { Text("Reload") }
        }
        if (log.skipped > 0) Text("${log.skipped} unreadable lines skipped", color = MaterialTheme.colorScheme.error)
        if (runIds.isEmpty()) Text("No runs yet.")
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(runIds, key = { it }) { id ->
                var expanded by rememberSaveable(id) { mutableStateOf(false) }
                val lines = groups[id].orEmpty()
                val run = session.runs.firstOrNull { it.id == id }
                val latest = lines.lastOrNull()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (showContent) run?.request ?: latest?.request.orEmpty() else "Request hidden",
                            style = MaterialTheme.typography.titleMedium)
                        latest?.let { Text(time.format(Date(it.timeMillis)), style = MaterialTheme.typography.labelSmall) }
                        Text("${run?.steps?.size ?: lines.size} logged steps" +
                            (run?.let { " · " + when {
                                it.finishedElapsedMs == null -> "In progress"
                                it.stopRequested -> "Stopped"
                                it.error != null -> "Failed"
                                it.result != null -> it.result?.let(::resultLabel).orEmpty()
                                else -> "Ended"
                            } } ?: ""), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Hide details" else "Show details")
                        }
                        if (expanded) {
                            run?.progress?.forEach { entry ->
                                Text("${elapsedLabel(entry.elapsedMs)}  ${entry.text}", style = MaterialTheme.typography.bodySmall)
                            }
                            for (line in lines) {
                                val e = line.event
                                Text("Step ${e.step} · ${"%.1f".format(e.seconds)} s · ${actionLabel(e.action, redact = !showContent)}")
                                if (e.why.isNotBlank()) Text(e.why, style = MaterialTheme.typography.bodySmall)
                                Text("→ ${e.outcome} · task ${e.taskStatus.name} · execution ${e.executionStatus.name}" +
                                    (e.failureCategory?.let { " · $it" } ?: "") +
                                    (e.promptTokens?.let { " · $it prompt tokens" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(line.engine, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
