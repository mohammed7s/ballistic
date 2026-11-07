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
import app.ballistic.assistant.core.CoreEngine
import app.ballistic.assistant.core.FakeCoreEngine
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

    val gate = CommandGate(app)
    val broker = ConfirmBroker(app)

    /**
     * The engine. Scripted until libballistic_core is bound (UniffiCoreEngine); the UI names it
     * on every screen so a scripted run is never taken for an agent run.
     */
    val engine: CoreEngine = FakeCoreEngine.demo()

    val hands = IntentHands(app)

    val ports = HostPorts(
        screen = NotificationScreen(app, installedApps = { InstalledApps.list(app) }, capabilities = ::capabilities),
        hands = hands,
        models = ModelsHttp(config = modelsConfig::load, apiKey = { secrets.get(SecretStore.MODEL_API_KEY) }),
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

val android.content.Context.graph: AppGraph get() = (applicationContext as BallisticApp).graph
