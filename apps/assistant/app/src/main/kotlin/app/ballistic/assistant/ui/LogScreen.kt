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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(graph: AppGraph) {
    var showContent by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val log = remember(reload) { graph.actionLog.read() }
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = showContent, onCheckedChange = { showContent = it })
            Text("Show typed text")
            OutlinedButton(onClick = { reload++ }) { Text("Reload") }
        }
        if (log.skipped > 0) Text("${log.skipped} unreadable lines skipped", color = MaterialTheme.colorScheme.error)
        if (log.lines.isEmpty()) Text("No steps logged yet.")
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(log.lines.asReversed()) { line ->
                val e = line.event
                Column {
                    Text("${time.format(Date(line.timeMillis))} · step ${e.step} · ${"%.1f".format(e.seconds)} s", style = MaterialTheme.typography.labelSmall)
                    Text(actionLabel(e.action, redact = !showContent))
                    if (e.why.isNotBlank()) Text(e.why, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "→ ${e.outcome} · task ${e.taskStatus.name} · execution ${e.executionStatus.name}" +
                            (e.failureCategory?.let { " · $it" } ?: "") +
                            (e.promptTokens?.let { " · $it prompt tokens" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("${line.engine} · request: ${if (showContent) line.request else "•••"}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
