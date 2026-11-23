package app.ballistic.assistant.benchmark

import android.app.Instrumentation
import android.os.SystemClock
import app.ballistic.assistant.AppGraph
import app.ballistic.assistant.MainActivity
import app.ballistic.assistant.adapters.ComputerControlPlatform
import app.ballistic.assistant.computercontrol.*
import app.ballistic.assistant.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fixed, emulator-only adapter fixture. No model/core decisions, host input or permission grants.
 * Uses production platform/Screen/Hands adapters and the saved Clock policy. This deliberately
 * does not claim to test the agent, UiPolicyHands, operation approvals or the app's full Stop flow.
 * Only launch Clock and tap its Stopwatch tab; never start/reset a timer or stopwatch.
 */
internal class ComputerControlAdapterSmoke(
    private val instrument: Instrumentation,
    private val activity: MainActivity,
    private val graph: AppGraph,
) {
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrument.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    fun run(output: JSONObject) {
        val running = AtomicBoolean(true)
        val checks = JSONObject()
        output.put("test_kind", "scripted_adapter_fixture").put("model_calls", 0).put("max_steps", 0)
            .put("checks", checks).put("independent_state", "not_checked")
        fun policy() = graph.policyStore.load().forApp(CLOCK)
        require(onMain { graph.gate.canAcceptCommands() }) { "owner_unlock_required" }
        require(graph.session.state.value.canSubmit) { "app_busy" }
        require(ScreenControlRules.mayRead(policy(), true) && ScreenControlRules.mayAct(policy()) &&
            policy().act == Act.Full && policy().confirm == ConfirmMode.DefaultList) { "setup_required:clock_policy" }
        val platform = ComputerControlPlatform(graph.app,
            onEnded = { running.set(false) },
            mayInput = { pkg, full ->
                running.get() && pkg == CLOCK && graph.gate.canAcceptCommands() &&
                    ScreenControlRules.mayAct(policy()) && (!full || policy().act == Act.Full) &&
                    policy().confirm == ConfirmMode.DefaultList
            })
        try {
            // Activity focus can arrive after startActivitySync returns.
            val focusDeadline = SystemClock.elapsedRealtime() + 5_000
            while (!onMain { activity.hasWindowFocus() } && SystemClock.elapsedRealtime() < focusDeadline)
                SystemClock.sleep(100)
            val reason = onMain { platform.start(activity, listOf(CLOCK)) }
            require(reason == null) { "session_start:${reason?.name}" }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (platform.state.value !is CcState.Active && SystemClock.elapsedRealtime() < deadline) {
                require(platform.state.value !is CcState.ConsentNeeded) { "owner_target_consent_required" }
                require(platform.state.value !is CcState.Idle) { "session_not_active" }
                SystemClock.sleep(100)
            }
            check(platform.state.value is CcState.Active) { "session_start_timeout" }
            checks.put("session_active", true)
            val hands = ComputerControlHands({ platform.view })
            val screen = ComputerControlScreen({ platform.view },
                mayRead = { it == CLOCK && ScreenControlRules.mayRead(policy(), true) },
                installedApps = { listOf(AppEntry("Clock", CLOCK)) },
                capabilities = { HostCapabilities(emptyList(), listOf(CLOCK)) })
            val launch = hands.act(HandsOp.OpenApp(CLOCK))
            output.put("launch", JSONObject(RecordJson.encodeToString(HandsResult.serializer(), launch)))
            check(launch == HandsResult.Ok) { "session_clock_launch_failed" }
            var before = screen.observe()
            val treeDeadline = SystemClock.elapsedRealtime() + 10_000
            while (before.nodes.isEmpty() && SystemClock.elapsedRealtime() < treeDeadline) {
                SystemClock.sleep(300)
                before = screen.observe()
            }
            output.put("before", JSONObject(RecordJson.encodeToString(ScreenState.serializer(), before)))
            check(before.`package` == CLOCK && before.nodes.isNotEmpty()) { "session_clock_tree_missing" }
            checks.put("clock_tree", true)
            val tabs = before.nodes.filter { n -> n.flags.clickable && n.flags.visible &&
                (n.label?.equals("Stopwatch", true) == true || n.text?.equals("Stopwatch", true) == true) }
            check(tabs.size == 1) { "session_stopwatch_target_ambiguous" }
            val b = tabs.single().bounds
            val tap = hands.act(HandsOp.Tap((b.x1 + b.x2) / 2, (b.y1 + b.y2) / 2))
            output.put("tap", JSONObject(RecordJson.encodeToString(HandsResult.serializer(), tap)))
            check(tap == HandsResult.Ok) { "session_stopwatch_tap_failed" }
            SystemClock.sleep(500)
            val after = screen.observe()
            output.put("after", JSONObject(RecordJson.encodeToString(ScreenState.serializer(), after)))
            val selected = after.nodes.any { n -> n.flags.selected &&
                (n.label?.equals("Stopwatch", true) == true || n.text?.equals("Stopwatch", true) == true) }
            check(selected) { "session_stopwatch_selection_unverified" }
            checks.put("stopwatch_selected", true)
            val denied = hands.act(HandsOp.OpenApp("com.android.settings"))
            checks.put("non_target_rejected", denied is HandsResult.NotExecuted)
            check(denied is HandsResult.NotExecuted) { "session_non_target_not_rejected" }
            val old = platform.view ?: error("session_disappeared")
            onMain { platform.close() }
            val stopped = hands.act(HandsOp.OpenApp(CLOCK))
            val staleRejected = runCatching { old.input.launch(CLOCK, null) }.isFailure
            checks.put("closed_input_rejected", stopped is HandsResult.NotExecuted)
                .put("retained_session_input_rejected", staleRejected)
            check(stopped is HandsResult.NotExecuted && staleRejected) { "session_close_not_enforced" }
            output.put("status", "passed")
        } finally {
            running.set(false)
            onMain { platform.close() }
        }
    }

    companion object { private const val CLOCK = "com.android.deskclock" }
}
