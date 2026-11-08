// The four doors into core (core/README.md §4), the Kotlin side of core/src/ports.rs. The app
// implements these; core calls them from its own thread and blocks on each call, so every method
// here is synchronous and may be called off the main thread. Errors are thrown as PortException,
// which core maps to its PortError.

package app.ballistic.assistant.core

/** Mirrors Rust `PortError`. [reason] is the text without the kind prefix, as core receives it. */
sealed class PortException(val reason: String, kind: String) : Exception("$kind: $reason") {
    class Disconnected(reason: String) : PortException(reason, "host disconnected")
    class Malformed(reason: String) : PortException(reason, "host returned malformed data")
    class Host(reason: String) : PortException(reason, "host error")
}

/** Eyes. Returns the current screen; retries an empty tree inside the port. */
fun interface Screen {
    @Throws(PortException::class)
    fun observe(): ScreenState
}

/** Hands. Executes exactly what it is told, in screen coordinates. Never decides anything. */
fun interface Hands {
    @Throws(PortException::class)
    fun act(op: HandsOp): HandsResult
}

/**
 * One completion per call. The host routes [role] to a backend. Before every call core asks
 * [limits] for that role's context (core/src/context.rs); the default advertises none.
 */
fun interface Models {
    @Throws(PortException::class)
    fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion

    @Throws(PortException::class)
    fun limits(role: ModelRole): ModelLimits = ModelLimits()

    /** The exact prompt size from the routed model's tokenizer; null lets core estimate. */
    @Throws(PortException::class)
    fun countTokens(role: ModelRole, messages: List<ChatMessage>): Int? = null
}

/** Asks the owner. The app shows the confirmation window (SPEC.md §7.1). */
fun interface Confirm {
    @Throws(PortException::class)
    fun ask(request: ConfirmRequest): ConfirmResult
}

/** What a host hands core for one run. */
data class HostPorts(val screen: Screen, val hands: Hands, val models: Models, val confirm: Confirm)
