// Process-wide objects. The prompt session lives here, not in an Activity, so a run keeps going
// when the owner leaves the app to look at another screen, and rotation reattaches to it.

package app.ballistic.assistant

import android.app.Application
import app.ballistic.assistant.adapters.BiometricConfirm
import app.ballistic.assistant.adapters.InstalledApps
import app.ballistic.assistant.adapters.IntentHands
import app.ballistic.assistant.adapters.NotificationScreen
import app.ballistic.assistant.confirm.ConfirmBroker
import app.ballistic.assistant.core.Act
import app.ballistic.assistant.core.ActionLog
import app.ballistic.assistant.core.ConfirmMode
import app.ballistic.assistant.core.ChatMessage
import app.ballistic.assistant.core.Completion
import app.ballistic.assistant.core.EngineChoice
import app.ballistic.assistant.core.FakeCoreEngine
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
import app.ballistic.assistant.models.ModelsHttp
import app.ballistic.assistant.session.PromptSession
import app.ballistic.assistant.storage.ModelsConfigStore
import app.ballistic.assistant.storage.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** App-private files (core/README.md §15: policy, logs; plus the shell's model config). */
    val policyStore = PolicyStore(app.filesDir.resolve("policy.json"))
    val actionLog = ActionLog(app.filesDir.resolve("logs/actions.jsonl"))
    val modelsConfig = ModelsConfigStore(app.filesDir.resolve("models.json"))
    val secrets = SecretStore(app)

    /** Debug builds: files dropped by adb for an emulator test (DebugTools); release: nothing. */
    var importNotes: List<String> = DebugTools.importFiles(app.filesDir, secrets, modelsConfig)
        private set

    fun importDebugFiles(): List<String> = DebugTools.importFiles(app.filesDir, secrets, modelsConfig).also { importNotes = it }

    val gate = CommandGate(app)
    val broker = ConfirmBroker(app)

    /**
     * The engine, chosen per run: the Rust core (libballistic_core via UniFFI) when it is in the
     * APK and a model is configured, else the scripted stand-in. The UI shows which, and why.
     */
    val engine = EngineChoice(
        real = UniffiCoreEngine.load(),
        fake = FakeCoreEngine.demo(),
        modelConfigured = { modelsConfig.load() != null },
    )

    val hands = IntentHands(app)

    val ports = HostPorts(
        screen = NotificationScreen(app, installedApps = { InstalledApps.list(app) }, capabilities = ::capabilities),
        hands = hands,
        models = CountedModels(ModelsHttp(config = modelsConfig::load, apiKey = { secrets.get(SecretStore.MODEL_API_KEY) })),
        confirm = BiometricConfirm(broker, policyStore),
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
            p.act != Act.None && p.confirm != ConfirmMode.NeverAutomate
        }
        return HostCapabilities(operations = IntentHands.OPERATIONS, appGrants = granted)
    }

    val session = PromptSession(
        scope = scope,
        gate = gate,
        ports = ports,
        corePorts = { RustCore(engine, it) },
        log = actionLog,
    )

    /** Stop: no new model calls or dispatches, and any approval not yet given is denied. */
    fun stop() {
        session.stop()
        broker.denyPending("stopped by owner")
    }
}

/**
 * Counts model calls and writes one logcat line per call: role, outcome, tokens, seconds. Never
 * the messages, the reply or the key.
 */
class CountedModels(private val inner: Models) : Models {
    private val calls = AtomicInteger(0)

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
