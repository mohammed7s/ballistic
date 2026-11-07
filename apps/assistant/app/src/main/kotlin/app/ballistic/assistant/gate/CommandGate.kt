// The command gate (SPEC.md §5.3, §7.8): no request is accepted, typed or spoken, until the
// session has passed a strong biometric. It answers "who may command"; per-operation approval
// (the confirmation window) is a separate check and is never satisfied by this one.
//
// Session: from a successful prompt until Ballistic has no visible activity (process ON_STOP)
// or the screen turns off. Locking does not stop a run in progress; it stops new requests.
//
// This prompt carries no CryptoObject: its result is a callback inside our process, which is
// enough to tell the owner's finger from a stranger's voice. A passkey alternative (SPEC §7.8)
// and voice entry (wake word, push-to-talk) are not built yet.
//
// Debug builds only: with no strong biometric available (the AndroidWorld emulator has none
// enrolled), DebugTools offers a test PIN for this gate. Release builds have no fallback, and
// no build uses it for operation approval.

package app.ballistic.assistant.gate

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.ballistic.assistant.debug.DebugTools
import app.ballistic.assistant.session.CommandGateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CommandGate(app: Application) : CommandGateState {
    private val _unlocked = MutableStateFlow(false)
    override val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) lock() },
        )
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = lock()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun lock() {
        _unlocked.value = false
    }

    /** Why the gate cannot be opened on this device, or null if it can. */
    fun unavailableReason(context: Context): String? =
        when (context.getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No strong biometric is enrolled."
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "No strong biometric hardware is available."
            else -> "Strong biometric authentication is unavailable."
        }

    /** Shows the system biometric prompt; [onResult] gets null on success or the failure text. */
    fun authenticate(activity: Activity, onResult: (String?) -> Unit) {
        unavailableReason(activity)?.let { why ->
            val shown = DebugTools.gateFallback(activity, why) { ok ->
                if (ok) _unlocked.value = true
                onResult(if (ok) null else "Not unlocked")
            }
            if (!shown) onResult(why)
            return
        }
        val executor = activity.mainExecutor
        BiometricPrompt.Builder(activity)
            .setTitle("Unlock Ballistic")
            .setSubtitle("Ballistic takes requests only after this check")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton("Cancel", executor) { _, _ -> onResult("Cancelled") }
            .build()
            .authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        _unlocked.value = true
                        onResult(null)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onResult(errString.toString())
                    }
                },
            )
    }
}
