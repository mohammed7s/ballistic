package app.ballistic.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit immutable notification action; works even when no Ask foreground service exists. */
class ScreenControlStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as BallisticApp).graph.stop()
    }
}
