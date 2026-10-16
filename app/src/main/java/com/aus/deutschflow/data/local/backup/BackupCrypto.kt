package com.aus.deutschflow.data.local.backup

import java.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, passphrase-based encryption for DeutschFlow library backups.
 *
 * Uses standard, widely-supported primitives:
 * - PBKDF2 with HMAC-SHA256 (100,000 iterations, 16-byte random salt, 256-bit key)
 * - AES-256-GCM (12-byte random IV, 128-bit authentication tag)
 *
 * Deliberately does NOT use the device Android Keystore key, so that backups can be
 * safely transferred and restored on other devices or after a full device wipe.
 */
object BackupCrypto {

    const val ENCRYPTED_FORMAT = "deutschflow-library-encrypted"
    const val ENCRYPTED_VERSION = 1
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    const val ITERATIONS = 100_000
    const val KEY_LENGTH_BITS = 256
    const val SALT_LENGTH_BYTES = 16
    const val IV_LENGTH_BYTES = 12
    const val TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    fun isEncryptedBackup(json: JSONObject): Boolean {
        return json.optString("format") == ENCRYPTED_FORMAT
    }

    fun encrypt(plainText: String, passphrase: CharArray): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
        val iv = ByteArray(IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }

        val keySpec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH_BITS)
        val keyFactory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return JSONObject().apply {
            put("format", ENCRYPTED_FORMAT)
            put("version", ENCRYPTED_VERSION)
            put("kdf", KDF_ALGORITHM)
            put("iterations", ITERATIONS)
            put("salt", Base64.getEncoder().encodeToString(salt))
            put("iv", Base64.getEncoder().encodeToString(iv))
            put("ciphertext", Base64.getEncoder().encodeToString(cipherBytes))
        }.toString(2)
    }

    fun decrypt(encryptedJson: JSONObject, passphrase: CharArray?): String {
        if (passphrase == null || passphrase.isEmpty()) {
            throw BackupException.PassphraseRequired("Passphrase is required for encrypted backup.")
        }

        val format = encryptedJson.optString("format")
        if (format != ENCRYPTED_FORMAT) {
            throw BackupException.InvalidFormat("Not an encrypted DeutschFlow backup.")
        }

        val version = encryptedJson.optInt("version", -1)
        if (version > ENCRYPTED_VERSION) {
            throw BackupException.UnsupportedVersion("Encrypted backup version $version is not supported.")
        }

        val saltStr = encryptedJson.optString("salt")
        val ivStr = encryptedJson.optString("iv")
        val ciphertextStr = encryptedJson.optString("ciphertext")
        val iterations = encryptedJson.optInt("iterations", ITERATIONS)

        if (saltStr.isBlank() || ivStr.isBlank() || ciphertextStr.isBlank()) {
            throw BackupException.CorruptedData("Encrypted backup is missing cryptographic fields.")
        }

        val salt = try {
            Base64.getDecoder().decode(saltStr)
        } catch (e: Exception) {
            throw BackupException.CorruptedData("Invalid salt encoding.")
        }

        val iv = try {
            Base64.getDecoder().decode(ivStr)
        } catch (e: Exception) {
            throw BackupException.CorruptedData("Invalid IV encoding.")
        }

        val ciphertext = try {
            Base64.getDecoder().decode(ciphertextStr)
        } catch (e: Exception) {
            throw BackupException.CorruptedData("Invalid ciphertext encoding.")
        }

        if (salt.size < 8 || iv.size != IV_LENGTH_BYTES) {
            throw BackupException.CorruptedData("Invalid cryptographic parameters in encrypted backup.")
        }

        try {
            val keySpec = PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS)
            val keyFactory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = keyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val plainBytes = cipher.doFinal(ciphertext)
            return String(plainBytes, Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw BackupException.WrongPassphrase("Incorrect passphrase or corrupted backup.")
        } catch (e: Exception) {
            if (e is BackupException) throw e
            throw BackupException.WrongPassphrase("Decryption failed: ${e.message}")
        }
    }
}
