// Hands one ConfirmRequest at a time from core's thread to the confirmation window and back.
// core blocks in Confirm.ask until the owner decides, Stop denies, or the request times out.
//
// Binding: a decision names the pending request's id; a window still showing an older request
// cannot answer a newer one. A request is immutable, so "editing" means a new request and a new
// id: any earlier approval is void. Nothing approves by default: dismissal, timeout, Stop and
// process death all deny.

package app.ballistic.assistant.confirm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.ballistic.assistant.R
import app.ballistic.assistant.core.ConfirmClass
import app.ballistic.assistant.core.ConfirmRequest
import app.ballistic.assistant.core.ConfirmResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfirmBroker(private val context: Context) {
    data class Pending(
        val id: String,
        val request: ConfirmRequest,
        val required: ConfirmClass,
        val decision: CompletableFuture<Decision>,
    )

    /** [signature] is the per-operation key's signature over the canonical request (hard only). */
    data class Decision(val result: ConfirmResult, val note: String, val signature: ByteArray? = null)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Blocks the calling (core) thread. Never call on the main thread. */
    fun ask(request: ConfirmRequest, required: ConfirmClass): Decision {
        if (required == ConfirmClass.Refuse) return Decision(ConfirmResult.Denied, "policy: never automate this app")
        val pending = Pending(UUID.randomUUID().toString(), request, required, CompletableFuture())
        synchronized(this) {
            if (_pending.value != null) return Decision(ConfirmResult.Denied, "another approval is open")
            _pending.value = pending
        }
        try {
            raiseWindow(pending)
            return pending.decision.get(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (e: TimeoutException) {
            return Decision(ConfirmResult.Denied, "no decision; timed out")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Decision(ConfirmResult.Denied, "interrupted")
        } finally {
            synchronized(this) { if (_pending.value?.id == pending.id) _pending.value = null }
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    /** From the window. Ignored unless [id] is the request still pending. */
    fun decide(id: String, decision: Decision) {
        val current = _pending.value ?: return
        if (current.id == id) current.decision.complete(decision)
    }

    /** Stop: deny whatever is waiting. */
    fun denyPending(note: String) {
        _pending.value?.decision?.complete(Decision(ConfirmResult.Denied, note))
    }

    /**
     * Starts the window and posts a notification that opens it. Android blocks activity starts
     * from the background (Ballistic is in the background whenever IntentHands has opened another
     * app), so the notification is the path that always works; the direct start is best effort.
     */
    private fun raiseWindow(pending: Pending) {
        val intent = Intent(context, ConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.confirm_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val kind = if (pending.required == ConfirmClass.Hard) "fingerprint approval" else "approval"
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Ballistic needs your $kind")
                // The notification shows only the app, never values: it is visible on the shade.
                .setContentText(pending.request.app)
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "approvals"
        private const val NOTIFICATION_ID = 1
        /** A run waits this long for a decision before the request is denied. */
        private const val TIMEOUT_MINUTES = 10L
    }
}
