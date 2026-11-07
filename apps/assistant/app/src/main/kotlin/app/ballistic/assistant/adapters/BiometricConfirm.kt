// Confirm port: the confirmation window, raised through ConfirmBroker.
//
// Can: show the exact request, require a strong biometric bound to a per-operation key for hard
// requests, raise a soft request to hard (never the reverse) using the owner's policy and the
// code-constant list (ConfirmFloor), refuse outright for apps set to "never automate".
// Cannot yet: return the signature to core (the port returns approved/denied only), or show the
// window over another app while Ballistic is in the background (Android's background activity
// start rule; the broker's notification opens it instead).

package app.ballistic.assistant.adapters

import app.ballistic.assistant.confirm.ConfirmBroker
import app.ballistic.assistant.core.Confirm
import app.ballistic.assistant.core.ConfirmFloor
import app.ballistic.assistant.core.ConfirmRequest
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.PolicyStore

class BiometricConfirm(private val broker: ConfirmBroker, private val policy: PolicyStore) : Confirm {
    override fun ask(request: ConfirmRequest): ConfirmResult {
        val required = ConfirmFloor.required(request, policy.load().forApp(request.app))
        return broker.ask(request, required).result
    }
}
