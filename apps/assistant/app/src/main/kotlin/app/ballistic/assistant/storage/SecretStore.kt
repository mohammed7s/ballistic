// The model API key at rest. Encrypted with an AES-GCM key held in Android Keystore (never
// exported), ciphertext in app-private SharedPreferences. Plain Keystore rather than
// androidx.security-crypto, whose EncryptedSharedPreferences is deprecated.
//
// The key needs no user authentication (core's model calls happen mid-run, unattended). It is
// excluded from backup (data_extraction_rules.xml). Values are never logged; get() is the only
// way out and its result goes straight into an Authorization header.

package app.ballistic.assistant.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun has(name: String): Boolean = prefs.contains(name)

    @Synchronized
    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    /** Null if absent or if it no longer decrypts (Keystore key lost); never throws the value. */
    @Synchronized
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val MODEL_API_KEY = "model_api_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "ballistic.secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
