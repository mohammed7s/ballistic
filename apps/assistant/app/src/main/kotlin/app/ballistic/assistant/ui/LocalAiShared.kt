// Pieces shared by Local AI's three pages (main, model picker, Advanced): when changes are
// allowed, the download confirmation, file import and small formatters. Every action is the same
// LocalAiController call as before the redesign; download, verification, loading and the
// no-fallback rule are unchanged.

package app.ballistic.assistant.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.models.ComputeBackend
import app.ballistic.assistant.models.DownloadableModel
import app.ballistic.assistant.models.LocalPhase
import app.ballistic.assistant.models.ManagedModel
import app.ballistic.assistant.models.ManagedModelStore
import app.ballistic.assistant.models.ModelPicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Whether Local AI may change models now (requests enabled, no task, no other job). */
internal data class LocalAiAllowed(val change: Boolean, val startSetup: Boolean)

@Composable
internal fun localAiAllowed(graph: AppGraph): LocalAiAllowed {
    val run by graph.session.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    val busy by graph.localAi.busy.collectAsState()
    val engine by graph.localModels.state.collectAsState()
    val free = run.canSubmit && run.pendingRequest == null && !busy && !engine.busy
    return LocalAiAllowed(change = unlocked && free, startSetup = free)
}

/** Asks for the phone unlock if needed, then opens the download confirmation. */
internal fun requestDownload(graph: AppGraph, activity: Activity, id: String, onError: (String?) -> Unit) {
    onError(null)
    if (graph.gate.canAcceptCommands()) graph.confirmDownload.value = id
    else graph.gate.authenticate(activity) { error ->
        onError(error)
        if (error == null) graph.confirmDownload.value = id
    }
}

/** One line, Details behind a disclosure, then Download. Shown on whichever Local AI page is open. */
@Composable
internal fun DownloadConfirm(graph: AppGraph) {
    val id by graph.confirmDownload.collectAsState()
    val allowed = localAiAllowed(graph)
    val entry = id?.let { graph.localAi.entry(it) } ?: return
    val close = { graph.confirmDownload.value = null }
    AlertDialog(
        onDismissRequest = close,
        shape = RectangleShape,
        title = { Text("Download ${entry.name}?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ModelPicker.downloadLine(entry))
                Disclosure("Details", key = "download-details-${entry.id}") {
                    Text("Downloads ${entry.publisherFileName} from ${entry.publisher} on Hugging Face using your internet connection. Wi-Fi is recommended. Licence: ${entry.license}.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Ballistic checks the byte count and SHA-256 against its catalogue, keeps the file beside your other models, selects on-phone AI and loads it. Needs ${gib(entry.bytes + ManagedModelStore.STORAGE_RESERVE)} free storage; loading also needs available memory.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("You can leave the app during the download; progress stays in a notification. Force-stopping the app interrupts it, and it restarts from the beginning.",
                        style = MaterialTheme.typography.bodySmall)
                    ModelProvenance(entry, null)
                }
            }
        },
        confirmButton = { BButton("Download", { close(); graph.localAi.download(entry.id) }, enabled = allowed.change, emphasis = true) },
        dismissButton = { BButton("Cancel", close) },
    )
}

/** Opens the document picker for [targetId]: a catalogue id (digest-checked) or the imported-GGUF slot. */
@Composable
internal fun rememberImport(graph: AppGraph): (String) -> Unit {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // The picker may have locked the gate on backgrounding. Keep the URI until the owner
        // returns and confirms; importing must not relax the command gate.
        if (uri != null) graph.pendingModelImport.value = uri
    }
    return { target -> graph.pendingImportTarget.value = target; picker.launch(arrayOf("*/*")) }
}

/** The selected file waiting for the owner's confirmation to copy it. */
@Composable
internal fun PendingImport(graph: AppGraph) {
    val pending by graph.pendingModelImport.collectAsState()
    val targetId by graph.pendingImportTarget.collectAsState()
    val allowed = localAiAllowed(graph)
    if (pending == null) return
    val target = graph.localAi.entry(targetId)
    Column(Modifier.ruledBox(MaterialTheme.colorScheme.outline), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(if (target == null) "File selected. Copy it into Ballistic to replace your imported GGUF. Your original file stays untouched."
            else "File selected for ${target.name}. Ballistic copies it and accepts it only if its size and SHA-256 match the catalogue. Your original file stays untouched.")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            BButton("Copy selected file", { pending?.let { graph.localAi.import(it, targetId) }; graph.pendingModelImport.value = null },
                enabled = allowed.change, emphasis = true)
            BButton("Dismiss", { graph.pendingModelImport.value = null })
        }
    }
}

/**
 * Where a catalogue file comes from and how it was checked: publisher, file, pinned revision,
 * byte size, expected SHA-256 (selectable, to compare with `sha256sum` on a computer), and once
 * installed what this phone verified.
 */
@Composable
internal fun ModelProvenance(entry: DownloadableModel, copy: ManagedModel?) {
    val c = MaterialTheme.colorScheme
    KeyValue("Publisher", "${entry.publisher} on Hugging Face", mono = false)
    KeyValue("Repository", entry.repository)
    KeyValue("File", entry.publisherFileName)
    KeyValue("Revision", entry.revision.take(12))
    KeyValue("Size", String.format(Locale.ROOT, "%,d bytes", entry.bytes))
    KeyValue("Licence", entry.license, mono = false)
    Text("Expected SHA-256", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    SelectionContainer { Text(entry.sha256, style = LocalBallisticExtras.current.monoSmall) }
    if (copy != null && copy.sha256 == entry.sha256 && copy.bytes == entry.bytes) {
        val date = copy.verifiedAt?.let { " " + day(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) } ?: ""
        Text("Verified on this phone$date: size and SHA-256 match.", style = MaterialTheme.typography.bodySmall)
    }
    entry.hashRechecked?.let {
        Text("Also downloaded on a computer on ${day(LocalDate.parse(it))}: size and SHA-256 matched this entry.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/** Flat 6dp bar in text colour; null = indeterminate (a moving block). */
@Composable
internal fun FlatProgress(fraction: Float?) {
    val c = MaterialTheme.colorScheme
    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = c.onSurface, trackColor = c.outlineVariant,
        strokeCap = StrokeCap.Butt, gapSize = 0.dp)
    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = c.onSurface,
        trackColor = c.outlineVariant, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
}

internal fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

/** Plain name of the chip a backend uses. */
internal fun chipWord(backend: ComputeBackend): String = when (backend) {
    ComputeBackend.NPU -> "Tensor chip"
    ComputeBackend.GPU -> "graphics chip"
    ComputeBackend.CPU -> "CPU"
}

internal fun LocalPhase.label() = when (this) {
    LocalPhase.Unloaded -> "Not loaded"
    LocalPhase.Loading -> "Loading"
    LocalPhase.Ready -> "Ready"
    LocalPhase.Generating -> "Busy"
    LocalPhase.Error -> "Didn't load"
}

internal fun decimal(n: Double) = String.format(Locale.ROOT, "%.1f", n)
internal fun duration(ms: Double) = "${decimal(ms / 1000)} s"
internal fun gib(bytes: Long) = "${decimal(bytes / (1024.0 * 1024 * 1024))} GiB"
internal fun transferSize(bytes: Long) = "${decimal(bytes / 1_000_000.0)} MB"
