// The Ask thread's state, independent of Android: the counterpart of harness/chat.py. The owner
// types a message; the session checks the command gate and passes it to core as one conversation
// turn (RustCore.converse) with the stored history. Core decides whether it is a reply, a
// question back or a phone task, and runs the task itself; the session shows what comes back
// (a reply as text, a task as a card with each step core reports) and offers Stop. One active
// turn at a time. Without a conversation store the session runs each message as a task (run).
//
// State flow (docs/ANDROID_APP.md): idle -> command authentication -> running <-> awaiting
// approval -> result. Command authentication and operation approval are separate checks.

package app.ballistic.assistant.session

import app.ballistic.assistant.core.ActionLog
import app.ballistic.assistant.core.ChatTurn
import app.ballistic.assistant.core.Confirm
import app.ballistic.assistant.core.ConfirmRequest
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.CoreOptions
import app.ballistic.assistant.core.HostPorts
import app.ballistic.assistant.core.LogLine
import app.ballistic.assistant.core.RecordJson
import app.ballistic.assistant.core.RunListener
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.RustCore
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.Tagged
import app.ballistic.assistant.core.TurnKind
import app.ballistic.assistant.core.TurnResult
import app.ballistic.assistant.core.TurnSource
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
    fun canAcceptCommands(): Boolean = unlocked.value
}

enum class Phase { Idle, NeedsUnlock, Running, AwaitingApproval, Finished }

data class ProgressLine(val elapsedMs: Long, val text: String)

/**
 * What one Ask message became. [Pending] until core answers or starts the agent loop; [Task] as
 * soon as core observes, plans or reports a step (a task turn runs the loop), so the card
 * appears while it runs.
 */
enum class ExchangeKind { Pending, Reply, Clarify, Task, NotUnderstood, Stopped, Error }

/** One owner message and what happened to it. */
data class RunView(
    val id: String,
    /** The owner's own words. */
    val request: String,
    val steps: List<StepEvent> = emptyList(),
    val progress: List<ProgressLine> = emptyList(),
    val result: RunResult? = null,
    val error: String? = null,
    /** Monotonic clock values, valid for this process only. Authentication wait is excluded. */
    val startedElapsedMs: Long = 0,
    val finishedElapsedMs: Long? = null,
    val stopRequested: Boolean = false,
    val kind: ExchangeKind = ExchangeKind.Task,
    /** Core's thread line: the reply, the question, or a finished task's first-person result. */
    val reply: String? = null,
    /** Values in [reply] the owner did not say, with their sources (core already annotates the text). */
    val values: List<Tagged> = emptyList(),
    /** Where the model ran for this message ("on this phone", "connected: host"), recorded at submit. */
    val route: String? = null,
    /** The self-contained request core ran for a task turn, if it differs from [request]. */
    val ranAs: String? = null,
    /** Rebuilt from the stored conversation after a restart: no steps, times or report. */
    val restored: Boolean = false,
) {
    val isTask: Boolean get() = kind == ExchangeKind.Task
    val finished: Boolean get() = finishedElapsedMs != null || result != null || error != null || restored
}

