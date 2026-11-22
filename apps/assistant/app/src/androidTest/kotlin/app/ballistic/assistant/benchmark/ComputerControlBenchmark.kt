package app.ballistic.assistant.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.BallisticApp
import app.ballistic.assistant.MainActivity
import app.ballistic.assistant.computercontrol.CcState
import app.ballistic.assistant.core.RecordJson
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.UniffiCoreEngine
import app.ballistic.assistant.models.ModelBackend
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Test-APK entry point. No shell identity adoption, grants, fake ports or automatic approvals.
 * The host supplies only a goal, never observations, actions or independent checker state.
 * Results are private files, fetched by the host via run-as on a disposable emulator.
 */
class ComputerControlBenchmark : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val id = args.getString("request_id").orEmpty()
        val output = JSONObject().put("schema", 1).put("transport", "apk_computer_control")
            .put("request_id", id).put("status", "error")
            .put("fingerprint", Build.FINGERPRINT).put("hardware", Build.HARDWARE)
            .put("approval_mode", "owner").put("max_steps", 15)
        var graph: AppGraph? = null
        var activity: Activity? = null
        var destination: File? = null
        val started = SystemClock.elapsedRealtime()
        try {
            require(id.matches(Regex("[a-f0-9]{32}"))) { "invalid_request_id" }
            // Fail closed on phones, even if the host-side serial check was bypassed.
            require(Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm", "cutf_ivsh")) { "virtual_device_required" }
            require(Build.VERSION.SDK_INT >= 37) { "api_37_required" }
            val directory = File(targetContext.filesDir, "cc_benchmark").apply { mkdirs() }
            val resultFile = File(directory, "$id.result.json")
            require(!resultFile.exists()) { "request_already_used" }
            destination = resultFile
            val requestFile = File(directory, "$id.request.json")
            require(requestFile.length() in 1..32_768) { "invalid_request_file" }
            val request = JSONObject(requestFile.readText())
            val goal = request.getString("goal")
            require(goal.isNotBlank() && goal.length <= 8_000) { "invalid_goal" }
            val timeout = request.getInt("timeout_seconds")
            require(timeout in 10..1800) { "invalid_timeout" }
            requestFile.delete()
            output.put("goal", goal)
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val g = (activity.application as BallisticApp).graph
            graph = g
            require(g.engine.current is UniffiCoreEngine) { "real_core_required" }
            require(g.session.state.value.canSubmit && !g.localAi.busy.value) { "app_busy" }
            require(onMain { g.gate.canAcceptCommands() }) { "owner_unlock_required" }
            // Models must already be configured/loaded through the app's ordinary setup.
            if (g.localAi.selection.value.backend == ModelBackend.Local) {
                require(g.localModels.state.value.metadata != null) { "load_local_model_first" }
            }
            output.put("model_route", g.routeLabel()).put("engine", g.engine.description)
            val selection = g.localAi.selection.value
            output.put("model_backend", selection.backend.name)
            if (selection.backend == ModelBackend.Local) {
                output.put("model_id", selection.activeModel ?: "imported")
                    .put("compute_backend", selection.backendChoice?.name ?: "catalogue_default")
            } else {
                val config = g.modelsConfig.load() ?: error("model_setup_required")
                output.put("models", JSONObject().apply {
                    ModelRole.entries.forEach { put(it.name, config.endpoint(it).model) }
                })
            }
            val missing = onMain { g.computerControl.platform().missing }
            require(missing == null) { "setup_required:${missing?.name}" }
            val reason = onMain { g.startComputerControl(activity as MainActivity) }
            require(reason == null) { "session_start:${reason?.name}" }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (g.computerControl.state.value !is CcState.Active && SystemClock.elapsedRealtime() < deadline) {
                val state = g.computerControl.state.value
                require(state !is CcState.ConsentNeeded) { "owner_target_consent_required" }
                require(state !is CcState.Idle) { "session_not_active:$state" }
                SystemClock.sleep(100)
            }
            val active = g.computerControl.state.value as? CcState.Active ?: error("session_start_timeout")
            output.put("targets", JSONArray(active.targets)).put("session_active_at_submit", true)
            // Each benchmark case starts a new conversation, while retaining app policy/models.
            onMain {
                check(g.session.clearConversation()) { "app_busy" }
                g.session.submit(goal)
            }
            val runId = g.session.state.value.runs.lastOrNull()?.id ?: error("request_not_admitted")
            val runDeadline = SystemClock.elapsedRealtime() + timeout * 1000L
            var timedOut = false
            while (true) {
                val run = g.session.state.value.runs.lastOrNull { it.id == runId } ?: error("run_disappeared")
                if (run.finished) break
                if (SystemClock.elapsedRealtime() >= runDeadline) {
                    timedOut = true
                    onMain { g.stop() }
                    break
                }
                SystemClock.sleep(200)
            }
            val run = g.session.state.value.runs.last { it.id == runId }
            output.put("status", if (timedOut) "timeout" else "finished")
                .put("run_id", runId).put("exchange_kind", run.kind.name)
                .put("agent_seconds", g.session.elapsedMillis(run) / 1000.0)
                .put("reply", run.reply ?: JSONObject.NULL).put("error", run.error ?: JSONObject.NULL)
                .put("report", run.result?.let { JSONObject(RecordJson.encodeToString(RunResult.serializer(), it)) } ?: JSONObject.NULL)
                .put("steps", JSONArray(run.steps.map { JSONObject(RecordJson.encodeToString(StepEvent.serializer(), it)) }))
                .put("progress", JSONArray(run.progress.map { JSONObject().put("elapsed_ms", it.elapsedMs).put("text", it.text) }))
            // This is agent output only. The host must run independent state checks separately.
            output.put("independent_state", "not_checked")
        } catch (e: Exception) {
            // Do not export arbitrary runtime exception text (it may contain app data/URLs).
            output.put("error_type", e.javaClass.simpleName)
            val known = e.message.orEmpty()
            val prefixes = listOf("invalid_", "virtual_device_", "api_37_", "request_", "real_core_", "app_busy",
                "owner_", "load_local_", "setup_required:", "session_", "run_disappeared")
            output.put("reason", if (prefixes.any(known::startsWith)) known else "instrumentation_error")
        } finally {
            graph?.let { g -> runCatching { onMain { g.stop() } } }
            output.put("wall_seconds", (SystemClock.elapsedRealtime() - started) / 1000.0)
            destination?.let { file ->
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(output.toString(2))
                check(temporary.renameTo(file)) { "result_write_failed" }
            }
            activity?.let { runCatching { onMain { it.finish() } } }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("benchmark_status", output.getString("status"))
                putString("result_file", destination?.name ?: "none")
            })
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
