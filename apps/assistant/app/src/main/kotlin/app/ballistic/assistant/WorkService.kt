package app.ballistic.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Keeps owner-started work alive when its Activity is hidden. Never restarts a task. */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as BallisticApp).graph
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = 0L
    private var endingNormally = false
    private var startupFailed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        started = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Active work", NotificationManager.IMPORTANCE_LOW),
        )
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(ID, notification("Starting"), type)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ballistic:active-work")
                .apply { setReferenceCounted(false); acquire() }
            getSharedPreferences("work_lifecycle", MODE_PRIVATE).edit().putBoolean("active", true).apply()
        } catch (_: Exception) {
            startupFailed = true
            graph.backgroundNotice.value = "Android could not keep this task running in the background. The task was stopped."
            graph.stop()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupFailed) return START_NOT_STICKY
        endingNormally = false
        if (intent?.action == STOP) {
            graph.stop()
        }
        // A notification action is a new service start. Observe completion with its startId,
        // otherwise stopSelfResult rejects the older collector's ID and leaves the service alive.
        scope.coroutineContext.cancelChildren()
        scope.launch {
            combine(graph.session.state, graph.localAi.busy) { run, local -> run to local }
                .collect { (run, local) ->
                    if (!run.canStop && !local) {
                        endingNormally = true
                        stopSelfResult(startId)
                    } else {
                        val label = if (run.canStop) run.activity ?: "Working" else "Local AI setup or inference"
                        getSystemService(NotificationManager::class.java).notify(ID, notification(label))
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun notification(status: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, WorkService::class.java).setAction(STOP), flags)
        val run = graph.session.state.value.runs.lastOrNull()
        val elapsed = if (graph.session.state.value.canStop && run != null) graph.session.elapsedMillis(run)
            else SystemClock.elapsedRealtime() - started
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Ballistic is working")
            .setContentText(status)
            .setContentIntent(open)
            .setWhen(System.currentTimeMillis() - elapsed)
            .setUsesChronometer(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        if (!endingNormally && (graph.session.state.value.canStop || graph.localAi.busy.value)) graph.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        getSharedPreferences("work_lifecycle", MODE_PRIVATE).edit().putBoolean("active", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "active_work"
        private const val ID = 2
        private const val STOP = "app.ballistic.assistant.STOP_WORK"
    }
}
