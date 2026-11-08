// Local AI › Advanced: everything the main page leaves out. Both speed checks with their token
// numbers, the engine (backend) for the current model, optional constrained planner output,
// context, suggested settings, memory, storage and GGUF import, runtime versions, Tensor
// information, model metadata, the runtime log line, licences and the no-fallback statement.

package app.ballistic.assistant.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.BuildConfig
import app.ballistic.assistant.models.*
import kotlinx.coroutines.delay

@Composable
fun LocalAiAdvancedScreen(graph: AppGraph, activity: Activity) {
    val c = MaterialTheme.colorScheme
    val mono = LocalBallisticExtras.current.monoSmall
    val ai = graph.localAi
    val selected by ai.selection.collectAsState()
    val model by ai.model.collectAsState()
    val engine by graph.localModels.state.collectAsState()
    val busy by ai.busy.collectAsState()
    val notice by ai.notice.collectAsState()
    val results by ai.speedResults.collectAsState()
    val run by graph.session.state.collectAsState()
    val allowed = localAiAllowed(graph)
    val import = rememberImport(graph)
    var memory by remember { mutableStateOf(ai.memory()) }
    LaunchedEffect(Unit) { while (true) { memory = ai.memory(); delay(3000) } }
    val entry = ai.entry(ai.activeId)
    val loaded = engine.metadata != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        notice?.let { Text(it, Modifier.padding(top = Space.s2), style = MaterialTheme.typography.bodySmall) }

        SectionHeading("Speed checks")
        KeyValue("Running on", engine.target?.takeIf { loaded }?.let { "${it.runtime.label} · ${it.backend.name}" } ?: "nothing loaded")
        val m = engine.metrics
        KeyValue("First token", m?.firstTokenMs?.let(::duration))
        KeyValue("Prefill", m?.promptTokensPerSecond?.let { "${decimal(it)} tok/s" })
        KeyValue("Decode", m?.generationTokensPerSecond?.let { "${decimal(it)} tok/s" })
        KeyValue("Prompt tokens", m?.promptTokens?.takeIf { it > 0 }?.toString())
        if (busy || engine.busy) FlatProgress(null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            BButton("Short check", { ai.speedCheck(SpeedCheck.Short) }, enabled = allowed.change && loaded)
            BButton("~2K check", { ai.speedCheck(SpeedCheck.LongPrefill) }, enabled = allowed.change && loaded)
            if (busy || engine.busy) BButton("Stop", { if (run.canStop) graph.stop() else ai.stop() })
        }
        Text("Short: a counting prompt, up to 32 output tokens. ~2K: a synthetic list of screen elements (about two thousand prompt tokens; the runtime reports the exact count), up to 16 output tokens. The main page's Test is the ~2K check. Measured on this phone; no phone actions or model network request.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Text("The main page's words are estimates from the last ~2K check: \"per step\" is its first-token time plus ${SpeedWords.STEP_OUTPUT_TOKENS} output tokens at its decode rate (one planner action); \"words/s\" is decode tokens/s × ${SpeedWords.WORDS_PER_TOKEN}. Not a measured phone task.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        if (results.isNotEmpty()) Disclosure("Results this session · ${results.size}", key = "speed-results") {
            results.forEach { r ->
                Text("${r.modelName} · ${r.runtime.label} ${r.backend.name} · ${r.check.label}", style = MaterialTheme.typography.bodyMedium)
                Text("${r.metrics.promptTokens.takeIf { it > 0 }?.toString() ?: "unmeasured"} prompt tokens · first token ${r.metrics.firstTokenMs?.let(::duration) ?: "not measured"} · prefill ${r.metrics.promptTokensPerSecond?.let { decimal(it) } ?: "-"} tok/s · decode ${r.metrics.generationTokensPerSecond?.let { decimal(it) } ?: "-"} tok/s", style = mono)
            }
            Text("Kept in memory until the app process ends; the last ~2K check per model is kept across restarts. LiteRT-LM rates are its own benchmark numbers; llama.cpp rates are its timing counters.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }

        SectionHeading("Engine for this model")
        if (entry != null && entry.backends.size > 1) {
            Text("Ballistic starts this model on the first choice below unless you pick another. Changing it unloads the model.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                entry.backends.forEach { b ->
                    val on = entry.backendFor(selected) == b
                    BButton((if (on) "(•) " else "( ) ") + b.name, { ai.chooseComputeBackend(b) }, enabled = allowed.change, emphasis = on)
                }
            }
        } else Text(if (entry != null) "${entry.runtime.label} on ${entry.backends.single().name} only (chosen for this model)."
            else "llama.cpp on CPU only.", style = MaterialTheme.typography.bodySmall)

        SectionHeading("Planner output")
        Column(verticalArrangement = Arrangement.spacedBy((-1).dp)) {
            RadioRow(!selected.constrainPlanner, "Free (default)", "The planner's reply is checked by core after it is written.", allowed.change) {
                ai.setConstrainPlanner(false)
            }
            RadioRow(selected.constrainPlanner, "Constrained to the action schema",
                "llama.cpp models only: decoding can only produce JSON matching core/schema/actions.json. LiteRT-LM models are unaffected. Not yet measured for quality or speed.",
                allowed.change) { ai.setConstrainPlanner(true) }
        }
        Text("Chat, reader and verifier output is never constrained. Core still validates every action.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)

        SectionHeading("Context")
        Text("4K is the starting point for app requests; some need 8K. 2K may fit only short prompts. Larger contexts need more memory. A Tensor NPU bundle has a context fixed when it was compiled; this setting does not change it.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            listOf(2048, 4096, 8192).forEach { tokens ->
                val on = selected.contextTokens == tokens
                BButton((if (on) "(•) " else "( ) ") + "${tokens / 1024}K", { ai.setContext(tokens) }, enabled = allowed.change, emphasis = on)
            }
        }
        Text("Changing context unloads the model. Prompts are never silently shortened.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)

        SectionHeading("Suggested settings for this phone")
        Text("Conservative starting settings, not measured performance. They do not choose or download a model. Applying them unloads the model.", style = MaterialTheme.typography.bodySmall)
        Text(ai.profile.suggestion, style = MaterialTheme.typography.bodySmall)
        KeyValue("Context", "${ai.profile.contextTokens} tokens")
        KeyValue("CPU threads", ai.profile.threads.toString())
        BButton("Apply suggested settings", ai::applyProfile, enabled = allowed.change)

        SectionHeading("Memory")
        KeyValue("Phone", ai.phone, mono = false)
        KeyValue("Memory", "${gib(memory.availMem)} free of ${gib(memory.totalMem)}")
        Text("System estimate, not Ballistic's memory use or a promise that a model fits.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)

        SectionHeading("Storage · import a file")
        KeyValue("Used", "${gib(ai.usedStorage)} by Ballistic models")
        KeyValue("Free", gib(ai.availableStorage))
        Text("Import your own compatible chat model in GGUF format (runs on llama.cpp, CPU). No model weights are bundled in the APK.", style = MaterialTheme.typography.bodySmall)
        BButton("Import GGUF", { import(DownloadableModel.IMPORTED_MODEL_ID) }, enabled = allowed.change)
        PendingImport(graph)

        SectionHeading("Runtimes")
        Text("Embedded llama.cpp CPU · 80d2c0c2c56e, and LiteRT-LM ${BuildConfig.LITERTLM_VERSION} (CPU/GPU/NPU) with the Google Tensor NPU runtime ${BuildConfig.LITERT_NPU_GOOGLE_TENSOR_VERSION}.\nContext setting: ${selected.contextTokens} · CPU threads: ${selected.threads}\nAll roles share one loaded model, with a fresh context per call. Generation limit: 1,024 tokens. No prompt truncation or cloud fallback.",
            style = mono)
        Text("Tensor NPU: ${ai.tensorInfo}", style = MaterialTheme.typography.bodySmall)
        KeyValue("Model", "${entry?.name ?: model?.name ?: "none"} · ${engine.phase.label()}", mono = false)
        KeyValue("Model identity", engine.metadata?.description)
        KeyValue("Quantization", engine.metadata?.quantization)
        KeyValue("Training context", engine.metadata?.trainingContext?.takeIf { it > 0 }?.toString())
        KeyValue("Allocated context", engine.metadata?.context?.takeIf { it > 0 }?.toString())
        KeyValue("Model load", engine.loadMs?.let(::duration))
        KeyValue("Last model call", engine.metrics?.durationMs?.let(::duration))
        KeyValue("Token counts", engine.metrics?.let { "${it.promptTokens} prompt · ${it.generatedTokens} generated" })
        if (engine.metrics?.capped == true) Text("The generation limit was reached.", style = MaterialTheme.typography.bodySmall)
        Text(modelLine(graph), style = MaterialTheme.typography.bodySmall)
        Text("Metadata is read on successful load. First-token latency includes framing and prefill. llama.cpp's generation rate excludes the first token. Metrics reset on unload or app restart.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)

        SectionHeading("Runtime log")
        engine.metadata?.backendReport?.let { Text(it, style = mono, color = c.onSurfaceVariant) }
        engine.error?.let { Text("Error: $it", style = MaterialTheme.typography.bodySmall) }
        engine.errorDetail?.let { Text(it, style = mono, color = c.onSurfaceVariant) }
        if (engine.metadata?.backendReport == null && engine.error == null) Text("Nothing reported.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)

        SectionHeading("Licences")
        Text("llama.cpp: MIT license; ggml and vendored component notices are in this app's assets. LiteRT-LM and the LiteRT NPU runtime: Apache-2.0. Model licences are separate and shown in each model's Details.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)

        Hairline(Modifier.padding(top = Space.s2))
        Text("Ballistic never switches to a connected server by itself, and never to another model or backend after a failure. If loading fails, nothing is loaded and nothing else is tried.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(Space.s4))
    }
}
