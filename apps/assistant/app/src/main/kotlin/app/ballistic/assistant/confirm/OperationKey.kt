// Per-operation key for hard confirmations (SPEC.md §7.1). For each request a fresh Keystore EC
// key is created that can sign only after a strong biometric for that one use
// (setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)). The fingerprint is matched in
// the TEE and the auth token goes to Keystore, so an injected touch on our window cannot produce
// a signature. The key signs the canonical description of the exact operation; the signature is
// checked against the public key and the key is deleted.
//
// Limit today: core's Confirm port returns only approved/denied, so core does not verify the
// signature itself. The binding that holds now is local: no signature over these exact bytes,
// no approval.

package app.ballistic.assistant.confirm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.ballistic.assistant.core.ConfirmRequest
import app.ballistic.assistant.core.CoreJson
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

class OperationKey private constructor(val alias: String) {
    /** Initialised for signing; pass to BiometricPrompt.CryptoObject. */
    val signature: Signature = Signature.getInstance(ALGORITHM).apply {
        initSign(keyStore().getKey(alias, null) as java.security.PrivateKey)
    }

    /**
     * Signs [request] with the authenticated [authorised] Signature from the prompt result,
     * verifies it, and deletes the key. Returns the signature, or null if anything fails.
     */
    fun signAndDiscard(authorised: Signature, request: ConfirmRequest): ByteArray? = try {
        val bytes = canonical(request)
        authorised.update(bytes)
        val sig = authorised.sign()
        val verifier = Signature.getInstance(ALGORITHM).apply {
            initVerify(keyStore().getCertificate(alias).publicKey)
            update(bytes)
        }
        if (verifier.verify(sig)) sig else null
    } catch (e: Exception) {
        null
    } finally {
        discard()
    }

    fun discard() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    companion object {
        private const val ALGORITHM = "SHA256withECDSA"
        private const val KEYSTORE = "AndroidKeyStore"

        /** The exact bytes approved: core's JSON form of the request (operation, app, values, sources). */
        fun canonical(request: ConfirmRequest): ByteArray =
            CoreJson.encodeToString(ConfirmRequest.serializer(), request).toByteArray(Charsets.UTF_8)

        fun create(): OperationKey {
            val alias = "ballistic.confirm." + UUID.randomUUID()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        .setInvalidatedByBiometricEnrollment(true)
                        .build(),
                )
            }.generateKeyPair()
            return OperationKey(alias)
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }
}
