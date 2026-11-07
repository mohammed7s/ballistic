// Debug builds only (src/debug; src/release has a version that does nothing). Lets a test
// emulator be set up without typing a secret into a shell or a UI field:
//
//   files/models_key           the model API key: imported into SecretStore, then deleted.
//   files/models_import.json   a models.json (ModelsConfig) to install, then deleted.
//
// Both are written with `adb shell run-as app.ballistic.assistant` (apps/assistant/README.md).
// Neither the key nor its length is ever logged or shown.
//
// Also the command gate's fallback for a device with no strong biometric enrolled (the
// AndroidWorld emulator): a fixed test PIN. It opens the command gate ONLY. Hard confirmations
// (ConfirmActivity) never use it and still need a biometric bound to the operation key.

package app.ballistic.assistant.debug

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import app.ballistic.assistant.models.ModelsConfig
import app.ballistic.assistant.storage.ModelsConfigStore
import app.ballistic.assistant.storage.SecretStore
import java.io.File

object DebugTools {
    const val AVAILABLE = true

    /** The test PIN for the command gate on a device without an enrolled biometric. */
    private const val GATE_PIN = "2580"

    /** Imports and deletes the files above. Returns what happened, for the Settings screen. */
    fun importFiles(filesDir: File, secrets: SecretStore, models: ModelsConfigStore): List<String> {
        val notes = mutableListOf<String>()
        val keyFile = filesDir.resolve("models_key")
        if (keyFile.exists()) {
            val key = keyFile.readText().trim()
            keyFile.delete()
            if (key.isNotEmpty()) {
                secrets.put(SecretStore.MODEL_API_KEY, key)
                notes += "Imported the API key from files/models_key (file deleted)."
            } else {
                notes += "files/models_key was empty (file deleted)."
            }
        }
        val configFile = filesDir.resolve("models_import.json")
        if (configFile.exists()) {
            val config = ModelsConfig.decodeOrNull(configFile.readText())
            configFile.delete()
            if (config != null) {
                models.save(config)
                notes += "Imported models.json from files/models_import.json (file deleted)."
            } else {
                notes += "files/models_import.json did not parse as models.json (file deleted)."
            }
        }
        return notes
    }

    /**
     * Shows the test-PIN dialog when no strong biometric can be used. Returns false (and shows
     * nothing) only in release builds; [onResult] gets true if the PIN matched.
     */
    fun gateFallback(activity: Activity, why: String, onResult: (Boolean) -> Unit): Boolean {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Test PIN"
        }
        AlertDialog.Builder(activity)
            .setTitle("Unlock Ballistic (debug build)")
            .setMessage("$why\nThis debug build accepts its test PIN for the command gate only. Confirmations still need a fingerprint.")
            .setView(field)
            .setPositiveButton("Unlock") { _, _ -> onResult(field.text.toString() == GATE_PIN) }
            .setNegativeButton("Cancel") { _, _ -> onResult(false) }
            .setOnCancelListener { onResult(false) }
            .show()
        return true
    }
}
