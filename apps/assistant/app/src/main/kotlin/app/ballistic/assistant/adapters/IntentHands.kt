// Hands port for an unprivileged app: what any app may do with intents (PLAN step 3).
//
// Core operations this adapter executes (advertised in HostCapabilities.operations):
//   open_app  start the app's launcher activity (or the given component). Ok means the system
//             accepted the intent. Android 14+ may silently refuse activity starts while
//             Ballistic is in the background, so Ok is an acknowledgement, never proof the app
//             is in front; core observes before it believes anything.
//   wait      sleep, at most 10 s.
//   intent    one typed operation (core/src/operations.rs), advertised per handler in
//             HostCapabilities.intents by intentGrants(): the public, documented platform intent,
//             sent to exactly the package core chose. Nothing here submits: no EXTRA_SKIP_UI=true,
//             no ACTION_CALL (dial is ACTION_DIAL), no ACTION_IMAGE_CAPTURE, no SmsManager. An
//             editor or compose screen stays open for the owner or for core's guarded Save/Send.
//             Timers and alarms are created by the clock app when it accepts the intent; core asks
//             the owner before it sends either.
// Not possible without Computer Control (docs/PLATFORM.md): tap, long_press, type, scroll, swipe,
// back, home. These return NotExecuted ("Host guarantees no input was dispatched", Types.kt),
// which is the truth here; Error would tell core the effect is unknown and stop the run as if a
// duplicate effect were possible.
//
// The notification reply below has no HandsOp in core yet; it is reached only from shell code.

package app.ballistic.assistant.adapters

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import app.ballistic.assistant.core.CameraMode
import app.ballistic.assistant.core.HandsOp
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.Hands
import app.ballistic.assistant.core.IntentGrant
import app.ballistic.assistant.core.IntentOp
import app.ballistic.assistant.core.SettingsPage
import app.ballistic.assistant.core.Weekday
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

