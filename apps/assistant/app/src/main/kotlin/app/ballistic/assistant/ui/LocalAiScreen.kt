package app.ballistic.assistant.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.models.*
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun LocalAiScreen(graph: AppGraph, activity: Activity, onOpenAsk: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
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
    var details by rememberSaveable { mutableStateOf(false) }
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

    confirmDownload?.let { id -> ai.entry(id)?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Download ${entry.name}?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${size(entry.bytes)} download · ${entry.runtime.label} · ${entry.accelerator.label}")
                Text("Downloads ${entry.publisherFileName} from ${publisher(entry)} on Hugging Face using your internet connection. Wi-Fi is recommended. License: ${entry.license}.")
                Text("Ballistic checks the byte count and SHA-256, keeps it beside your other models, selects on-device AI and loads it. ${size(entry.bytes + ManagedModelStore.STORAGE_RESERVE)} free storage is needed; loading also needs available RAM.")
                if (entry.note.isNotEmpty()) Text(entry.note)
                Text("You can leave the app during download; progress stays in a notification. Force-stopping the app interrupts the download, which restarts from the beginning.")
            } },
            confirmButton = { TextButton(onClick = { confirmDownload = null; ai.download(entry.id) }, enabled = enabled) { Text("Download & use on device") } },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("Cancel") } },
        )
    } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Your local AI", style = MaterialTheme.typography.headlineMedium)
            Text(ai.phone, style = MaterialTheme.typography.bodyLarge)
            Text(when {
                selected.backend == ModelBackend.Http -> "Ask is using a connected model. Your on-device models are managed here."
                engine.phase == LocalPhase.Loading -> "Preparing ${engine.target?.modelName ?: "your model"} on this phone."
                engine.phase == LocalPhase.Generating -> "Your phone is generating a response."
                engine.metadata != null -> "Your model is ready on this phone."
                model != null -> "Your model is installed. Load it when you are ready."
                else -> "Choose a model below to keep AI requests on your phone."
            }, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if(selected.backend == ModelBackend.Local) "On-device model" else "Connected model", style = MaterialTheme.typography.titleLarge)
                    Text(if(selected.backend == ModelBackend.Local) "Model requests stay on this device. Other app networking is unchanged." else connectedModelSummary(graph))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected.backend == ModelBackend.Local, { ai.choose(ModelBackend.Local) }, { Text("On device") }, enabled = enabled)
                        FilterChip(selected.backend == ModelBackend.Http, { ai.choose(ModelBackend.Http) }, { Text("Connected") }, enabled = enabled)
                    }
                    Text("Active: ${displayName(ai, ai.activeId, model)} · ${engine.phase.label()}")
                    loadedTarget?.let { Text("Running on: ${it.runtime.label} · ${it.backend.name}", style = MaterialTheme.typography.titleSmall) }
                    engine.metadata?.backendReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if(selected.backend == ModelBackend.Http) TextButton(onClick = onOpenSettings) { Text("Connection settings") }
                    if(busy || engine.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    engine.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    engine.errorDetail?.let { Text("Details (runtime log): $it", style = MaterialTheme.typography.bodySmall) }
                    if (download == null) notice?.let { Text(it) }
                    if(busy || engine.busy) Button(onClick = { if(run.canStop) graph.stop() else ai.stop() }) { Text("Stop local operation") }
                }
            }
        }
        item { SessionAccess(graph, activity) }
        backgroundNotice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (busy) item {
            OutlinedButton(onClick = { activity.moveTaskToBack(true) }) { Text("Continue in background") }
        }
        if(!run.canSubmit || run.pendingRequest != null) item { Text("Finish or stop the Ask run before changing models.") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Models", style = MaterialTheme.typography.titleLarge)
                    Text("Pinned downloads checked by size and SHA-256. Choose one to download, use or delete. Previews: phone-task quality is not validated.", style = MaterialTheme.typography.bodySmall)
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("${displayName(ai, viewing, installed[viewing])} ▾", Modifier.weight(1f))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            ai.catalogue.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Column {
                                        Text(entry.name + if (entry.id == ai.activeId) " · active" else "")
                                        Text("${size(entry.bytes)} · ${entry.runtime.label} · ${entry.accelerator.label} · ${status(entry, installed)}", style = MaterialTheme.typography.bodySmall)
                                    } },
                                    onClick = { viewing = entry.id; menuOpen = false },
                                )
                            }
                            installed[DownloadableModel.IMPORTED_MODEL_ID]?.let { own ->
                                DropdownMenuItem(
                                    text = { Column {
                                        Text("Your imported GGUF" + if (ai.activeId == DownloadableModel.IMPORTED_MODEL_ID) " · active" else "")
                                        Text("${size(own.bytes)} · llama.cpp · CPU · ${own.name}", style = MaterialTheme.typography.bodySmall)
                                    } },
                                    onClick = { viewing = DownloadableModel.IMPORTED_MODEL_ID; menuOpen = false },
                                )
                            }
                        }
                    }
                    val entry = ai.entry(viewing)
                    val copy = installed[viewing]
                    val isActive = viewing == ai.activeId
                    val isLoaded = loadedTarget?.modelId == viewing
                    if (entry != null) {
                        Text("${size(entry.bytes)} · ${entry.runtime.label} · built for ${entry.accelerator.label}")
                        Text("${entry.license} · ${publisher(entry)} on Hugging Face${if (entry.gated) " · gated by the publisher" else ""}", style = MaterialTheme.typography.bodySmall)
                        if (entry.note.isNotEmpty()) Text(entry.note, style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            isLoaded -> "Loaded on ${loadedTarget?.backend?.name}"
                            copy != null -> "Downloaded and verified" + if (isActive) " · active" else ""
                            else -> "Not downloaded"
                        })
                        if (entry.backends.size > 1) {
                            Text("Backend (your choice; no automatic fallback)", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                entry.backends.forEach { b ->
                                    FilterChip(entry.backendFor(selected) == b, { ai.chooseComputeBackend(b) }, { Text(b.name) }, enabled = enabled)
                                }
                            }
                        } else Text("Backend: ${entry.backends.single().name} only", style = MaterialTheme.typography.bodySmall)
                    } else copy?.let { Text("${size(it.bytes)} · llama.cpp · CPU · ${it.name}. Your own file; no publisher digest check.") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (copy != null && !isLoaded) Button(onClick = { ai.use(viewing) }, enabled = enabled) { Text("Use") }
                        if (copy == null && entry != null && !entry.gated) Button(onClick = { requestDownload(entry.id) }, enabled = canStartSetup) {
                            Text(if (unlocked) "Download" else "Unlock & download")
                        }
                        if (copy != null) OutlinedButton(onClick = { ai.delete(viewing) }, enabled = enabled) { Text("Delete") }
                    }
                    if (entry != null && copy == null) OutlinedButton(onClick = { importTarget = entry.id; picker.launch(arrayOf("*/*")) }, enabled = enabled) {
                        Text("Import verified file")
                    }
                    if (entry?.gated == true && copy == null) Text("This publisher requires a Hugging Face account that accepted its terms. Ballistic never asks for or stores a Hugging Face token: download ${entry.publisherFileName} on a computer, copy it to the phone, then import it here. Ballistic accepts it only if size and SHA-256 match.", style = MaterialTheme.typography.bodySmall)
                    authenticationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!canStartSetup) Text("Finish or stop the current operation before changing models.")
                    if (download != null) notice?.let { Text(it) }
                    download?.let { p ->
                        Text("Downloading ${ai.entry(downloading)?.name ?: "model"}")
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${transferSize(p.received)} / ${transferSize(p.total)} · ${(p.fraction * 100).toInt()}%${if (p.received == p.total) " · download received" else ""}")
                    }
                }
            }
        }
        item {
            Text("Model storage", style = MaterialTheme.typography.titleMedium)
            Text("${size(ai.usedStorage)} used by Ballistic models · ${size(ai.availableStorage)} available", style = MaterialTheme.typography.bodySmall)
            Text("Import your own compatible chat model in GGUF format. No model weights are bundled in the APK.")
            OutlinedButton(onClick = { importTarget = DownloadableModel.IMPORTED_MODEL_ID; picker.launch(arrayOf("*/*")) }, enabled = enabled) { Text("Import GGUF") }
            if(pending != null) {
                val target = ai.entry(importTarget)
                Text(if (target == null) "File selected. Copy it into Ballistic to replace your imported GGUF. Your original file stays untouched."
                    else "File selected for ${target.name}. Ballistic copies it and accepts it only if its size and SHA-256 match the catalogue. Your original file stays untouched.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pending?.let { ai.import(it, importTarget) }; graph.pendingModelImport.value = null }, enabled = enabled) { Text("Copy selected file") }
                    TextButton(onClick = { graph.pendingModelImport.value = null }) { Text("Dismiss") }
                }
            }
            Text("Ballistic never switches to a connected model automatically, and never to another model or backend after a failure.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ai::load, enabled = enabled && model != null && engine.metadata == null) { Text("Load active model") }
                OutlinedButton(onClick = ai::unload, enabled = enabled && engine.metadata != null) { Text("Unload") }
            }
        }
        item {
            Text("How responsive is it?", style = MaterialTheme.typography.titleLarge)
            Text("Measured on this phone, for the loaded local model only.", style = MaterialTheme.typography.bodySmall)
            val m = engine.metrics
            Metric("First token", m?.firstTokenMs?.let(::duration))
            Metric("Prompt processing", m?.promptTokensPerSecond?.let { "${decimal(it)} tokens/s" })
            Metric("Generation", m?.generationTokensPerSecond?.let { "${decimal(it)} tokens/s" })
            Metric("Prompt tokens", m?.promptTokens?.takeIf { it > 0 }?.toString())
            Text("Short: a counting prompt, up to 32 output tokens. ~2K prefill: a synthetic list of screen elements (about two thousand prompt tokens; the runtime reports the exact count), up to 16 output tokens. No phone actions or model network request.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ai.speedCheck(SpeedCheck.Short) }, enabled = enabled && engine.metadata != null) { Text("Short check") }
                OutlinedButton(onClick = { ai.speedCheck(SpeedCheck.LongPrefill) }, enabled = enabled && engine.metadata != null) { Text("~2K prefill check") }
            }
            if (results.isNotEmpty()) {
                Text("Results this session", style = MaterialTheme.typography.titleMedium)
                results.forEach { r ->
                    Text("${r.modelName} · ${r.runtime.label} ${r.backend.name} · ${r.check.label}", style = MaterialTheme.typography.bodyMedium)
                    Text("${r.metrics.promptTokens.takeIf { it > 0 }?.toString() ?: "unmeasured"} prompt tokens · first token ${r.metrics.firstTokenMs?.let(::duration) ?: "not measured"} · prefill ${r.metrics.promptTokensPerSecond?.let { decimal(it) } ?: "-"} tok/s · decode ${r.metrics.generationTokensPerSecond?.let { decimal(it) } ?: "-"} tok/s", style = MaterialTheme.typography.bodySmall)
                }
                Text("Kept in memory until the app process ends. LiteRT-LM rates are its own benchmark numbers; llama.cpp rates are its timing counters.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text("Suggested for this phone", style = MaterialTheme.typography.titleMedium)
            Text("Conservative starting settings, not measured performance. They do not choose or download a model.")
            OutlinedButton(onClick = ai::applyProfile, enabled = enabled) { Text("Apply suggested settings") }
            Text("Applying settings unloads the model.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Memory: ${size(memory.availMem)} available of ${size(memory.totalMem)}")
            Text("System estimate, not Ballistic's memory usage or a promise that weights fit.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { details = !details }) { Text(if(details) "Hide technical details" else "Technical details") }
            if(details) {
                Text("Context capacity", style = MaterialTheme.typography.titleMedium)
                Text("4K is the starting point for app requests; some need 8K. 2K may fit only short prompts. Larger contexts need more memory. A Tensor NPU bundle has a context fixed when it was compiled; this setting does not change it.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048,4096,8192).forEach { tokens ->
                        FilterChip(selected.contextTokens == tokens, { ai.setContext(tokens) }, { Text("${tokens / 1024}K") }, enabled = enabled)
                    }
                }
                Text("Changing context unloads the model. Load it again before asking. Prompts are never silently shortened.", style = MaterialTheme.typography.bodySmall)
                Text(ai.profile.suggestion)
                Text("Suggested: ${ai.profile.contextTokens} context tokens · ${ai.profile.threads} CPU threads. Not benchmarked on this phone.")
                Metric("Model load", engine.loadMs?.let(::duration))
                Metric("Last model call", engine.metrics?.durationMs?.let(::duration))
                Metric("Token counts", engine.metrics?.let { "${it.promptTokens} prompt · ${it.generatedTokens} generated" })
                if(engine.metrics?.capped == true) Text("The generation limit was reached.")
                Text(modelLine(graph), style = MaterialTheme.typography.bodySmall)
                Text("Runtimes: embedded llama.cpp CPU · 80d2c0c2c56e, and LiteRT-LM ${app.ballistic.assistant.BuildConfig.LITERTLM_VERSION} (CPU/GPU/NPU) with the Google Tensor NPU runtime ${app.ballistic.assistant.BuildConfig.LITERT_NPU_GOOGLE_TENSOR_VERSION}.\nContext setting: ${selected.contextTokens} · CPU threads: ${selected.threads}\nAll four roles share one loaded model, with a fresh context per call. Generation limit: 1,024 tokens. No prompt truncation or cloud fallback.")
                Text("Tensor NPU: ${ai.tensorInfo}", style = MaterialTheme.typography.bodySmall)
                Metric("Model identity", engine.metadata?.description)
                Metric("Quantization metadata", engine.metadata?.quantization)
                Metric("Training context", engine.metadata?.trainingContext?.takeIf { it > 0 }?.toString())
                Metric("Allocated context", engine.metadata?.context?.takeIf { it > 0 }?.toString())
                Text("Metadata is read on successful load. First-token latency includes framing and prefill. llama.cpp's generation rate excludes the first token. Metrics reset on unload/process restart.", style = MaterialTheme.typography.bodySmall)
                Text("llama.cpp: MIT license; ggml and vendored component notices are in this app's assets. LiteRT-LM and the LiteRT NPU runtime: Apache-2.0. Model licenses are separate.", style = MaterialTheme.typography.bodySmall)
            }
        }

    }
}
private fun publisher(entry: DownloadableModel) = java.net.URI(entry.url).path.split('/').getOrNull(1) ?: "the publisher"
private fun status(entry: DownloadableModel, installed: Map<String, ManagedModel>) = when {
    installed.containsKey(entry.id) -> "downloaded"
    entry.gated -> "gated, import only"
    else -> "not downloaded"
}
private fun displayName(ai: LocalAiController, id: String, copy: ManagedModel?) =
    ai.entry(id)?.name ?: if (id == DownloadableModel.IMPORTED_MODEL_ID) (copy?.name?.let { "Imported: $it" } ?: "No model installed") else "No model installed"
@Composable private fun Metric(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Text(value ?: "Not measured", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
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
