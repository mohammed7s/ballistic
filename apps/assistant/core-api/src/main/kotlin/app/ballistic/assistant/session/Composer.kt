// What Ask's composer offers, from the session, gate and local-model state. One function so the
// field, the button and the line under it can never disagree: whenever Send cannot send, the
// composer says why in words next to the button (a greyed button alone reads as "broken").
//
// An installed but unloaded on-phone model is the normal state after the app process starts
// (every update or restart): Send loads it first, then sends. Loading is the same LocalAiController
// job as Local AI's Load button; nothing else is tried if it fails.

package app.ballistic.assistant.session

/** What tapping the composer's button does. */
enum class ComposerAction {
    /** Submit the text now. */
    Send,
    /** Load the selected on-phone model, then submit the text if loading succeeds. */
    LoadThenSend,
    /** Ask for the phone unlock / re-enable requests (no text to send yet). */
    Unlock,
    /** Open Local AI: there is no model on this phone to load. */
    SetUpAi,
    /** The button is shown disabled; [ComposerView.reason] says why. */
    None,
}

data class ComposerInputs(
    val phase: Phase,
    val unlocked: Boolean,
    /** "On this phone" is selected as where AI runs. */
    val localSelected: Boolean,
    /** Ballistic's Rust core is in this build (not only the demo engine). */
    val realEngine: Boolean,
    /** A model file for the active selection is on this phone. */
    val modelInstalled: Boolean,
    /** The active model is loaded and ready. */
    val modelLoaded: Boolean,
    /** The local engine is loading or generating (a speed check, or a turn). */
    val engineBusy: Boolean,
    /** A Local AI job (download, import, load, check) is running. */
    val localJobBusy: Boolean,
    val textBlank: Boolean,
    /** A load is queued to send this text when it finishes. */
    val sendQueued: Boolean = false,
    /** The last load's error, if the model is not loaded because loading failed. */
    val loadError: String? = null,
)

data class ComposerView(
    val fieldEnabled: Boolean,
    val button: String,
    val buttonEnabled: Boolean,
    val action: ComposerAction,
    val placeholder: String,
    /** One short line under the composer; null when Send simply works. */
    val reason: String?,
)

fun composerView(i: ComposerInputs): ComposerView {
    val active = i.phase == Phase.Running || i.phase == Phase.AwaitingApproval
    fun view(field: Boolean, button: String, action: ComposerAction, placeholder: String, reason: String?) =
        ComposerView(field, button, action != ComposerAction.None, action, placeholder, reason)
    return when {
        active -> view(false, "Send", ComposerAction.None, "Task running. Stop it to send another.", null)
        i.sendQueued -> view(false, "Loading…", ComposerAction.None, "Message Ballistic",
            "Loading the model. Your message is sent when it is ready.")
        // Locked: typing is allowed; Send parks the message and asks for the unlock.
        !i.unlocked && i.textBlank -> view(true, "Enable", ComposerAction.Unlock, "Requests are locked",
            "Requests are locked. Enable them to send.")
        i.localSelected && !i.realEngine -> view(false, "Send", ComposerAction.None, "Not available in this build",
            "Ballistic's core isn't in this build.")
        i.localSelected && !i.modelInstalled -> view(false, "Set up AI", ComposerAction.SetUpAi, "Set up AI to start",
            "No model on this phone yet.")
        i.localJobBusy || i.engineBusy -> view(true, "Send", ComposerAction.None, "Message Ballistic",
            if (i.localSelected && !i.modelLoaded) "Loading the model…" else "Local AI is busy. Wait for it to finish.")
        i.localSelected && !i.modelLoaded -> {
            val reason = i.loadError?.let { "Model didn't load: $it" } ?: "Model not loaded. Send loads it first."
            view(true, "Send", if (i.textBlank) ComposerAction.None else ComposerAction.LoadThenSend, "Message Ballistic", reason)
        }
        // Locked with text: Send parks the message and the unlock prompt appears.
        else -> view(true, "Send", if (i.textBlank) ComposerAction.None else ComposerAction.Send, "Message Ballistic", null)
    }
}
