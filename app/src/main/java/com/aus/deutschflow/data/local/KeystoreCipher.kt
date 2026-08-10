package com.aus.deutschflow.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KeystoreCipher"

/**
 * Encrypts the API key so that it is not sitting in the clear on the device.
 *
 * The key material lives in the Android Keystore and never enters this process -
 * on a device with a TEE or StrongBox it never leaves secure hardware at all, so a
 * copy of the DataStore file is worth nothing on its own. That file is already
 * excluded from cloud backup and device transfer; this is the other half, for the
 * cases that exclusion does not cover, like a rooted device or an adb backup of a
 * debuggable build.
 *
 * No dependency: androidx.security's EncryptedSharedPreferences would do the same
 * job, and it is deprecated. AES/GCM through javax.crypto is about sixty lines and
 * is not going anywhere.
 */
@Singleton
class KeystoreCipher @Inject constructor() {

    /**
     * @return the ciphertext, or null if it could not be produced - in which case
     * the caller must not fall back to storing the plaintext.
     */
    fun encrypt(plainText: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // The IV is generated per encryption and is not a secret; it is prefixed so
        // that decryption has it without a second stored field.
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Could not encrypt the stored key", e)
        null
    }

    /**
     * @return the plaintext, or null when the stored value cannot be read.
     *
     * Null is an ordinary outcome, not an error to escalate: the Keystore entry is
     * dropped when the device's lock screen is removed, and a restored backup can
     * carry ciphertext whose key never came with it. Both mean the same thing to the
     * user - the key is gone and needs entering again - and neither is a crash.
     */
    fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val body = bytes.copyOfRange(IV_LENGTH, bytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the stored key; it will have to be entered again", e)
        null
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // A fresh IV per encryption, enforced by the Keystore rather than
                    // trusted to this code: GCM with a reused IV leaks the key stream.
                    .setRandomizedEncryptionRequired(true)
                    // Deliberately not user-authentication bound. The translation call
                    // can run while the screen is off, and a key the app cannot reach
                    // without a lock-screen prompt would fail there with no way to
                    // explain itself.
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "deutschflow.api-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
