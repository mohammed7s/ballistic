// Release builds: no file imports and no command-gate fallback (see src/debug).

package app.ballistic.assistant.debug

import android.app.Activity
import app.ballistic.assistant.storage.ModelsConfigStore
import app.ballistic.assistant.storage.SecretStore
import java.io.File

object DebugTools {
    const val AVAILABLE = false

    fun importFiles(filesDir: File, secrets: SecretStore, models: ModelsConfigStore): List<String> = emptyList()

    fun gateFallback(activity: Activity, why: String, onResult: (Boolean) -> Unit): Boolean = false
}