class IntentHands(private val context: Context) : Hands {
    override fun act(op: HandsOp): HandsResult = when (op) {
        is HandsOp.OpenApp -> openApp(op)
        is HandsOp.Wait -> {
            Thread.sleep(op.millis.coerceIn(0, MAX_WAIT_MS).toLong())
            HandsResult.Ok
        }
        is HandsOp.Intent -> startIntent(op)
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

    /** Ok = the system accepted the start in exactly [HandsOp.Intent.package]; nothing is saved or sent. */
    private fun startIntent(op: HandsOp.Intent): HandsResult {
        val built = try {
            build(op.intent, ZoneId.systemDefault())
        } catch (e: IllegalArgumentException) {
            return HandsResult.NotExecuted("invalid operation values")
        }
        val intent = built.setPackage(op.`package`).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return HandsResult.NotExecuted("${op.`package`} does not handle this")
        return start(intent)
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

    /**
     * (operation, package) pairs to advertise: handlers that resolve on this device now, among
     * [granted] packages. Each operation is probed with a representative intent of the same
     * action, data scheme and type the real one uses (manifest <queries> makes them visible).
     */
    fun intentGrants(granted: Collection<String>): List<IntentGrant> {
        val pm = context.packageManager
        return PROBES.flatMap { (operation, probe) ->
            pm.queryIntentActivities(build(probe, ZoneId.systemDefault()), PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }.distinct().filter { it in granted }
                .map { IntentGrant(operation, it) }
        }
    }

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
        private const val ALL_APPS_NOTIFICATIONS = "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"

        /** One representative operation per id, for [intentGrants]. */
        val PROBES: List<Pair<String, IntentOp>> = listOf(
            "set_timer" to IntentOp.SetTimer(60),
            "set_alarm" to IntentOp.SetAlarm(7, 0),
            "create_event" to IntentOp.CreateEvent("probe", "2030-01-01T09:00", "2030-01-01T10:00"),
            "compose_sms" to IntentOp.ComposeSms("5550100", "probe"),
            "add_contact" to IntentOp.AddContact("probe"),
            "open_settings" to IntentOp.OpenSettings(SettingsPage.Wifi),
            "open_camera" to IntentOp.OpenCamera(CameraMode.Photo),
            "dial" to IntentOp.Dial("5550100"),
            "view_uri" to IntentOp.ViewUri("https://example.com"),
        )

        private val PHONE = Regex("""^\+?[0-9]{3,15}$""")
        private val GEO = Regex("""^geo:-?[0-9.]+,-?[0-9.]+$""")

        /**
         * The documented intent for [op], without package or flags. Core already validated every
         * value; the checks here are a second fence (phone digits, https/geo only) and throw
         * IllegalArgumentException, reported as NotExecuted.
         */
        fun build(op: IntentOp, zone: ZoneId): Intent = when (op) {
            // AlarmClock: "This action always starts the timer." EXTRA_SKIP_UI stays false.
            is IntentOp.SetTimer -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, op.seconds.also { require(it in 1..86_400) })
                .apply { op.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            is IntentOp.SetAlarm -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, op.hour.also { require(it in 0..23) })
                .putExtra(AlarmClock.EXTRA_MINUTES, op.minute.also { require(it in 0..59) })
                .apply {
                    op.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    if (op.days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(op.days.map(::calendarDay)))
                }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            // Calendar provider guide, "Use an intent to insert an event": opens the editor.
            is IntentOp.CreateEvent -> Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis(op.begin, zone))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis(op.end, zone))
                .putExtra(CalendarContract.Events.TITLE, op.title)
                .apply {
                    op.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                    op.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                }
            // ACTION_SENDTO smsto: with sms_body opens a compose screen; it never sends.
            is IntentOp.ComposeSms -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone(op.to), null))
                .putExtra("sms_body", op.body)
            // ContactsContract.Intents.Insert, type RawContacts.CONTENT_TYPE: the contacts editor.
            is IntentOp.AddContact -> Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, op.name)
                .apply {
                    op.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, phone(it)) }
                    op.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                }
            is IntentOp.OpenSettings -> Intent(
                when (op.page) {
                    SettingsPage.Wifi -> Settings.ACTION_WIFI_SETTINGS
                    SettingsPage.InternetPanel -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                    SettingsPage.Bluetooth -> Settings.ACTION_BLUETOOTH_SETTINGS
                    SettingsPage.Display -> Settings.ACTION_DISPLAY_SETTINGS
                    SettingsPage.Sound -> Settings.ACTION_SOUND_SETTINGS
                    SettingsPage.Notifications -> ALL_APPS_NOTIFICATIONS
                    SettingsPage.Apps -> Settings.ACTION_APPLICATION_SETTINGS
                    SettingsPage.Date -> Settings.ACTION_DATE_SETTINGS
                },
            )
            // The camera app's own capture screen saves to its gallery as usual.
            is IntentOp.OpenCamera -> Intent(
                if (op.mode == CameraMode.Photo) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA else MediaStore.INTENT_ACTION_VIDEO_CAMERA,
            )
            // ACTION_DIAL shows the number; the call needs the dialer's own call button.
            is IntentOp.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone(op.number), null))
            is IntentOp.ViewUri -> {
                require(op.uri.startsWith("https://") || GEO.matches(op.uri))
                Intent(Intent.ACTION_VIEW, Uri.parse(op.uri)).addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }

        private fun phone(s: String): String = s.also { require(PHONE.matches(it)) }

        private fun millis(local: String, zone: ZoneId): Long = try {
            LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("bad date-time")
        }

        private fun calendarDay(d: Weekday): Int = when (d) {
            Weekday.Monday -> Calendar.MONDAY
            Weekday.Tuesday -> Calendar.TUESDAY
            Weekday.Wednesday -> Calendar.WEDNESDAY
            Weekday.Thursday -> Calendar.THURSDAY
            Weekday.Friday -> Calendar.FRIDAY
            Weekday.Saturday -> Calendar.SATURDAY
            Weekday.Sunday -> Calendar.SUNDAY
        }
    }
}