data class PromptState(
    val phase: Phase = Phase.Idle,
    val engine: String = "",
    /** What core is doing now, while running. */
    val activity: String? = null,
    /** Earlier messages first, the current or last one at the end. */
    val runs: List<RunView> = emptyList(),
    /** Request waiting for the gate to pass. */
    val pendingRequest: String? = null,
) {
    /** A second submission is refused until the current run ends. */
    val canSubmit: Boolean get() = phase != Phase.Running && phase != Phase.AwaitingApproval
    val canStop: Boolean get() = phase == Phase.Running || phase == Phase.AwaitingApproval
    /** The task that is running now, if the active message became one. */
    val activeTask: RunView? get() = runs.lastOrNull()?.takeIf { canStop && it.isTask }
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
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The stored conversation. Null: every message runs as a task (no chat). */
    private val conversation: ConversationStore? = null,
    /** Where requests go now, recorded on each message. */
    private val route: () -> String = { "" },
) {
    private val modelLifecycle = ports.models as? app.ballistic.assistant.models.ModelRunLifecycle
    private val core: RustCore = corePorts(ports.copy(confirm = ObservedConfirm(ports.confirm)))
    @Volatile private var stopRequested = false
    private val _state = MutableStateFlow(PromptState(
        engine = core.description,
        runs = conversation?.load()?.let(::exchangesFromHistory).orEmpty(),
    ))
    val state: StateFlow<PromptState> = _state.asStateFlow()

    fun elapsedMillis(run: RunView): Long =
        ((run.finishedElapsedMs ?: elapsedClock()) - run.startedElapsedMs).coerceAtLeast(0)

    /** Starts a turn, or parks the message until the gate passes. Ignored while one is active. */
    fun submit(text: String) {
        val request = text.trim()
        if (request.isEmpty() || !_state.value.canSubmit) return
        if (!gate.canAcceptCommands()) {
            _state.update { it.copy(phase = Phase.NeedsUnlock, pendingRequest = request) }
            return
        }
        start(request)
    }

    /** Called by the app when the gate passes; runs a request parked by [submit]. */
    fun onUnlocked() {
        val pending = _state.value.pendingRequest ?: return
        if (_state.value.phase == Phase.NeedsUnlock && gate.canAcceptCommands()) start(pending)
    }

    /** The gate prompt was dismissed or failed: drop the parked request. */
    fun onUnlockCancelled() {
        _state.update {
            if (it.phase == Phase.NeedsUnlock) it.copy(phase = Phase.Idle, pendingRequest = null) else it
        }
    }

    fun stop() {
        stopRequested = true
        _state.value.runs.lastOrNull()?.takeIf { _state.value.canStop }?.let {
            updateRun(it.id) { run -> run.copy(stopRequested = true) }
            appendProgress(it.id, "Stop requested; waiting for current work to end")
        }
        core.stop()
        modelLifecycle?.cancelRun()
    }

    /**
     * Forgets the stored conversation and clears the thread (tasks stay in Activity through the
     * action log). Refused while a turn is active.
     */
    fun clearConversation(): Boolean {
        if (!_state.value.canSubmit) return false
        conversation?.clear()
        _state.update { it.copy(runs = emptyList(), phase = Phase.Idle) }
        return true
    }

    /** Re-reads which engine will take the next run (it depends on Settings). */
    fun refreshEngine() {
        _state.update { it.copy(engine = core.description) }
    }

    private fun start(request: String) {
        stopRequested = false
        modelLifecycle?.beginRun()
        val chat = conversation != null
        val run = RunView(id = UUID.randomUUID().toString(), request = request, startedElapsedMs = elapsedClock(),
            progress = listOf(ProgressLine(0, "Starting request")),
            kind = if (chat) ExchangeKind.Pending else ExchangeKind.Task, route = route().ifEmpty { null })
        _state.update {
            it.copy(phase = Phase.Running, engine = core.description, pendingRequest = null, activity = "starting", runs = it.runs + run)
        }
        scope.launch {
            val listener = object : RunListener {
                override fun onStep(event: StepEvent) {
                    log?.append(LogLine(run.id, clock(), request, core.description, event))
                    updateRun(run.id) { it.copy(steps = it.steps + event, kind = ExchangeKind.Task) }
                    appendProgress(run.id, "Step ${event.step}: ${actionLabel(event.action, redact = true)} → ${event.outcome}")
                }

                override fun onActivity(activity: String) {
                    if (!isChatModelActivity(activity)) updateRun(run.id) { it.copy(kind = ExchangeKind.Task) }
                    appendProgress(run.id, activity)
                    _state.update { s -> if (s.phase == Phase.Running) s.copy(activity = activity) else s }
                }
            }
            var turn: TurnResult? = null
            val (result, error) = try {
                if (chat) {
                    val t = core.converse(conversation!!.load(), request, CoreOptions(maxSteps = maxSteps), listener)
                    turn = t
                    t.report?.let { runCatching { RecordJson.decodeFromJsonElement(RunResult.serializer(), it) }.getOrNull() } to t.error
                } else {
                    core.run(request, maxSteps, listener) to null
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    modelLifecycle?.cancelRun()
                    modelLifecycle?.endRun()
                    throw e
                }
                null to (e.message ?: e::class.simpleName ?: "error")
            }
            modelLifecycle?.endRun()
            turn?.history?.let { history ->
                // Only what core returned goes into the conversation.
                try { conversation?.append(history) } catch (_: Exception) {}
            }
            appendProgress(run.id, when {
                stopRequested -> "Stopped; earlier actions remain"
                error != null -> "Run ended with an error"
                result != null -> resultLabel(result)
                turn != null -> "Reply received"
                else -> "Run ended"
            })
            val finished = elapsedClock()
            updateRun(run.id) {
                it.copy(
                    result = result, error = error, finishedElapsedMs = finished, stopRequested = stopRequested,
                    kind = turn?.let(::exchangeKind) ?: if (error != null && it.kind == ExchangeKind.Pending) ExchangeKind.Error else it.kind,
                    reply = turn?.text?.trim()?.ifEmpty { null },
                    values = turn?.values.orEmpty(),
                    ranAs = turn?.request?.takeIf { r -> r.trim() != request },
                )
            }
            _state.update { it.copy(phase = Phase.Finished, activity = null) }
        }
    }

    private fun appendProgress(id: String, text: String) {
        val now = elapsedClock()
        updateRun(id) { run ->
            if (run.progress.lastOrNull()?.text == text) run else run.copy(
                progress = (run.progress + ProgressLine((now - run.startedElapsedMs).coerceAtLeast(0), text)).takeLast(250),
            )
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

/** The chat model call itself (UniffiCoreEngine's activity lines for the `chat` role). */
internal fun isChatModelActivity(activity: String): Boolean =
    activity == "Waiting for chat model" || activity == "Chat reply received; checking response"

internal fun exchangeKind(turn: TurnResult): ExchangeKind = when (turn.kind) {
    TurnKind.Reply -> ExchangeKind.Reply
    TurnKind.Clarify -> ExchangeKind.Clarify
    TurnKind.Task -> ExchangeKind.Task
    TurnKind.NotUnderstood -> ExchangeKind.NotUnderstood
    TurnKind.Stopped -> ExchangeKind.Stopped
}

/**
 * The thread after a restart, rebuilt from the stored turns: each owner turn with the turn that
 * answered it. A task shows core's history line (request and outcome words), not a report.
 */
fun exchangesFromHistory(history: List<ChatTurn>): List<RunView> {
    val out = mutableListOf<RunView>()
    for ((i, turn) in history.withIndex()) {
        if (turn.source != TurnSource.Owner) continue
        val next = history.getOrNull(i + 1)?.takeIf { it.source != TurnSource.Owner }
        val kind = when (next?.source) {
            TurnSource.TaskResult -> ExchangeKind.Task
            TurnSource.Assistant -> ExchangeKind.Reply
            else -> ExchangeKind.Stopped
        }
        val reply = when (next?.source) {
            TurnSource.TaskResult -> taskLineOutcome(next.text)
            TurnSource.Assistant -> next.text
            else -> null
        }
        out += RunView(id = "restored-$i", request = turn.text, kind = kind, reply = reply,
            values = next?.values.orEmpty(), restored = true, finishedElapsedMs = 0)
    }
    return out
}

/** Core's task line is `Task: <request> → <outcome>`; the outcome words, or the line itself. */
internal fun taskLineOutcome(line: String): String {
    val outcome = line.substringAfterLast(" → ", "").trim()
    return if (outcome.isEmpty()) line else outcome.replaceFirstChar { it.uppercase() }
}
