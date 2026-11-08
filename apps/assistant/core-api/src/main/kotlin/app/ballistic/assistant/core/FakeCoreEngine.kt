// A scripted stand-in for core, for building the UI and the adapters before the Rust library is
// bound. It replays a fixed list of actions through the real ports, so the prompt box, the
// confirmation window and Hands can be exercised, but it plans nothing, makes no model calls,
// resolves no targets and verifies nothing. Anything it shows is labelled as scripted.

package app.ballistic.assistant.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One scripted step. [op] is what Hands receives (the fake does no target resolution, so the
 * script supplies it); [confirm] is asked before dispatch when present, as core would for a
 * guarded action.
 */
data class ScriptedStep(
    val action: Action,
    val why: String,
    val op: HandsOp? = null,
    val confirm: ConfirmRequest? = null,
)

class FakeCoreEngine(
    private val script: List<ScriptedStep>,
    /** Pause between steps so the UI can be watched; checked against Stop while waiting. */
    private val stepDelayMillis: Long = 0,
) : CoreEngine {
    override val description: String = "Fake core (scripted replay, no planning)"

    override fun run(request: String, maxSteps: Int, ports: HostPorts, listener: RunListener, cancel: CancelToken): RunResult {
        var execution = ExecutionStatus.NotExecuted
        var task = TaskStatus.Pending
        for ((index, scripted) in script.take(maxSteps).withIndex()) {
            val step = index + 1
            val started = System.nanoTime()
            if (cancel.isCancelled || !pause(cancel)) return stopped(step - 1, task, execution)

            listener.onActivity("observing")
            val screen = try {
                ports.screen.observe()
            } catch (e: PortException) {
                return RunResult(false, e.message ?: "observe failed", step, TaskStatus.Failed, execution, failureCategory = "environment")
            }

            var outcome: String
            var failure: String? = null
            var terminal = false
            var stepExecution = ExecutionStatus.NotExecuted
            when (val action = scripted.action) {
                is Action.Done -> {
                    outcome = "scripted done (fake core: nothing verified)"
                    task = TaskStatus.Completed
                    terminal = true
                }
                is Action.GiveUp -> {
                    outcome = "planner gave up"
                    task = TaskStatus.Failed; failure = "planning"; terminal = true
                }
                is Action.Read, is Action.Answer -> {
                    outcome = "reader and answer provenance are not implemented"
                    task = TaskStatus.Unsupported; failure = "planning"; terminal = true
                }
                else -> {
                    val op = scripted.op ?: error("script step $step (${action::class.simpleName}) has no HandsOp")
                    var approved = true
                    scripted.confirm?.let { request ->
                        listener.onActivity("awaiting approval")
                        approved = try {
                            ports.confirm.ask(request) == ConfirmResult.Approved
                        } catch (e: PortException) {
                            false
                        }
                    }
                    if (!approved) {
                        outcome = "owner denied confirmation"
                        task = TaskStatus.Failed; failure = "policy_denial"; terminal = true
                    } else if (cancel.isCancelled) {
                        // Checked immediately before dispatch: Stop after approval still prevents the act.
                        return stopped(step - 1, task, execution)
                    } else {
                        listener.onActivity("acting")
                        val result = try {
                            ports.hands.act(op)
                        } catch (e: PortException) {
                            HandsResult.Error(e.message ?: "hands failed")
                        }
                        when (result) {
                            HandsResult.Ok -> {
                                execution = ExecutionStatus.Executed; stepExecution = ExecutionStatus.Executed
                                task = if (op is HandsOp.OpenApp) TaskStatus.OpenedUi else TaskStatus.Pending
                                outcome = if (op is HandsOp.OpenApp) "opened_ui" else "host acknowledged input"
                                if (scripted.confirm != null) {
                                    outcome = "guarded input acknowledged; effect unverified; stopped because reliable readback or idempotency is unavailable"
                                    failure = "verification"; terminal = true
                                }
                            }
                            is HandsResult.NotExecuted -> {
                                outcome = "host did not execute action: ${result.message}"; failure = "execution"
                            }
                            is HandsResult.Error -> {
                                execution = ExecutionStatus.Unknown; stepExecution = ExecutionStatus.Unknown
                                task = TaskStatus.Pending
                                outcome = "execution outcome unknown; stopped to prevent duplicate effects"
                                failure = "execution"; terminal = true
                            }
                        }
                    }
                }
            }
            listener.onStep(
                StepEvent(
                    step = step,
                    action = logForm(scripted.action),
                    why = scripted.why,
                    outcome = outcome,
                    taskStatus = task,
                    executionStatus = execution,
                    failureCategory = failure,
                    stepExecutionStatus = stepExecution,
                    capabilityMode = if (screen.capabilities != null) "advertised" else "missing_denied",
                    seconds = (System.nanoTime() - started) / 1e9,
                ),
            )
            if (terminal) {
                return RunResult(task == TaskStatus.Completed, outcome, step, task, execution, failureCategory = failure)
            }
        }
        return RunResult(false, "step budget exhausted", minOf(script.size, maxSteps), task, execution, failureCategory = "planning")
    }

    /**
     * The scripted stand-in has no chat model: every message replays the script as a task. It
     * returns no history, so nothing scripted enters the stored conversation.
     */
    override fun converse(
        history: List<ChatTurn>, message: String, options: CoreOptions,
        ports: HostPorts, listener: RunListener, cancel: CancelToken,
    ): TurnResult {
        val result = run(message, options.maxSteps, ports, listener, cancel)
        return TurnResult(
            kind = TurnKind.Task,
            text = "Scripted replay by the demo engine; nothing was planned or verified.",
            request = message,
            report = RecordJson.encodeToJsonElement(RunResult.serializer(), result),
        )
    }

    private fun stopped(steps: Int, task: TaskStatus, execution: ExecutionStatus) =
        RunResult(false, "stopped by owner", steps, task, execution, stopped = true)

    /** Returns false if Stop arrived during the pause. */
    private fun pause(cancel: CancelToken): Boolean {
        var left = stepDelayMillis
        while (left > 0) {
            if (cancel.isCancelled) return false
            Thread.sleep(minOf(left, 50))
            left -= 50
        }
        return !cancel.isCancelled
    }

    companion object {
        /** Core's log form of an action (core/src/actions.rs `to_json`): `e7` targets, `c3` reads. */
        fun logForm(action: Action): JsonElement {
            val obj = CoreJson.encodeToJsonElement(Action.serializer(), action) as JsonObject
            val out = obj.toMutableMap()
            when (val t = obj["target"]) {
                null -> {}
                JsonNull -> out.remove("target")
                else -> t.jsonPrimitive.intOrNull?.let { out["target"] = JsonPrimitive("e$it") }
            }
            (obj["targets"] as? JsonArray)?.let { ids ->
                out["targets"] = JsonArray(ids.mapNotNull { it.jsonPrimitive.intOrNull?.let { n -> JsonPrimitive("c$n") } })
            }
            return JsonObject(out)
        }

        /**
         * A short demonstration: open Clock, wait, one soft-confirmed tap. The run then stops with
         * "effect unverified", as core does after any acknowledged guarded input.
         */
        fun demo(): FakeCoreEngine = FakeCoreEngine(demoSteps(), stepDelayMillis = 600)

        fun demoSteps(): List<ScriptedStep> = listOf(
            ScriptedStep(Action.OpenApp("Clock"), "The alarm lives in Clock.", HandsOp.OpenApp("com.android.deskclock")),
            ScriptedStep(Action.Wait, "Let the app draw.", HandsOp.Wait(500)),
            ScriptedStep(
                Action.Tap(4), "Save the alarm.", HandsOp.Tap(540, 2100),
                ConfirmRequest(
                    operation = HandsOp.Tap(540, 2100),
                    app = "com.android.deskclock",
                    verb = "tap e4",
                    target = "Save",
                    values = listOf(Tagged("07:00", Source.OwnerRequest)),
                    hard = false,
                    reason = "alarms take a soft confirmation",
                ),
            ),
        )
    }
}
