// Hands port for an unprivileged app: what any app may do with intents (PLAN step 3).
//
// Core operations this adapter executes (advertised in HostCapabilities.operations):
//   open_app  start the app's launcher activity (or the given component). Ok means the system
//             accepted the intent. Android 14+ may silently refuse activity starts while
//             Ballistic is in the background, so Ok is an acknowledgement, never proof the app
//             is in front; core observes before it believes anything.
//   wait      sleep, at most 10 s.
// Not possible without Computer Control (docs/PLATFORM.md): tap, long_press, type, scroll, swipe,
// back, home. These return NotExecuted ("Host guarantees no input was dispatched", Types.kt),
// which is the truth here; Error would tell core the effect is unknown and stop the run as if a
// duplicate effect were possible.
//
// Host-only actions below have no HandsOp in core yet, so core cannot request them; they exist
// for the next pass (core contracts for notification reply, dial, alarm; SPEC.md §5.5) and are
// reached only from shell code. Each reports what it can actually know.

package app.ballistic.assistant.adapters

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.Hands

class IntentHands(private val context: Context) : Hands {
    override fun act(op: HandsOp): HandsResult = when (op) {
        is HandsOp.OpenApp -> openApp(op)
        is HandsOp.Wait -> {
            Thread.sleep(op.millis.coerceIn(0, MAX_WAIT_MS).toLong())
            HandsResult.Ok
        }
        HandsOp.Back, HandsOp.Home, is HandsOp.Tap, is HandsOp.LongPress, is HandsOp.Type, is HandsOp.Scroll, is HandsOp.Swipe ->
            HandsResult.NotExecuted("needs Computer Control")
    }

    private fun openApp(op: HandsOp.OpenApp): HandsResult {
        val intent = op.activity?.let {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(op.`package`, it))
        } ?: context.packageManager.getLaunchIntentForPackage(op.`package`)
            ?: return HandsResult.NotExecuted("no launcher activity for ${op.`package`}")
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Replies through a notification's inline-reply action (RemoteInput). Ok means the reply
     * was handed to the app's PendingIntent; whether the app sent it is unknown until readback.
     * The caller must have passed the confirmation window for this exact text and notification.
     */
    fun replyToNotification(key: String, actionIndex: Int, text: String): HandsResult {
        val service = NotificationScreenService.connected ?: return HandsResult.NotExecuted("notification access is not granted")
        val sbn = service.activeNotifications.orEmpty().firstOrNull { it.key == key }
            ?: return HandsResult.NotExecuted("notification is gone")
        val action = sbn.notification.actions?.getOrNull(actionIndex)
            ?: return HandsResult.NotExecuted("no such action")
        val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            ?: return HandsResult.NotExecuted("action has no free-text reply")
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, text) })
        return try {
            action.actionIntent.send(context, 0, fill)
            HandsResult.Ok
        } catch (e: PendingIntent.CanceledException) {
            HandsResult.NotExecuted("the app withdrew the reply action")
        }
    }

    /** Opens the dialer with [number] filled in; the owner presses Call. Ok = dialer opened. */
    fun dial(number: String): HandsResult =
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Asks the clock app to create an alarm, showing its UI. Ok = the clock app took the intent. */
    fun setAlarm(hour: Int, minute: Int, label: String?): HandsResult =
        start(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

    private fun start(intent: Intent): HandsResult = try {
        context.startActivity(intent)
        HandsResult.Ok
    } catch (e: ActivityNotFoundException) {
        HandsResult.NotExecuted("no app handles this")
    } catch (e: SecurityException) {
        HandsResult.NotExecuted("not permitted")
    }

    companion object {
        /** What core may ask of this adapter (core/src/capabilities.rs operation ids). */
        val OPERATIONS = listOf("open_app", "wait")
        private const val MAX_WAIT_MS = 10_000
    }
}
