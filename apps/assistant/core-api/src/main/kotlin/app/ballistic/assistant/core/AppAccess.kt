package app.ballistic.assistant.core

/** Restrictions on the operations this Android host actually implements. */
object AppAccess {
    fun canOpen(policy: AppPolicy): Boolean = policy.act != Act.None && policy.confirm != ConfirmMode.NeverAutomate

    /**
     * A typed operation in its handler app. Opening a page, the camera, a link or a filled-in
     * dialer is navigation; a timer or alarm (created on start) or a pre-filled editor or compose
     * screen acts in the app, so it needs the owner's full-action grant for that app.
     */
    fun canRun(policy: AppPolicy, op: IntentOp): Boolean = canOpen(policy) && when (op) {
        is IntentOp.OpenSettings, is IntentOp.OpenCamera, is IntentOp.ViewUri, is IntentOp.Dial -> true
        is IntentOp.SetTimer, is IntentOp.SetAlarm, is IntentOp.CreateEvent, is IntentOp.ComposeSms, is IntentOp.AddContact ->
            policy.act == Act.Full
    }

    fun canReadNotifications(policy: AppPolicy, local: Boolean): Boolean =
        policy.observe != Observe.None && (local || policy.route != Route.DeviceOnly)
}

/** Re-check at dispatch as well as advertising grants to core. Other operations fail closed. */
class PolicyHands(
    private val policy: () -> PolicyFile,
    private val delegate: Hands,
    private val confirm: Confirm,
    private val canDispatch: () -> Boolean = { true },
) : Hands {
    override fun act(op: HandsOp): HandsResult {
        if (!canDispatch()) return HandsResult.NotExecuted("task stopped")
        if (op is HandsOp.Wait) return delegate.act(op)
        val target = when (op) {
            is HandsOp.OpenApp -> op.`package`
            // A typed operation acts in its handler app, under that app's policy.
            is HandsOp.Intent -> op.`package`
            else -> return HandsResult.NotExecuted("operation not supported by this host")
        }
        val allowed = { p: AppPolicy -> if (op is HandsOp.Intent) AppAccess.canRun(p, op.intent) else AppAccess.canOpen(p) }
        val before = policy().forApp(target)
        if (!allowed(before)) return HandsResult.NotExecuted("app access is blocked in Ballistic settings")
        if (before.confirm == ConfirmMode.Always) {
            val verb = if (op is HandsOp.Intent) op.intent.operationId else "open"
            val approval = confirm.ask(ConfirmRequest(op, target, verb, target, hard = true,
                reason = "Owner requires approval for every action in this app"))
            if (approval != ConfirmResult.Approved) return HandsResult.NotExecuted("owner did not approve")
        }
        val after = policy().forApp(target)
        if (!canDispatch() || after != before || !allowed(after))
            return HandsResult.NotExecuted("task stopped or app access changed")
        return delegate.act(op)
    }
}
