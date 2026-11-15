package app.ballistic.assistant.core

/** Restrictions on the operations this Android host actually implements. */
object AppAccess {
    fun canOpen(policy: AppPolicy): Boolean = policy.act != Act.None && policy.confirm != ConfirmMode.NeverAutomate

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
        val before = policy().forApp(target)
        if (!AppAccess.canOpen(before)) return HandsResult.NotExecuted("app access is blocked in Ballistic settings")
        if (before.confirm == ConfirmMode.Always) {
            val verb = if (op is HandsOp.Intent) op.intent.operationId else "open"
            val approval = confirm.ask(ConfirmRequest(op, target, verb, target, hard = true,
                reason = "Owner requires approval for every action in this app"))
            if (approval != ConfirmResult.Approved) return HandsResult.NotExecuted("owner did not approve")
        }
        val after = policy().forApp(target)
        if (!canDispatch() || after != before || !AppAccess.canOpen(after))
            return HandsResult.NotExecuted("task stopped or app access changed")
        return delegate.act(op)
    }
}
