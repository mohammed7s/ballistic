// Commands use the phone's secure unlock state. Manual app locking is separate from
// device locking; neither grants approval for a sensitive operation.
package app.ballistic.assistant.gate

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.ballistic.assistant.debug.DebugTools
import app.ballistic.assistant.session.CommandGateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CommandGate(private val app: Application) : CommandGateState {
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val preferences = app.getSharedPreferences("command_gate", Context.MODE_PRIVATE)
    private var manuallyLocked = preferences.getBoolean("manually_locked", false)
    private var debugAuthenticated = false
    private val _unlocked = MutableStateFlow(false)
    override val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshWhileVisible = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) handler.post(refreshWhileVisible)
                if (event == Lifecycle.Event.ON_STOP) handler.removeCallbacks(refreshWhileVisible)
            },
        )
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    debugAuthenticated = false
                    _unlocked.value = false
                } else refresh()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Read platform state at command admission, not just a possibly stale UI flow. */
    override fun canAcceptCommands(): Boolean = refresh()

    fun refresh(): Boolean {
        val ready = !manuallyLocked && power.isInteractive && !keyguard.isKeyguardLocked &&
            !keyguard.isDeviceLocked && (keyguard.isDeviceSecure || debugAuthenticated)
        _unlocked.value = ready
        return ready
    }

    /** Explicit manual lock persists across activity/process recreation. Existing work is not approval. */
    fun lock() {
        manuallyLocked = true
        preferences.edit().putBoolean("manually_locked", true).apply()
        _unlocked.value = false
    }

    fun authenticate(activity: Activity, onResult: (String?) -> Unit) {
        fun enable() {
            manuallyLocked = false
            preferences.edit().putBoolean("manually_locked", false).apply()
            onResult(if (refresh()) null else "Unlock the phone before giving Ballistic requests.")
        }
        if (!keyguard.isDeviceSecure) {
            val shown = DebugTools.gateFallback(activity, "Set a secure phone screen lock to authorize requests.") { ok ->
                debugAuthenticated = ok
                if (ok) enable() else onResult("Not unlocked")
            }
            if (!shown) onResult("Set a secure phone screen lock to authorize requests.")
        } else if (power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
            enable()
        } else {
            keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = enable()
                override fun onDismissCancelled() { onResult("Phone unlock cancelled") }
                override fun onDismissError() { onResult("Unlock the phone first, then return to Ballistic.") }
            })
        }
    }
}
