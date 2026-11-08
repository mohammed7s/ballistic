// The real engine: libballistic_core (Rust) through its UniFFI binding (core/src/ffi.rs). Core
// runs the loop on the calling thread and calls back into [HostPorts] for every observation,
// action, model call, confirmation and step event. Every value crosses as the serde JSON of
// core/src/types.rs, decoded here with CoreJson into the classes in Types.kt, so serde's shapes
// stay the single contract for the harness and the app.
//
// The generated binding is ffi/ballistic_core.kt (apps/assistant/scripts/build_core.sh); it
// loads the native library through JNA on first use. [load] says whether it is present.

package app.ballistic.assistant.core

import app.ballistic.assistant.core.ffi.HostException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.ballistic.assistant.core.ffi.HostPorts as FfiHostPorts
import app.ballistic.assistant.core.ffi.converse as ffiConverse
import app.ballistic.assistant.core.ffi.coreVersion as ffiCoreVersion
import app.ballistic.assistant.core.ffi.run as ffiRun

/** Core reported a port failure the loop does not handle itself; the run stopped there. */
class CoreRunException(message: String) : Exception(message)

class UniffiCoreEngine private constructor(val version: String) : CoreEngine {
    override val description: String = "Ballistic core $version (Rust, in-process)"

    override fun run(request: String, maxSteps: Int, ports: HostPorts, listener: RunListener, cancel: CancelToken): RunResult {
        val out = ffiRun(request, maxSteps.toUInt(), Callbacks(ports, listener, cancel))
        val final = RecordJson.parseToJsonElement(out) as? JsonObject
            ?: throw CoreRunException("core returned something that is not a JSON object")
        final["report"]?.let { return RecordJson.decodeFromJsonElement(RunResult.serializer(), it) }
        throw CoreRunException((final["error"] as? JsonPrimitive)?.content ?: "core returned neither a report nor an error")
    }

    override fun converse(
        history: List<ChatTurn>, message: String, options: CoreOptions,
        ports: HostPorts, listener: RunListener, cancel: CancelToken,
    ): TurnResult {
        val out = ffiConverse(
            CoreJson.encodeToString(ListSerializer(ChatTurn.serializer()), history), message,
            CoreJson.encodeToString(CoreOptions.serializer(), options), Callbacks(ports, listener, cancel),
        )
        val final = RecordJson.parseToJsonElement(out) as? JsonObject
            ?: throw CoreRunException("core returned something that is not a JSON object")
        final["turn"]?.let { return RecordJson.decodeFromJsonElement(TurnResult.serializer(), it) }
        throw CoreRunException((final["error"] as? JsonPrimitive)?.content ?: "core returned neither a turn nor an error")
    }

    /**
     * Core's side of the ports. Never throws anything but [HostException] (UniFFI turns any
     * other throwable into a Host error, but the mapping here keeps Disconnected and Malformed
     * distinct, which core treats differently after a dispatch).
     */
    private class Callbacks(
        private val ports: HostPorts,
        private val listener: RunListener,
        private val cancel: CancelToken,
    ) : FfiHostPorts {
        override fun observe(): String = port {
            listener.onActivity("Reading available screen information")
            CoreJson.encodeToString(ScreenState.serializer(), ports.screen.observe())
        }

        override fun act(opJson: String): String = port {
            val op = CoreJson.decodeFromString(HandsOp.serializer(), opJson)
            listener.onActivity("Dispatching an action")
            CoreJson.encodeToString(HandsResult.serializer(), ports.hands.act(op))
        }

        override fun complete(role: String, messagesJson: String, maxTokens: UInt): String = port {
            val modelRole = ModelRole.entries.firstOrNull { it.name.equals(role, ignoreCase = true) }
                ?: throw PortException.Malformed("unknown model role $role")
            val messages = CoreJson.decodeFromString(ListSerializer(ChatMessage.serializer()), messagesJson)
            listener.onActivity("Waiting for ${modelRole.name.lowercase()} model")
            val completion = ports.models.complete(modelRole, messages, maxTokens.toInt())
            listener.onActivity("${modelRole.name} reply received; checking response")
            CoreJson.encodeToString(Completion.serializer(), completion)
        }

        override fun confirm(requestJson: String): String = port {
            val confirmRequest = CoreJson.decodeFromString(ConfirmRequest.serializer(), requestJson)
            listener.onActivity("awaiting approval")
            CoreJson.encodeToString(ConfirmResult.serializer(), ports.confirm.ask(confirmRequest))
        }

        override fun log(eventJson: String) {
            // A record for people: a line the shell cannot parse is dropped, never fatal.
            try {
                listener.onStep(RecordJson.decodeFromString(StepEvent.serializer(), eventJson))
            } catch (e: Exception) {
                listener.onActivity("unreadable step event from core")
            }
        }

        override fun isCancelled(): Boolean = cancel.isCancelled

        private inline fun port(call: () -> String): String = try {
            call()
        } catch (e: PortException) {
            throw when (e) {
                is PortException.Disconnected -> HostException.Disconnected(e.reason)
                is PortException.Malformed -> HostException.Malformed(e.reason)
                is PortException.Host -> HostException.Host(e.reason)
            }
        } catch (e: SerializationException) {
            throw HostException.Malformed(e.message ?: "JSON does not match types.rs")
        } catch (e: IllegalArgumentException) {
            throw HostException.Malformed(e.message ?: "JSON does not match types.rs")
        } catch (e: InterruptedException) {
            throw HostException.Disconnected("interrupted")
        } catch (e: RuntimeException) {
            throw HostException.Host("${e::class.simpleName}: ${e.message.orEmpty().take(200)}")
        }
    }

    companion object {
        /**
         * The engine, or null with the reason if libballistic_core cannot be loaded (not in
         * this APK, or built from a different core than the generated binding).
         */
        fun load(): Result<UniffiCoreEngine> = try {
            Result.success(UniffiCoreEngine(ffiCoreVersion()))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}

/**
 * Chooses per run: the real core when a model is configured and the native library loaded,
 * otherwise the scripted stand-in. [description] says which and why, so a scripted run is never
 * taken for an agent run.
 */
class EngineChoice(
    private val real: Result<UniffiCoreEngine>,
    private val fake: CoreEngine,
    private val modelConfigured: () -> Boolean,
) : CoreEngine {
    val current: CoreEngine
        get() = real.getOrNull()?.takeIf { modelConfigured() } ?: fake

    override val description: String
        get() {
            val engine = real.getOrNull()
            return when {
                engine == null -> "${fake.description}: libballistic_core is not in this build"
                !modelConfigured() -> "${fake.description}: no model configured (Settings)"
                else -> "${engine.description}, planning with the configured model"
            }
        }

    override fun run(request: String, maxSteps: Int, ports: HostPorts, listener: RunListener, cancel: CancelToken): RunResult =
        current.run(request, maxSteps, ports, listener, cancel)

    override fun converse(
        history: List<ChatTurn>, message: String, options: CoreOptions,
        ports: HostPorts, listener: RunListener, cancel: CancelToken,
    ): TurnResult = current.converse(history, message, options, ports, listener, cancel)
}
