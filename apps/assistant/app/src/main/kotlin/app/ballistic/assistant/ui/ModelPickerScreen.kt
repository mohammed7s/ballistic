// Change model: two recommended models for this phone (best and small) in plain words with their
// size, then More models. Each row has one action (Use, Download or Import) and Details with the
// file's provenance. Rules for which models show where are in core-api ModelPicker.

package app.ballistic.assistant.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.models.*

@Composable
fun ModelPickerScreen(graph: AppGraph, activity: Activity) {
    val c = MaterialTheme.colorScheme
    val ai = graph.localAi
    val installed by ai.installed.collectAsState()
    val selected by ai.selection.collectAsState()
    val engine by graph.localModels.state.collectAsState()
    val busy by ai.busy.collectAsState()
    val notice by ai.notice.collectAsState()
    val download by ai.downloadProgress.collectAsState()
    val downloading by ai.downloading.collectAsState()
    val allowed = localAiAllowed(graph)
    var error by remember { mutableStateOf<String?>(null) }
    val import = rememberImport(graph)
    val lists = remember { ai.picker }

    DownloadConfirm(graph)

    @Composable
    fun ModelRow(entry: DownloadableModel, why: String?) {
        val copy = installed[entry.id]
        val active = entry.id == ai.activeId && selected.backend == ModelBackend.Local
        val status = when {
            active && engine.metadata != null && engine.target?.modelId == entry.id -> "In use"
            active && copy != null -> "Chosen"
            copy != null -> "Downloaded"
            downloading == entry.id -> "Downloading"
            entry.gated -> "Import only"
            else -> null
        }
        Column(Modifier.fillMaxWidth().ruledBox(if (active) c.onSurface else c.outlineVariant), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                status?.let { StatusLabel(it) }
            }
            Text(listOfNotNull(why, ModelPicker.whereItRuns(entry), ModelPicker.size(entry.bytes)).joinToString(". ") + ".",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                when {
                    copy != null && !(active && engine.metadata != null && engine.target?.modelId == entry.id) ->
                        BButton("Use", { ai.use(entry.id) }, enabled = allowed.change, emphasis = true)
                    copy == null && !entry.gated ->
                        BButton("Download", { requestDownload(graph, activity, entry.id) { error = it } }, enabled = allowed.startSetup, emphasis = why != null)
                    copy == null && entry.gated ->
                        BButton("Import verified file", { import(entry.id) }, enabled = allowed.change)
                }
            }
            Disclosure("Details", key = "model-details-${entry.id}") {
                ModelProvenance(entry, copy)
                Text("${entry.runtime.label} · ${entry.accelerator.label}", style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
                if (entry.note.isNotEmpty()) Text(entry.note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                if (entry.gated && copy == null) Text("This publisher requires a Hugging Face account that accepted its terms. Ballistic never asks for or stores a Hugging Face token: download ${entry.publisherFileName} on a computer, copy it to the phone, then use Import verified file. Ballistic accepts it only if size and SHA-256 match.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    if (copy == null && !entry.gated) BButton("Import verified file", { import(entry.id) }, enabled = allowed.change)
                    if (copy != null) BButton("Delete", { ai.delete(entry.id) }, enabled = allowed.change)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        error?.let { WarnBlock("Not unlocked", it) }
        if (busy || engine.busy) {
            FlatProgress(download?.fraction)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(download?.let { "Downloading ${ai.entry(downloading)?.name ?: "model"} · ${transferSize(it.received)} / ${transferSize(it.total)} · ${(it.fraction * 100).toInt()} %" }
                    ?: if (engine.phase == LocalPhase.Loading) "Loading…" else "Working…",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                BButton("Stop", { ai.stop() })
            }
            if (download != null) Text("You can leave the app.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        engine.error?.let { WarnBlock("Didn't load", it) }
        PendingImport(graph)

        SectionHeading("Recommended for this phone")
        lists.recommended.forEach { ModelRow(it.entry, it.why) }

        Hairline(Modifier.padding(top = Space.s2))
        Disclosure("More models · ${lists.more.size + if (installed.containsKey(DownloadableModel.IMPORTED_MODEL_ID)) 1 else 0}", key = "more-models") {
            lists.more.forEach { ModelRow(it, null) }
            installed[DownloadableModel.IMPORTED_MODEL_ID]?.let { own ->
                val active = ai.activeId == DownloadableModel.IMPORTED_MODEL_ID
                Column(Modifier.fillMaxWidth().ruledBox(if (active) c.onSurface else c.outlineVariant), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Text("Your imported GGUF", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("${own.name}. Runs on the CPU. ${ModelPicker.size(own.bytes)}. Your own file; no publisher digest check.",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        if (!(active && engine.metadata != null)) BButton("Use", { ai.use(DownloadableModel.IMPORTED_MODEL_ID) }, enabled = allowed.change, emphasis = true)
                        BButton("Delete", { ai.delete(DownloadableModel.IMPORTED_MODEL_ID) }, enabled = allowed.change)
                    }
                }
            }
            if (!ai.chip.googleTensor) Text("Models built for the Google Tensor chip are hidden: this phone doesn't report one.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Text("To use your own GGUF file, see Advanced › Storage.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Text("Ballistic never switches model or backend by itself, and never to a connected server.",
            Modifier.padding(top = Space.s2), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Spacer(Modifier.height(Space.s4))
    }
}
