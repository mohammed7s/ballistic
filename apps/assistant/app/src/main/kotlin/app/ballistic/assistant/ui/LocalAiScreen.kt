package app.ballistic.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
fun LocalAiScreen(graph: AppGraph, onOpenAsk: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val ai = graph.localAi
    val selected by ai.selection.collectAsState()
    val model by ai.model.collectAsState()
    val engine by graph.localModels.state.collectAsState()
    val busy by ai.busy.collectAsState()
    val notice by ai.notice.collectAsState()
    val run by graph.session.state.collectAsState()
    val unlocked by graph.gate.unlocked.collectAsState()
    var details by rememberSaveable { mutableStateOf(false) }
    var memory by remember { mutableStateOf(ai.memory()) }
    LaunchedEffect(Unit) { while(true) { memory = ai.memory(); delay(3000) } }
    val enabled = unlocked && run.canSubmit && run.pendingRequest == null && !busy && !engine.busy
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // The picker may have locked the gate on backgrounding. Keep the URI in the UI until
        // the owner returns and unlocks; importing must not relax the command gate.
        if (uri != null) graph.pendingModelImport.value = uri
    }
    val pending by graph.pendingModelImport.collectAsState()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Your local AI", style = MaterialTheme.typography.headlineMedium)
            Text(ai.phone, style = MaterialTheme.typography.bodyLarge)
            Text(when {
                selected.backend == ModelBackend.Http -> "Ask is using a connected model. Your on-device model is managed here."
                engine.phase == LocalPhase.Loading -> "Preparing your model on this phone."
                engine.phase == LocalPhase.Generating -> "Your phone is generating a response."
                engine.metadata != null -> "Your model is ready on this phone."
                model != null -> "Your model is imported. Load it when you are ready."
                else -> "Import a model to keep AI requests on your phone."
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
                    Text("On device: ${model?.name ?: "No model installed"} · ${engine.phase.label()}")
                    if(selected.backend == ModelBackend.Http) TextButton(onClick = onOpenSettings) { Text("Connection settings") }
                    if(busy || engine.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    engine.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    notice?.let { Text(it) }
                }
            }
        }
        if(!unlocked) item {
            Text("Unlock to set up or change models.")
            OutlinedButton(onClick = onOpenAsk) { Text("Go to Ask to unlock") }
        }
        if(!run.canSubmit || run.pendingRequest != null) item { Text("Finish or stop the Ask run before changing models.") }
        item {
            Text("Model storage", style = MaterialTheme.typography.titleMedium)
            Text(model?.let { "${size(it.bytes)} managed copy" } ?: "Choose a chat model in GGUF format that you have permission to use. No model is bundled or downloaded.")
            Text("${size(ai.availableStorage)} storage available", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled) { Text("Import GGUF") }
                if(model != null) OutlinedButton(onClick = ai::remove, enabled = enabled) { Text("Remove copy") }
            }
            if(pending != null) {
                Text("File selected. Copy it into Ballistic to replace your current model. Your original file stays untouched.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pending?.let(ai::import); graph.pendingModelImport.value = null }, enabled = enabled) { Text("Copy selected file") }
                    TextButton(onClick = { graph.pendingModelImport.value = null }) { Text("Dismiss") }
                }
            }
            Text("Your original file stays untouched. Ballistic never switches to a connected model automatically.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ai::load, enabled = enabled && model != null && engine.metadata == null) { Text("Load model") }
                OutlinedButton(onClick = ai::unload, enabled = enabled && engine.metadata != null) { Text("Unload") }
            }
            if(busy || engine.busy) Button(onClick = { if(run.canStop) graph.stop() else ai.stop() }) { Text("Stop local operation") }
        }
        item {
            Text("How responsive is it?", style = MaterialTheme.typography.titleLarge)
            Text("Measured on this phone, for the local model only.", style = MaterialTheme.typography.bodySmall)
            val m = engine.metrics
            Metric("First token", m?.firstTokenMs?.let(::duration))
            Metric("Generation", m?.generationTokensPerSecond?.let { "${decimal(it)} tokens/s" })
            Text("Speed check: a short counting prompt, up to 32 output tokens. No phone actions or model network request.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = ai::speedCheck, enabled = enabled && engine.metadata != null) { Text("Check local speed") }
        }
        item {
            Text("Suggested for this phone", style = MaterialTheme.typography.titleMedium)
            Text("Conservative starting settings, not measured performance. You choose which model to import.")
            OutlinedButton(onClick = ai::applyProfile, enabled = enabled) { Text("Apply suggested settings") }
            Text("Applying settings unloads the model. It does not choose or download weights.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Memory: ${size(memory.availMem)} available of ${size(memory.totalMem)}")
            Text("System estimate, not Ballistic's memory usage or a promise that weights fit.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { details = !details }) { Text(if(details) "Hide technical details" else "Technical details") }
            if(details) {
                Text("Context capacity", style = MaterialTheme.typography.titleMedium)
                Text("4K is the starting point for app requests; some need 8K. 2K may fit only short prompts. Larger contexts need more memory and are not guaranteed to fit this phone.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048,4096,8192).forEach { tokens ->
                        FilterChip(selected.contextTokens == tokens, { ai.setContext(tokens) }, { Text("${tokens / 1024}K") }, enabled = enabled)
                    }
                }
                Text("Changing context unloads the model. Load it again before asking. Prompts are never silently shortened.", style = MaterialTheme.typography.bodySmall)
                Text(ai.profile.suggestion)
                Text("Suggested: ${ai.profile.contextTokens} context tokens · ${ai.profile.threads} CPU threads. Not benchmarked on this phone.")
                Metric("Model load", engine.loadMs?.let(::duration))
                Metric("Prompt processing", engine.metrics?.promptTokensPerSecond?.let { "${decimal(it)} tokens/s" })
                Metric("Last model call", engine.metrics?.durationMs?.let(::duration))
                Metric("Token counts", engine.metrics?.let { "${it.promptTokens} prompt · ${it.generatedTokens} generated" })
                if(engine.metrics?.capped == true) Text("The generation limit was reached.")
                Text(modelLine(graph), style = MaterialTheme.typography.bodySmall)
                Text("Runtime: embedded llama.cpp CPU · 80d2c0c2c56e\nContext setting: ${selected.contextTokens} · threads: ${selected.threads}\nAll four roles share one loaded model. Generation limit: 1,024 tokens. No prompt truncation or cloud fallback.")
                Metric("GGUF identity", engine.metadata?.description)
                Metric("Quantization metadata", engine.metadata?.quantization)
                Metric("Training context", engine.metadata?.trainingContext?.toString())
                Metric("Allocated context", engine.metadata?.context?.toString())
                Text("Metadata is read on successful load. First-token latency includes framing and prefill. Generation rate excludes the first token; fewer than two output tokens has no measured rate. Metrics reset on unload/process restart.", style = MaterialTheme.typography.bodySmall)
                Text("llama.cpp: MIT license; ggml and vendored component notices are in this app's assets. Model licenses are separate.", style = MaterialTheme.typography.bodySmall)
            }
        }

    }
}
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
