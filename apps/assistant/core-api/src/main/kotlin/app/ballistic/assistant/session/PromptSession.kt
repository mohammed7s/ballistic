// The prompt box's state, independent of Android: the counterpart of harness/chat.py. The owner
// types a request; the session checks the command gate, runs it through RustCore, shows each step
// (action, why, outcome) as core reports it, and offers Stop. One active run at a time.
//
// State flow (docs/ANDROID_APP.md): idle -> command authentication -> running <-> awaiting
// approval -> result. Command authentication and operation approval are separate checks.

package app.ballistic.assistant.session

import app.ballistic.assistant.core.ActionLog
import app.ballistic.assistant.core.Confirm
import app.ballistic.assistant.core.ConfirmRequest
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.HostPorts
import app.ballistic.assistant.core.LogLine
import app.ballistic.assistant.core.RunListener
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.RustCore
import app.ballistic.assistant.core.StepEvent
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the command gate (SPEC.md §7.8) has passed for this session. Owned by the app. */
interface CommandGateState {
    val unlocked: StateFlow<Boolean>
}

enum class Phase { Idle, NeedsUnlock, Running, AwaitingApproval, Finished }

/** One request and what happened to it. */
data class RunView(
    val id: String,
    val request: String,
    val steps: List<StepEvent> = emptyList(),
    val result: RunResult? = null,
    val error: String? = null,
)

data class PromptState(
    val phase: Phase = Phase.Idle,
    val engine: String = "",
    /** What core is doing now, while running. */
    val activity: String? = null,
    /** Earlier runs first, the current or last run at the end. */
    val runs: List<RunView> = emptyList(),
    /** Request waiting for the gate to pass. */
    val pendingRequest: String? = null,
) {
    /** A second submission is refused until the current run ends. */
    val canSubmit: Boolean get() = phase != Phase.Running && phase != Phase.AwaitingApproval
    val canStop: Boolean get() = phase == Phase.Running || phase == Phase.AwaitingApproval
}

/**
 * [corePorts] builds the RustCore from ports; the session wraps the Confirm port so it can show
 * "awaiting approval", which is the only change it makes to what core sees.
 */
class PromptSession(
    private val scope: CoroutineScope,
    private val gate: CommandGateState,
    ports: HostPorts,
    corePorts: (HostPorts) -> RustCore,
    private val log: ActionLog? = null,
    private val maxSteps: Int = 15,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val modelLifecycle = ports.models as? app.ballistic.assistant.models.ModelRunLifecycle
    private val core: RustCore = corePorts(ports.copy(confirm = ObservedConfirm(ports.confirm)))
    private val _state = MutableStateFlow(PromptState(engine = core.description))
    val state: StateFlow<PromptState> = _state.asStateFlow()

    /** Starts a run, or parks the request until the gate passes. Ignored while a run is active. */
    fun submit(text: String) {
        val request = text.trim()
        if (request.isEmpty() || !_state.value.canSubmit) return
        if (!gate.unlocked.value) {
            _state.update { it.copy(phase = Phase.NeedsUnlock, pendingRequest = request) }
            return
        }
        start(request)
    }

    /** Called by the app when the gate passes; runs a request parked by [submit]. */
    fun onUnlocked() {
        val pending = _state.value.pendingRequest ?: return
        if (_state.value.phase == Phase.NeedsUnlock && gate.unlocked.value) start(pending)
    }

    /** The gate prompt was dismissed or failed: drop the parked request. */
    fun onUnlockCancelled() {
        _state.update {
            if (it.phase == Phase.NeedsUnlock) it.copy(phase = Phase.Idle, pendingRequest = null) else it
        }
    }

    fun stop() {
        core.stop()
        modelLifecycle?.cancelRun()
    }

    /** Re-reads which engine will take the next run (it depends on Settings). */
    fun refreshEngine() {
        _state.update { it.copy(engine = core.description) }
    }

    private fun start(request: String) {
        modelLifecycle?.beginRun()
        val run = RunView(id = UUID.randomUUID().toString(), request = request)
        _state.update {
            it.copy(phase = Phase.Running, engine = core.description, pendingRequest = null, activity = "starting", runs = it.runs + run)
        }
        scope.launch {
            val listener = object : RunListener {
                override fun onStep(event: StepEvent) {
                    log?.append(LogLine(run.id, clock(), request, core.description, event))
                    updateRun(run.id) { it.copy(steps = it.steps + event) }
                }

                override fun onActivity(activity: String) {
                    _state.update { s -> if (s.phase == Phase.Running) s.copy(activity = activity) else s }
                }
            }
            val (result, error) = try {
                core.run(request, maxSteps, listener) to null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    modelLifecycle?.cancelRun()
                    modelLifecycle?.endRun()
                    throw e
                }
                null to (e.message ?: e::class.simpleName ?: "error")
            }
            modelLifecycle?.endRun()
            updateRun(run.id) { it.copy(result = result, error = error) }
            _state.update { it.copy(phase = Phase.Finished, activity = null) }
        }
    }

    private fun updateRun(id: String, change: (RunView) -> RunView) {
        _state.update { s -> s.copy(runs = s.runs.map { if (it.id == id) change(it) else it }) }
    }

    /** Marks the session as awaiting approval for the duration of the owner's decision. */
    private inner class ObservedConfirm(private val inner: Confirm) : Confirm {
        override fun ask(request: ConfirmRequest): ConfirmResult {
            _state.update { it.copy(phase = Phase.AwaitingApproval, activity = "awaiting approval") }
            try {
                return inner.ask(request)
            } finally {
                _state.update { if (it.phase == Phase.AwaitingApproval) it.copy(phase = Phase.Running, activity = "acting") else it }
            }
        }
    }
}
