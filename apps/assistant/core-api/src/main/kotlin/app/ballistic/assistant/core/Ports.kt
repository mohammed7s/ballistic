// The four doors into core (core/README.md §4), the Kotlin side of core/src/ports.rs. The app
// implements these; core calls them from its own thread and blocks on each call, so every method
// here is synchronous and may be called off the main thread. Errors are thrown as PortException,
// which core maps to its PortError.

package app.ballistic.assistant.core

/** Mirrors Rust `PortError`. */
sealed class PortException(message: String) : Exception(message) {
    class Disconnected(message: String) : PortException("host disconnected: $message")
    class Malformed(message: String) : PortException("host returned malformed data: $message")
    class Host(message: String) : PortException("host error: $message")
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

/** One completion per call. The host routes [role] to a backend. */
fun interface Models {
    @Throws(PortException::class)
    fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion
}

/** Asks the owner. The app shows the confirmation window (SPEC.md §7.1). */
fun interface Confirm {
    @Throws(PortException::class)
    fun ask(request: ConfirmRequest): ConfirmResult
}

/** What a host hands core for one run. */
data class HostPorts(val screen: Screen, val hands: Hands, val models: Models, val confirm: Confirm)
