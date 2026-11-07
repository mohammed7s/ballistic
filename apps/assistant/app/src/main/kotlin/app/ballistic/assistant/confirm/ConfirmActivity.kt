// The confirmation window (SPEC.md §7.1). Shows one ConfirmRequest exactly: app, verb, target,
// the resolved operation, every value with its source, and why core asked. Approve / Deny.
//
//  - FLAG_SECURE: no screenshots, recording or Recents thumbnail of what is approved.
//  - Other apps' overlays are hidden (setHideOverlayWindows) and touches through an obscuring
//    window are dropped (filterTouchesWhenObscured, plus the filter's obscured check).
//  - Every touch passes InjectedInputFilter; key events other than Back are dropped, so a
//    virtual dpad or keyboard cannot focus and press Approve. Back denies.
//  - Approve acts only within a short time of a verified finger lift, so an accessibility
//    ACTION_CLICK (which is no input event) cannot approve on its own.
//  - Hard requests: Approve opens BiometricPrompt (BIOMETRIC_STRONG only, no PIN/pattern
//    fallback) bound to a per-operation key that signs the exact request (OperationKey).
//  - Leaving the window does not decide; the request stays pending until the owner returns,
//    Stop, or the broker's timeout. Finishing the window without a decision denies.

package app.ballistic.assistant.confirm

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.core.ConfirmClass
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.CoreJson
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.Source
import app.ballistic.assistant.graph
import app.ballistic.assistant.ui.BallisticTheme

class ConfirmActivity : ComponentActivity() {
    private lateinit var filter: InjectedInputFilter
    private var lastVerifiedUp = 0L
    private var gestureAccepted = false
    private var gestureDeviceId = KeyCharacterMap.VIRTUAL_KEYBOARD
    private var decided = false
    private var status by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        setRecentsScreenshotEnabled(false)
        filter = InjectedInputFilter(this)
        val broker = graph.broker
        setContent {
            BallisticTheme {
                val pending by broker.pending.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    val current = pending
                    if (current == null) {
                        // Decided, stopped or timed out while the window was away.
                        Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                            Text("Nothing is waiting for approval.")
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { finish() }) { Text("Close") }
                        }
                    } else {
                        ConfirmContent(current, status, onApprove = { approve(current) }, onDeny = { deny(current, "denied by owner") })
                    }
                }
            }
        }
        window.decorView.filterTouchesWhenObscured = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                broker.pending.value?.let { deny(it, "dismissed") } ?: finish()
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // No display yet means the window is not shown; nothing can be approved.
        val displayId = window.decorView.display?.displayId ?: return true
        // ACTION_MOVE is not verified: the input layer batches move samples and may append a
        // predicted one, so the signature over the event no longer matches and
        // verifyInputEvent() fails for real fingers. A move inside a gesture whose DOWN passed
        // the full check is held to the same device, touchscreen source and finger tool, and
        // the decisive UP gets the full check again.
        val verdict = if (event.actionMasked == MotionEvent.ACTION_MOVE && gestureAccepted) {
            filter.checkMove(event, gestureDeviceId)
        } else {
            filter.check(event, displayId)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureAccepted = verdict.accepted
            gestureDeviceId = event.deviceId
        }
        if (!verdict.accepted || !gestureAccepted) {
            // Drop it. If a gesture was in progress, cancel it so no button stays pressed.
            if (gestureAccepted) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                gestureAccepted = false
            }
            status = "Ignored input: ${verdict.reason}"
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) lastVerifiedUp = SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return true
    }

    override fun onDestroy() {
        // A window closed without a decision denies; rotation keeps the request pending.
        if (!decided && isFinishing) graph.broker.pending.value?.let { deny(it, "window closed") }
        super.onDestroy()
    }

    private fun recentVerifiedTouch() = SystemClock.uptimeMillis() - lastVerifiedUp < CLICK_WINDOW_MS

    private fun approve(pending: ConfirmBroker.Pending) {
        if (!recentVerifiedTouch()) {
            status = "Approve needs a touch on the screen"
            return
        }
        when (pending.required) {
            ConfirmClass.Refuse -> deny(pending, "policy refuses")
            ConfirmClass.Soft -> finishWith(pending, ConfirmBroker.Decision(ConfirmResult.Approved, "approved (soft)"))
            ConfirmClass.Hard -> approveWithBiometric(pending)
        }
    }

    private fun approveWithBiometric(pending: ConfirmBroker.Pending) {
        if (getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            status = "This needs a strong biometric, and none is available. Deny, or enrol a fingerprint."
            return
        }
        val key = try {
            OperationKey.create()
        } catch (e: Exception) {
            status = "Could not create the approval key"
            return
        }
        val executor = mainExecutor
        BiometricPrompt.Builder(this)
            .setTitle("Approve in ${pending.request.app}")
            .setSubtitle(pending.request.verb + " " + pending.request.target)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(true)
            .setNegativeButton("Cancel", executor) { _, _ -> key.discard(); status = "Not approved" }
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(key.signature),
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authorised = result.cryptoObject?.signature
                        val sig = authorised?.let { key.signAndDiscard(it, pending.request) }
                        if (sig == null) {
                            status = "Approval could not be bound to this operation"
                            return
                        }
                        finishWith(pending, ConfirmBroker.Decision(ConfirmResult.Approved, "approved (biometric)", sig))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        key.discard()
                        status = "Not approved: $errString"
                    }
                },
            )
    }

    private fun deny(pending: ConfirmBroker.Pending, note: String) {
        finishWith(pending, ConfirmBroker.Decision(ConfirmResult.Denied, note))
    }

    private fun finishWith(pending: ConfirmBroker.Pending, decision: ConfirmBroker.Decision) {
        decided = true
        graph.broker.decide(pending.id, decision)
        finish()
    }

    companion object {
        /** Approve must follow a verified finger lift within this many milliseconds. */
        private const val CLICK_WINDOW_MS = 500L
    }
}

@Composable
private fun ConfirmContent(pending: ConfirmBroker.Pending, status: String?, onApprove: () -> Unit, onDeny: () -> Unit) {
    val r = pending.request
    Column(
        Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (pending.required == ConfirmClass.Hard) "Approve with fingerprint" else "Approve",
            style = MaterialTheme.typography.headlineSmall,
        )
        Field("App", r.app)
        Field("Action", r.verb)
        Field("Target", r.target)
        Field("Exact operation", operationText(r.operation), mono = true)
        if (r.values.isNotEmpty()) {
            Text("Values", style = MaterialTheme.typography.labelLarge)
            for (v in r.values) {
                Text("“${v.value}”  from ${sourceText(v.source)}", fontWeight = if (v.source.isOwner) FontWeight.Normal else FontWeight.Bold)
            }
        }
        Field("Why core asks", r.reason)
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
            Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text("Approve") }
        }
    }
}

@Composable
private fun Field(name: String, value: String, mono: Boolean = false) {
    Column {
        Text(name, style = MaterialTheme.typography.labelLarge)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

private fun operationText(op: HandsOp): String = CoreJson.encodeToString(HandsOp.serializer(), op)

/** Untrusted sources are named in full so a forged value shows where it came from. */
private fun sourceText(source: Source): String = when (source) {
    Source.OwnerRequest -> "your request"
    Source.OwnerData -> "your data"
    Source.ScreenLabel -> "a control's label"
    is Source.App -> "content in ${source.detail} (not you)"
    is Source.Sms -> "an SMS: ${source.detail} (not you)"
    is Source.Note -> "a note: ${source.detail} (not you)"
    is Source.Web -> "a web page: ${source.detail} (not you)"
}
