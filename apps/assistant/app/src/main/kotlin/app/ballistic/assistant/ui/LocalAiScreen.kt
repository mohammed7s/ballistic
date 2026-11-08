// Local AI (docs/UX_SYNTHESIS.md §4.4): where AI runs (radio rows, no automatic fallback), the
// model dropdown over the pinned catalogue, what is actually running, measured checks, then
// storage, suggested settings and technical details behind disclosures. Every action is the
// same LocalAiController call as before; only the layout changed. Model numbers live here and
// in task details, nowhere else.

package app.ballistic.assistant.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.models.*
import app.ballistic.assistant.session.LabelStyle
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun LocalAiScreen(graph: AppGraph, activity: Activity, onOpenAsk: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val mono = LocalBallisticExtras.current.mono
    val ai = graph.localAi
    val selected by ai.selection.collectAsState()
    val model by ai.model.collectAsState()
    val installed by ai.installed.collectAsState()
    val engine by graph.localModels.state.collectAsState()
    val busy by ai.busy.collectAsState()
    val notice by ai.notice.collectAsState()
    val download by ai.downloadProgress.collectAsState()
    val downloading by ai.downloading.collectAsState()
    val results by ai.speedResults.collectAsState()
    val backgroundNotice by graph.backgroundNotice.collectAsState()
    var confirmDownload by rememberSaveable { mutableStateOf<String?>(null) }
    var authenticationError by remember { mutableStateOf<String?>(null) }
    val run by graph.session.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    var viewing by rememberSaveable { mutableStateOf(ai.activeId) }
    var menuOpen by remember { mutableStateOf(false) }
    var importTarget by rememberSaveable { mutableStateOf(DownloadableModel.IMPORTED_MODEL_ID) }
    var memory by remember { mutableStateOf(ai.memory()) }
    LaunchedEffect(Unit) { while(true) { memory = ai.memory(); delay(3000) } }
    val enabled = unlocked && run.canSubmit && run.pendingRequest == null && !busy && !engine.busy
    val canStartSetup = run.canSubmit && run.pendingRequest == null && !busy && !engine.busy
    fun requestDownload(id: String) {
        authenticationError = null
        if (graph.gate.canAcceptCommands()) confirmDownload = id
        else graph.gate.authenticate(activity) { error ->
            authenticationError = error
            if (error == null) confirmDownload = id
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // The picker may have locked the gate on backgrounding. Keep the URI in the UI until
        // the owner returns and unlocks; importing must not relax the command gate.
        if (uri != null) graph.pendingModelImport.value = uri
    }
    val pending by graph.pendingModelImport.collectAsState()
    val loadedTarget = engine.target?.takeIf { engine.metadata != null }
    val connectedConfigured = remember(selected) { graph.modelsConfig.load() != null }
    val leaves = remember(selected) { graph.leavesPhone() }

    confirmDownload?.let { id -> ai.entry(id)?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            shape = RectangleShape,
            title = { Text("Download ${entry.name}?", style = MaterialTheme.typography.titleMedium) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${size(entry.bytes)} · ${entry.runtime.label} · ${entry.accelerator.label}", style = mono)
                Text("Downloads ${entry.publisherFileName} from ${publisher(entry)} on Hugging Face using your internet connection. Wi-Fi is recommended. License: ${entry.license}.")
                Text("Ballistic checks the byte count and SHA-256, keeps it beside your other models, selects on-device AI and loads it. ${size(entry.bytes + ManagedModelStore.STORAGE_RESERVE)} free storage is needed; loading also needs available RAM.")
                if (entry.note.isNotEmpty()) Text(entry.note)
                Text("You can leave the app during download; progress stays in a notification. Force-stopping the app interrupts the download, which restarts from the beginning.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { BButton("Download & use on this phone", { confirmDownload = null; ai.download(entry.id) }, enabled = enabled, emphasis = true) },
            dismissButton = { BButton("Cancel", { confirmDownload = null }) },
        )
    } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        item {
            SectionHeading("Where AI runs")
            Column(verticalArrangement = Arrangement.spacedBy((-1).dp)) {
                RadioRow(selected.backend == ModelBackend.Local, "On this phone", "Prompts and screen information stay on this phone.", enabled) {
                    ai.choose(ModelBackend.Local)
                }
                RadioRow(selected.backend == ModelBackend.Http, "Connected server",
                    if (connectedConfigured) connectedModelSummary(graph).removePrefix("Connected · ") else "Set up in Settings · prompts leave the phone",
                    enabled) { ai.choose(ModelBackend.Http) }
            }
            if (selected.backend == ModelBackend.Http) Column(Modifier.padding(top = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                if (leaves) WarnBlock("Leaves phone", "Prompts and screen information are sent to ${graph.routeLabel().removePrefix("left the phone: ")}. Apps set to \"this phone only\" are left out.")
                else if (!connectedConfigured) WarnBlock("Not set up", "Add a server in Settings first.")
                BButton("Connection settings", onOpenSettings)
            }
            Text("Ballistic never switches to a connected server by itself, and never to another model or backend after a failure.",
                Modifier.padding(top = Space.s2), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        if (!unlocked) item {
            Row(Modifier.fillMaxWidth().ruledBox(c.outlineVariant), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                StatusLabel("Locked", LabelStyle.Inverted)
                Text("Enable requests to change models.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                BButton("Enable", { graph.gate.authenticate(activity) { e -> authenticationError = e; if (e == null) graph.session.onUnlocked() else graph.session.onUnlockCancelled() } })
            }
        }
        backgroundNotice?.let { message -> item { WarnBlock("Interrupted", message) } }
        if(!run.canSubmit || run.pendingRequest != null) item { Text("Finish or stop the task before changing models.", style = MaterialTheme.typography.bodySmall) }

        item {
            SectionHeading("On-phone model")
            Box {
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceContainerHigh).border(Space.hairline, c.outline)
                        .clickable(role = Role.DropdownList, onClickLabel = "Choose a model") { menuOpen = true }
                        .heightIn(min = Space.touch).padding(horizontal = Space.s3),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    Text(displayName(ai, viewing, installed[viewing]), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ai.entry(viewing)?.let { Text("${it.runtime.label} · ${it.accelerator.label}", Modifier.weight(1f), style = LocalBallisticExtras.current.monoSmall,
                        color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } ?: Box(Modifier.weight(1f))
                    Text(if (menuOpen) "▴" else "▾", color = c.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = RectangleShape,
                    containerColor = c.surfaceContainerHigh) {
                    ai.catalogue.forEach { entry ->
                        DropdownMenuItem(
                            text = { CatalogueRow(entry.name, "${entry.runtime.label} · ${entry.accelerator.label} · ${size(entry.bytes)}",
                                status(entry, installed, ai.activeId), current = entry.id == viewing) },
                            onClick = { viewing = entry.id; menuOpen = false },
                        )
                    }
                    installed[DownloadableModel.IMPORTED_MODEL_ID]?.let { own ->
                        DropdownMenuItem(
                            text = { CatalogueRow("Your imported GGUF", "llama.cpp · CPU · ${size(own.bytes)} · ${own.name}",
                                if (ai.activeId == DownloadableModel.IMPORTED_MODEL_ID) "In use" else "Downloaded", current = viewing == DownloadableModel.IMPORTED_MODEL_ID) },
                            onClick = { viewing = DownloadableModel.IMPORTED_MODEL_ID; menuOpen = false },
                        )
                    }
                }
            }
        }
        item {
            val entry = ai.entry(viewing)
            val copy = installed[viewing]
            val isActive = viewing == ai.activeId
            val isLoaded = loadedTarget?.modelId == viewing
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                if (entry != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        StatusLabel(when {
                            isLoaded -> "Loaded"
                            copy != null && isActive -> "In use"
                            copy != null -> "Downloaded"
                            entry.gated -> "Import only"
                            else -> "Not downloaded"
                        })
                        Text("${size(entry.bytes)} · built for ${entry.accelerator.label}", style = mono, color = c.onSurfaceVariant)
                    }
                    Text("${entry.license} · ${publisher(entry)} on Hugging Face${if (entry.gated) " · gated by the publisher" else ""}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    if (entry.note.isNotEmpty()) Text(entry.note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    if (entry.backends.size > 1) {
                        Text("Backend (your choice; no automatic fallback)", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            entry.backends.forEach { b ->
                                val on = entry.backendFor(selected) == b
                                BButton((if (on) "(•) " else "( ) ") + b.name, { ai.chooseComputeBackend(b) }, enabled = enabled, emphasis = on)
                            }
                        }
                    } else Text("Backend: ${entry.backends.single().name} only", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                } else copy?.let { Text("${size(it.bytes)} · llama.cpp · CPU · ${it.name}. Your own file; no publisher digest check.", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    if (copy != null && !isLoaded) BButton("Use", { ai.use(viewing) }, enabled = enabled, emphasis = true)
                    if (copy == null && entry != null && !entry.gated) BButton(if (unlocked) "Download" else "Unlock & download", { requestDownload(entry.id) }, enabled = canStartSetup, emphasis = true)
                    if (entry != null && copy == null) BButton("Import verified file", { importTarget = entry.id; picker.launch(arrayOf("*/*")) }, enabled = enabled)
                    if (copy != null) BButton("Delete", { ai.delete(viewing) }, enabled = enabled)
                }
                if (entry?.gated == true && copy == null) Text("This publisher requires a Hugging Face account that accepted its terms. Ballistic never asks for or stores a Hugging Face token: download ${entry.publisherFileName} on a computer, copy it to the phone, then import it here. Ballistic accepts it only if size and SHA-256 match.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                authenticationError?.let { WarnBlock("Not unlocked", it) }
                if (download != null) notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                download?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        StatusLabel("Downloading")
                        Text(ai.entry(downloading)?.name ?: "model", style = MaterialTheme.typography.bodyMedium)
                    }
                    FlatProgress(p.fraction)
                    Text("${transferSize(p.received)} / ${transferSize(p.total)} · ${(p.fraction * 100).toInt()} %${if (p.received == p.total) " · download received" else ""}", style = mono)
                    Text("You can leave the app.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
        item {
            Hairline()
            KeyValue("Active", "${displayName(ai, ai.activeId, model)} · ${engine.phase.label()}", mono = false)
            KeyValue("Running on", loadedTarget?.let { "${it.runtime.label} · ${it.backend.name}" } ?: "nothing loaded")
            engine.metadata?.backendReport?.let { Text(it, style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant) }
            val m = engine.metrics
            KeyValue("First token", m?.firstTokenMs?.let(::duration))
            KeyValue("Prefill", m?.promptTokensPerSecond?.let { "${decimal(it)} tok/s" })
            KeyValue("Decode", m?.generationTokensPerSecond?.let { "${decimal(it)} tok/s" })
            KeyValue("Prompt tokens", m?.promptTokens?.takeIf { it > 0 }?.toString())
            if (busy || engine.busy) FlatProgress(null)
            engine.error?.let { WarnBlock("Error", it) }
            engine.errorDetail?.let { Text("Details (runtime log): $it", style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant) }
            if (download == null) notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2), modifier = Modifier.padding(top = Space.s2)) {
                if (engine.metadata == null) BButton("Load", ai::load, enabled = enabled && model != null, emphasis = true)
                else BButton("Unload", ai::unload, enabled = enabled)
                BButton("Short check", { ai.speedCheck(SpeedCheck.Short) }, enabled = enabled && engine.metadata != null)
                BButton("~2K check", { ai.speedCheck(SpeedCheck.LongPrefill) }, enabled = enabled && engine.metadata != null)
                if (busy || engine.busy) BButton("Stop", { if (run.canStop) graph.stop() else ai.stop() })
                if (busy) BButton("Continue in background", { activity.moveTaskToBack(true) })
            }
            Text("Short: a counting prompt, up to 32 output tokens. ~2K: a synthetic list of screen elements (about two thousand prompt tokens; the runtime reports the exact count), up to 16 output tokens. Measured on this phone; no phone actions or model network request.",
                Modifier.padding(top = Space.s1), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        if (results.isNotEmpty()) item {
            Disclosure("Results this session · ${results.size}", key = "speed-results") {
                results.forEach { r ->
                    Text("${r.modelName} · ${r.runtime.label} ${r.backend.name} · ${r.check.label}", style = MaterialTheme.typography.bodyMedium)
                    Text("${r.metrics.promptTokens.takeIf { it > 0 }?.toString() ?: "unmeasured"} prompt tokens · first token ${r.metrics.firstTokenMs?.let(::duration) ?: "not measured"} · prefill ${r.metrics.promptTokensPerSecond?.let { decimal(it) } ?: "-"} tok/s · decode ${r.metrics.generationTokensPerSecond?.let { decimal(it) } ?: "-"} tok/s", style = LocalBallisticExtras.current.monoSmall)
                }
                Text("Kept in memory until the app process ends. LiteRT-LM rates are its own benchmark numbers; llama.cpp rates are its timing counters.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        item {
            Hairline()
            Disclosure("Storage · import a file", key = "storage") {
                KeyValue("Used", "${size(ai.usedStorage)} by Ballistic models")
                KeyValue("Free", size(ai.availableStorage))
                Text("Import your own compatible chat model in GGUF format. No model weights are bundled in the APK.", style = MaterialTheme.typography.bodySmall)
                BButton("Import GGUF", { importTarget = DownloadableModel.IMPORTED_MODEL_ID; picker.launch(arrayOf("*/*")) }, enabled = enabled)
            }
            if(pending != null) Column(Modifier.ruledBox(c.outline), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                val target = ai.entry(importTarget)
                Text(if (target == null) "File selected. Copy it into Ballistic to replace your imported GGUF. Your original file stays untouched."
                    else "File selected for ${target.name}. Ballistic copies it and accepts it only if its size and SHA-256 match the catalogue. Your original file stays untouched.")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    BButton("Copy selected file", { pending?.let { ai.import(it, importTarget) }; graph.pendingModelImport.value = null }, enabled = enabled, emphasis = true)
                    BButton("Dismiss", { graph.pendingModelImport.value = null })
                }
            }
            Hairline()
            Disclosure("Suggested settings for this phone", key = "suggested") {
                Text("Conservative starting settings, not measured performance. They do not choose or download a model. Applying them unloads the model.", style = MaterialTheme.typography.bodySmall)
                Text(ai.profile.suggestion, style = MaterialTheme.typography.bodySmall)
                KeyValue("Context", "${ai.profile.contextTokens} tokens")
                KeyValue("CPU threads", ai.profile.threads.toString())
                BButton("Apply suggested settings", ai::applyProfile, enabled = enabled)
            }
            Hairline()
            Disclosure("Technical details · context, memory, licences", key = "technical") {
                KeyValue("Phone", ai.phone, mono = false)
                KeyValue("Memory", "${size(memory.availMem)} free of ${size(memory.totalMem)}")
                Text("System estimate, not Ballistic's memory usage or a promise that weights fit.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Text("Context capacity", style = MaterialTheme.typography.labelLarge)
                Text("4K is the starting point for app requests; some need 8K. 2K may fit only short prompts. Larger contexts need more memory. A Tensor NPU bundle has a context fixed when it was compiled; this setting does not change it.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    listOf(2048,4096,8192).forEach { tokens ->
                        val on = selected.contextTokens == tokens
                        BButton((if (on) "(•) " else "( ) ") + "${tokens / 1024}K", { ai.setContext(tokens) }, enabled = enabled, emphasis = on)
                    }
                }
                Text("Changing context unloads the model. Load it again before asking. Prompts are never silently shortened.", style = MaterialTheme.typography.bodySmall)
                KeyValue("Model load", engine.loadMs?.let(::duration))
                KeyValue("Last model call", engine.metrics?.durationMs?.let(::duration))
                KeyValue("Token counts", engine.metrics?.let { "${it.promptTokens} prompt · ${it.generatedTokens} generated" })
                if(engine.metrics?.capped == true) Text("The generation limit was reached.", style = MaterialTheme.typography.bodySmall)
                Text(modelLine(graph), style = MaterialTheme.typography.bodySmall)
                Text("Runtimes: embedded llama.cpp CPU · 80d2c0c2c56e, and LiteRT-LM ${app.ballistic.assistant.BuildConfig.LITERTLM_VERSION} (CPU/GPU/NPU) with the Google Tensor NPU runtime ${app.ballistic.assistant.BuildConfig.LITERT_NPU_GOOGLE_TENSOR_VERSION}.\nContext setting: ${selected.contextTokens} · CPU threads: ${selected.threads}\nAll roles share one loaded model, with a fresh context per call. Generation limit: 1,024 tokens. No prompt truncation or cloud fallback.", style = LocalBallisticExtras.current.monoSmall)
                Text("Tensor NPU: ${ai.tensorInfo}", style = MaterialTheme.typography.bodySmall)
                KeyValue("Model identity", engine.metadata?.description)
                KeyValue("Quantization", engine.metadata?.quantization)
                KeyValue("Training context", engine.metadata?.trainingContext?.takeIf { it > 0 }?.toString())
                KeyValue("Allocated context", engine.metadata?.context?.takeIf { it > 0 }?.toString())
                Text("Metadata is read on successful load. First-token latency includes framing and prefill. llama.cpp's generation rate excludes the first token. Metrics reset on unload/process restart.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Text("llama.cpp: MIT license; ggml and vendored component notices are in this app's assets. LiteRT-LM and the LiteRT NPU runtime: Apache-2.0. Model licenses are separate.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.height(Space.s4))
        }
    }
}

/** One catalogue row: name, runtime · backend · size in mono, status label; current row ruled. */
@Composable
private fun CatalogueRow(name: String, meta: String, status: String, current: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(Space.warnRule).fillMaxHeight().background(if (current) c.onSurface else c.surfaceContainerHigh))
        Column(Modifier.weight(1f).padding(start = Space.s2, top = 4.dp, bottom = 4.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            Text(meta, style = LocalBallisticExtras.current.monoSmall, color = c.onSurfaceVariant)
        }
        Text(status.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
    }
}

/** Flat 6dp bar in text colour; null = indeterminate (a moving block). */
@Composable
internal fun FlatProgress(fraction: Float?) {
    val c = MaterialTheme.colorScheme
    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = c.onSurface, trackColor = c.outlineVariant,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt, gapSize = 0.dp)
    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = c.onSurface,
        trackColor = c.outlineVariant, strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
}

private fun publisher(entry: DownloadableModel) = java.net.URI(entry.url).path.split('/').getOrNull(1) ?: "the publisher"
private fun status(entry: DownloadableModel, installed: Map<String, ManagedModel>, activeId: String) = when {
    installed.containsKey(entry.id) && entry.id == activeId -> "In use"
    installed.containsKey(entry.id) -> "Downloaded"
    entry.gated -> "Import only"
    else -> "Download"
}
private fun displayName(ai: LocalAiController, id: String, copy: ManagedModel?) =
    ai.entry(id)?.name ?: if (id == DownloadableModel.IMPORTED_MODEL_ID) (copy?.name?.let { "Imported: $it" } ?: "No model installed") else "No model installed"
private fun LocalPhase.label() = when(this) {
    LocalPhase.Unloaded -> "Unloaded"
    LocalPhase.Loading -> "Loading"
    LocalPhase.Ready -> "Ready"
    LocalPhase.Generating -> "Generating"
    LocalPhase.Error -> "Error"
}
private fun decimal(n: Double) = String.format(Locale.ROOT,"%.1f",n)
private fun duration(ms: Double) = "${decimal(ms / 1000)} s"
private fun size(bytes: Long) = "${decimal(bytes / (1024.0*1024*1024))} GiB"
private fun transferSize(bytes: Long) = "${decimal(bytes / 1_000_000.0)} MB"
