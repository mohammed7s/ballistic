// Launchable apps, for ScreenState.installed_apps and the policy table. Visible through the
// manifest's <queries> MAIN/LAUNCHER entry; no QUERY_ALL_PACKAGES.

package app.ballistic.assistant.adapters

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.ballistic.assistant.core.AppEntry

object InstalledApps {
    fun list(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppEntry(label = it.loadLabel(pm).toString(), `package` = it.activityInfo.packageName, activity = it.activityInfo.name) }
            .distinctBy { it.`package` }
            .sortedBy { it.label.lowercase() }
    }
}
