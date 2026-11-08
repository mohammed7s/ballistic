// The shell's view of core: one request in, events while it runs, one result out. Core owns the
// loop (observe, plan, check, confirm, dispatch, verify); the shell supplies the four ports and
// shows what comes back (core/README.md §15 rule 6). Nothing here decides an action.

package app.ballistic.assistant.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Task progress, separate from execution and verification (SPEC.md §5.5). */
@Serializable
enum class TaskStatus {
    @SerialName("completed") Completed,
    @SerialName("opened_ui") OpenedUi,
    @SerialName("pending") Pending,
    @SerialName("failed") Failed,
    @SerialName("unsupported") Unsupported,
}

/**
 * One step as core logs it (core/src/agent.rs `host.log(...)`; core/README.md §13). [action] is
 * core's log form (`"target":"e7"`), kept as JSON because it is shown, never executed.
 * Token counts are planned for the log (§13) and absent from core's current lines.
 */
@Serializable
data class StepEvent(
    val step: Int,
    val action: JsonElement,
    val why: String = "",
    val outcome: String,
    @SerialName("task_status") val taskStatus: TaskStatus,
    @SerialName("execution_status") val executionStatus: ExecutionStatus,
    val verification: Verification = Verification(),
    @SerialName("failure_category") val failureCategory: String? = null,
    @SerialName("step_execution_status") val stepExecutionStatus: ExecutionStatus = ExecutionStatus.NotExecuted,
    @SerialName("capability_mode") val capabilityMode: String? = null,
    val seconds: Double = 0.0,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
)

/**
 * What core returns at the end of a run (the JSON `agent::run` returns). [stopped] records
 * the owner's Stop request; execution status retains any effects from earlier steps.
 */
@Serializable
data class RunResult(
    val done: Boolean,
    val reason: String,
    val steps: Int,
    @SerialName("task_status") val taskStatus: TaskStatus,
    @SerialName("execution_status") val executionStatus: ExecutionStatus,
    val verification: Verification = Verification(),
    @SerialName("failure_category") val failureCategory: String? = null,
    val stopped: Boolean = false,
)

/** Set by Stop. Core checks it before every model call and immediately before every dispatch. */
class CancelToken {
    private val flag = AtomicBoolean(false)
    val isCancelled: Boolean get() = flag.get()
    fun cancel() = flag.set(true)
}

/** Events core emits while a run is in progress. Called on core's thread. */
interface RunListener {
    /** One finished step, the same object that goes to the action log. */
    fun onStep(event: StepEvent)
    /** What core is doing now ("observing", "planning", "awaiting approval", …), for display. */
    fun onActivity(activity: String) {}
}

/** The engine behind [RustCore]: the Rust library, or a scripted stand-in for UI work. */
interface CoreEngine {
    /** Shown in the UI so a scripted run is never mistaken for an agent run. */
    val description: String

    /**
     * Runs [request] to a terminal result, calling [ports] as core decides. Blocks the calling
     * thread for the whole run; never call on the main thread.
     */
    fun run(request: String, maxSteps: Int, ports: HostPorts, listener: RunListener, cancel: CancelToken): RunResult

    /**
     * One conversation turn: [history] is what earlier turns returned in [TurnResult.history],
     * [message] the owner's new words. A task turn runs the loop on [ports] (its steps reach
     * [listener]); a reply calls only the chat model. Blocks; never call on the main thread.
     * Engines without a conversation path say so in a fixed turn and call no port.
     */
    fun converse(
        history: List<ChatTurn>, message: String, options: CoreOptions,
        ports: HostPorts, listener: RunListener, cancel: CancelToken,
    ): TurnResult = TurnResult(
        kind = TurnKind.NotUnderstood,
        text = "I can't hold a conversation with this engine ($description).",
    )
}

/**
 * The facade the shell calls. Runs the engine off the caller's thread, allows one active run,
 * and turns Stop (or cancellation of the calling coroutine) into the engine's [CancelToken].
 * The loop is not exposed step by step: sequencing belongs to core, and a shell that called
 * `step()` itself would be deciding when to act.
 */
class RustCore(
    private val engine: CoreEngine,
    private val ports: HostPorts,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val active = AtomicReference<CancelToken?>(null)

    val description: String get() = engine.description
    val isRunning: Boolean get() = active.get() != null

    /** @throws IllegalStateException if a run is already active. */
    suspend fun run(request: String, maxSteps: Int, listener: RunListener): RunResult {
        val token = CancelToken()
        check(active.compareAndSet(null, token)) { "a run is already active" }
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { token.cancel() }
        try {
            return withContext(dispatcher) { engine.run(request, maxSteps, ports, listener, token) }
        } finally {
            handle?.dispose()
            active.compareAndSet(token, null)
        }
    }

    /**
     * One conversation turn (see [CoreEngine.converse]). Shares the one-active-run rule and Stop
     * with [run]: a task turn is a run.
     * @throws IllegalStateException if a run is already active.
     */
    suspend fun converse(history: List<ChatTurn>, message: String, options: CoreOptions, listener: RunListener): TurnResult {
        val token = CancelToken()
        check(active.compareAndSet(null, token)) { "a run is already active" }
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { token.cancel() }
        try {
            return withContext(dispatcher) { engine.converse(history, message, options, ports, listener, token) }
        } finally {
            handle?.dispose()
            active.compareAndSet(token, null)
        }
    }

    /** Prevents new model calls and dispatches. Cannot undo an operation already submitted. */
    fun stop() {
        active.get()?.cancel()
    }
}
