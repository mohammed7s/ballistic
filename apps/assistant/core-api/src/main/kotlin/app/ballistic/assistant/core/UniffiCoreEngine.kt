// Placeholder for the real engine. No native code exists yet.

package app.ballistic.assistant.core

/**
 * Bound to libballistic_core via UniFFI in a later pass. Planned shape (not yet agreed in core):
 * core exports `run(request, max_steps, host, cancel)` where `host` is a UniFFI callback
 * interface whose four methods take and return the JSON strings the JSON-lines bridge already
 * carries (core/src/bridge.rs), decoded here with [CoreJson] into the classes in Types.kt. That
 * keeps serde's shapes the single contract for the harness and the app.
 *
 * Prerequisite in core (docs/ANDROID_APP.md, integration step 1): `agent::run` takes the
 * concrete stdio `Bridge` today and must take the port traits, emit run events and honour
 * cancellation before this class can do anything.
 */
class UniffiCoreEngine : CoreEngine {
    override val description: String = "ballistic_core (not bound yet)"

    override fun run(request: String, maxSteps: Int, ports: HostPorts, listener: RunListener, cancel: CancelToken): RunResult =
        throw UnsupportedOperationException("libballistic_core is not bound yet; use FakeCoreEngine")
}
