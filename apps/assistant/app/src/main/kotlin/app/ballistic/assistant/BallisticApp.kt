// Process-wide objects. The prompt session lives here, not in an Activity, so a run keeps going
// when the owner leaves the app to look at another screen, and rotation reattaches to it.

package app.ballistic.assistant

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import app.ballistic.assistant.adapters.BiometricConfirm
import app.ballistic.assistant.adapters.InstalledApps
import app.ballistic.assistant.adapters.IntentHands
import app.ballistic.assistant.adapters.NotificationScreen
import app.ballistic.assistant.confirm.ConfirmBroker
import app.ballistic.assistant.core.AppAccess
import app.ballistic.assistant.core.PolicyHands
import app.ballistic.assistant.core.Act
import app.ballistic.assistant.core.ActionLog
import app.ballistic.assistant.core.ConfirmMode
import app.ballistic.assistant.core.ChatMessage
import app.ballistic.assistant.core.Completion
import app.ballistic.assistant.core.EngineChoice
import app.ballistic.assistant.core.FakeCoreEngine
import app.ballistic.assistant.core.ModelLimits
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.Models
import app.ballistic.assistant.core.PortException
import app.ballistic.assistant.core.UniffiCoreEngine
import app.ballistic.assistant.debug.DebugTools
import app.ballistic.assistant.core.HostCapabilities
import app.ballistic.assistant.core.HostPorts
import app.ballistic.assistant.core.PolicyStore
import app.ballistic.assistant.core.RustCore
import app.ballistic.assistant.gate.CommandGate
import app.ballistic.assistant.models.*
import app.ballistic.assistant.session.ConversationStore
import app.ballistic.assistant.session.PromptSession
import app.ballistic.assistant.storage.ModelsConfigStore
import app.ballistic.assistant.storage.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicInteger

class BallisticApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Wiring only: which adapter fills which port. Nothing here decides an action. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val backgroundNotice = MutableStateFlow<String?>(
        if (app.getSharedPreferences("work_lifecycle", 0).getBoolean("active", false))
            "Previous work was interrupted when the app process stopped. It has not been resumed." else null,
    )

    /** App-private files (core/README.md §15: policy, logs; plus the shell's model config). */
    val policyStore = PolicyStore(app.filesDir.resolve("policy.json"))
    val actionLog = ActionLog(app.filesDir.resolve("logs/actions.jsonl"))
    val modelsConfig = ModelsConfigStore(app.filesDir.resolve("models.json"))
    val secrets = SecretStore(app)
    /** Debug builds: files dropped by adb for an emulator test (DebugTools); release: nothing. */
    var importNotes: List<String> = DebugTools.importFiles(app.filesDir, secrets, modelsConfig)
        private set

    fun importDebugFiles(): List<String> = DebugTools.importFiles(app.filesDir, secrets, modelsConfig).also { importNotes = it }


    val modelSelection = ModelSelectionStore(app.filesDir.resolve("model-selection.json")) { modelsConfig.load() != null }
    /** One engine per runtime; only one model is loaded at a time (LocalModels unloads both). */
    val localModels = LocalModels(mapOf(ModelRuntime.LlamaCpp to LlamaDriver(), ModelRuntime.LiteRtLm to LiteRtLmDriver(app)))
    val pendingModelImport = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
    val localAi: LocalAiController = LocalAiController(app, scope, localModels, modelSelection) {
        gate.canAcceptCommands() && session.state.value.canSubmit && session.state.value.pendingRequest == null
    }
    val routedModels = RoutedModels(
        selection = { localAi.selection.value.backend },
        http = ModelsHttp(config = modelsConfig::load, apiKey = { secrets.get(SecretStore.MODEL_API_KEY) }),
        local = localModels,
    )

    val gate = CommandGate(app)
    val broker = ConfirmBroker(app)

    /**
     * The engine, chosen per run: the Rust core (libballistic_core via UniFFI) when it is in the
     * APK and a model is configured, else the scripted stand-in. The UI shows which, and why.
     */
    val engine = EngineChoice(
        real = UniffiCoreEngine.load(),
        fake = FakeCoreEngine.demo(),
        modelConfigured = { localAi.selection.value.backend == ModelBackend.Local || modelsConfig.load() != null },
    )

    private val operationConfirm = BiometricConfirm(broker, policyStore)
    val hands = PolicyHands(policyStore::load, IntentHands(app), operationConfirm) {
        session.state.value.canStop && session.state.value.runs.lastOrNull()?.stopRequested != true
    }

    val ports = HostPorts(
        screen = NotificationScreen(app, installedApps = {
            val policy = policyStore.load()
            InstalledApps.list(app).filter { AppAccess.canOpen(policy.forApp(it.`package`)) }
        }, capabilities = ::capabilities, mayRead = { pkg ->
            AppAccess.canReadNotifications(policyStore.load().forApp(pkg), localAi.selection.value.backend == ModelBackend.Local)
        }, needsNotificationAccess = {
            policyStore.load().apps.values.any { AppAccess.canReadNotifications(it, localAi.selection.value.backend == ModelBackend.Local) }
        }),
        hands = hands,
        models = CountedModels(routedModels),
        confirm = operationConfirm,
    )

    /**
     * What this host can execute now, and in which apps (SPEC.md §5.5). Operations are only the
     * ones IntentHands performs unprivileged; grants are installed apps the owner's policy lets
     * the assistant act in.
     */
    fun capabilities(): HostCapabilities {
        val policy = policyStore.load()
        val granted = InstalledApps.list(app).map { it.`package` }.filter {
            val p = policy.forApp(it)
            AppAccess.canOpen(p)
        }
        return HostCapabilities(operations = IntentHands.OPERATIONS, appGrants = granted)
    }

    /**
     * First run is shown once. An install that already has a model, a connected server, app
     * rules or a conversation counts as set up, so an update never puts an owner through it.
     */
    fun firstRunDone(): Boolean {
        val prefs = app.getSharedPreferences("first_run", 0)
        if (prefs.getBoolean("done", false)) return true
        val existing = localAi.installed.value.isNotEmpty() || modelsConfig.load() != null ||
            policyStore.load().apps.isNotEmpty() || app.filesDir.resolve("conversation.json").exists() ||
            app.filesDir.resolve("logs/actions.jsonl").exists()
        if (existing) markFirstRunDone()
        return existing
    }

    fun markFirstRunDone() {
        app.getSharedPreferences("first_run", 0).edit().putBoolean("done", true).apply()
    }

    /** Ask's conversation: the turns core returned, last 12, app-private (excluded from backup). */
    val conversation = ConversationStore(app.filesDir.resolve("conversation.json"))

    /** True when model requests leave the phone (connected route to a non-local host). */
    fun leavesPhone(): Boolean {
        if (localAi.selection.value.backend == ModelBackend.Local) return false
        val cfg = modelsConfig.load() ?: return false
        return ModelRole.entries.any { cfg.endpoint(it).leavesDevice() }
    }

    /** Where the next request's model calls go, in words; recorded on each Ask message. */
    fun routeLabel(): String {
        if (localAi.selection.value.backend == ModelBackend.Local) return "on this phone"
        val cfg = modelsConfig.load() ?: return "connected server (not set up)"
        val hosts = ModelRole.entries.map { cfg.endpoint(it) }.filter { it.leavesDevice() }
            .mapNotNull { runCatching { java.net.URI(it.baseUrl).host }.getOrNull() }.distinct()
        return if (hosts.isEmpty()) "connected server on this phone" else "left the phone: ${hosts.joinToString()}"
    }

    val session: PromptSession = PromptSession(
        scope = scope,
        gate = gate,
        ports = ports,
        corePorts = { RustCore(engine, it) },
        log = actionLog,
        elapsedClock = android.os.SystemClock::elapsedRealtime,
        conversation = conversation,
        route = ::routeLabel,
    )

    init {
        scope.launch {
            combine(session.state, localAi.busy) { run, local -> run.canStop || local }
                .distinctUntilChanged().collect { active ->
                    if (active) {
                        backgroundNotice.value = null
                        try {
                            ContextCompat.startForegroundService(app, Intent(app, WorkService::class.java))
                        } catch (_: Exception) {
                            backgroundNotice.value = "Could not start background execution. The task was stopped; return to Ballistic and retry."
                            stop()
                        }
                    }
                }
        }
    }

    /** Stop: no new model calls or dispatches, and any approval not yet given is denied. */
    fun stop() {
        session.stop()
        localAi.stop()
        broker.denyPending("stopped by owner")
    }
}

/**
 * Counts model calls and writes one logcat line per call: role, outcome, tokens, seconds. Never
 * the messages, the reply or the key.
 */
class CountedModels(private val inner: Models) : Models, ModelRunLifecycle {
    override fun beginRun() { (inner as? ModelRunLifecycle)?.beginRun() }
    override fun endRun() { (inner as? ModelRunLifecycle)?.endRun() }
    override fun cancelRun() { (inner as? ModelRunLifecycle)?.cancelRun() }
    private val calls = AtomicInteger(0)

    // Forwarded so core's context guard sees the routed model's limit (the defaults advertise none).
    override fun limits(role: ModelRole): ModelLimits = inner.limits(role)
    override fun countTokens(role: ModelRole, messages: List<ChatMessage>): Int? = inner.countTokens(role, messages)

    override fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion {
        val n = calls.incrementAndGet()
        val started = System.nanoTime()
        fun seconds() = "%.1f".format((System.nanoTime() - started) / 1e9)
        try {
            val completion = inner.complete(role, messages, maxTokens)
            android.util.Log.i(TAG, "model call $n role=${role.name.lowercase()} ok prompt_tokens=${completion.usage.promptTokens} completion_tokens=${completion.usage.completionTokens} seconds=${seconds()}")
            return completion
        } catch (e: PortException) {
            android.util.Log.i(TAG, "model call $n role=${role.name.lowercase()} failed (${e::class.simpleName}) seconds=${seconds()}")
            throw e
        }
    }

    companion object {
        const val TAG = "Ballistic"
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as BallisticApp).graph
