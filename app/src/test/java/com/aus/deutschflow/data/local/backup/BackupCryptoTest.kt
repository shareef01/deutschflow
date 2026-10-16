package com.aus.deutschflow.data.local.backup
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun `encrypts and decrypts with valid passphrase`() {
        val plainText = """{"format":"deutschflow-library","version":2,"exportedAt":1234567890,"vocabulary":[]}"""
        val passphrase = "correct-horse-battery-staple".toCharArray()

        val encryptedJsonString = BackupCrypto.encrypt(plainText, passphrase)
        val encryptedJson = JSONObject(encryptedJsonString)

        assertTrue(BackupCrypto.isEncryptedBackup(encryptedJson))
        assertEquals(BackupCrypto.ENCRYPTED_FORMAT, encryptedJson.getString("format"))
        assertEquals(BackupCrypto.ENCRYPTED_VERSION, encryptedJson.getInt("version"))

        val decrypted = BackupCrypto.decrypt(encryptedJson, passphrase)
        assertEquals(plainText, decrypted)
    }

    @Test
    fun `decrypt throws WrongPassphrase with incorrect passphrase`() {
        val plainText = """{"hello":"welt"}"""
        val encryptedJsonString = BackupCrypto.encrypt(plainText, "correct-pass".toCharArray())
        val encryptedJson = JSONObject(encryptedJsonString)

        assertThrows(BackupException.WrongPassphrase::class.java) {
            BackupCrypto.decrypt(encryptedJson, "wrong-pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws PassphraseRequired when passphrase is null or empty`() {
        val plainText = """{"hello":"welt"}"""
        val encryptedJsonString = BackupCrypto.encrypt(plainText, "pass".toCharArray())
        val encryptedJson = JSONObject(encryptedJsonString)

        assertThrows(BackupException.PassphraseRequired::class.java) {
            BackupCrypto.decrypt(encryptedJson, null)
        }

        assertThrows(BackupException.PassphraseRequired::class.java) {
            BackupCrypto.decrypt(encryptedJson, charArrayOf())
        }
    }

    @Test
    fun `decrypt throws InvalidFormat for unencrypted payload`() {
        val plainJson = JSONObject("""{"format":"deutschflow-library","version":2}""")
        assertFalse(BackupCrypto.isEncryptedBackup(plainJson))

        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupCrypto.decrypt(plainJson, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws UnsupportedVersion for future versions`() {
        val futureJson = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 999,
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")

        assertThrows(BackupException.UnsupportedVersion::class.java) {
            BackupCrypto.decrypt(futureJson, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws CorruptedData for missing or malformed crypto fields`() {
        val missingFields = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1
        }""")

        assertThrows(BackupException.CorruptedData::class.java) {
            BackupCrypto.decrypt(missingFields, "pass".toCharArray())
        }

        val invalidBase64 = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "salt": "not-valid-base64!!!",
            "iv": "not-valid-base64!!!",
            "ciphertext": "not-valid-base64!!!"
        }""")

        assertThrows(BackupException.CorruptedData::class.java) {
            BackupCrypto.decrypt(invalidBase64, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws UnsupportedVersion for negative or zero version`() {
        val zeroVersion = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 0,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": 100000,
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.UnsupportedVersion::class.java) {
            BackupCrypto.decrypt(zeroVersion, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws InvalidFormat for unsupported KDF algorithm`() {
        val untrustedKdf = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "ScryptWithMD5",
            "iterations": 100000,
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupCrypto.decrypt(untrustedKdf, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws CorruptedData for missing PBKDF2 iterations`() {
        val missingIterations = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "PBKDF2WithHmacSHA256",
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.CorruptedData::class.java) {
            BackupCrypto.decrypt(missingIterations, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws InvalidFormat for out of range PBKDF2 iterations`() {
        // Oversized iterations (DoS attempt)
        val oversizedIterations = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": 10000000,
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupCrypto.decrypt(oversizedIterations, "pass".toCharArray())
        }

        // Insufficient iterations (weak key derivation)
        val weakIterations = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": 1000,
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupCrypto.decrypt(weakIterations, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws CorruptedData for undersized or oversized salt or invalid IV length`() {
        // 8 bytes salt is below MIN_SALT_BYTES (16)
        val shortSalt = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": 100000,
            "salt": "c2FsdDEyMw==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.CorruptedData::class.java) {
            BackupCrypto.decrypt(shortSalt, "pass".toCharArray())
        }

        // 65 bytes salt is above MAX_SALT_BYTES (64)
        val oversizedSaltBytes = ByteArray(65) { 1.toByte() }
        val oversizedSalt = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": 100000,
            "salt": "${Base64.getEncoder().encodeToString(oversizedSaltBytes)}",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.CorruptedData::class.java) {
            BackupCrypto.decrypt(oversizedSalt, "pass".toCharArray())
        }

        // 8 bytes IV instead of 12 bytes
        val shortIv = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": 100000,
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2MTI=",
            "ciphertext": "Y2lwaGVydGV4dA=="
        }""")
        assertThrows(BackupException.CorruptedData::class.java) {
            BackupCrypto.decrypt(shortIv, "pass".toCharArray())
        }
    }

    @Test
    fun `decrypt throws CorruptedData or CapacityExceeded for empty or oversized ciphertext`() {
        val emptyCiphertext = JSONObject("""{
            "format": "deutschflow-library-encrypted",
            "version": 1,
            "kdf": "PBKDF2WithHmacSHA256",
            "iterations": 100000,
            "salt": "c2FsdHNhbHQxMjM0NTY3OA==",
            "iv": "aXZpdml2aXZpdg==",
            "ciphertext": ""
        }""")
        assertThrows(BackupException.CorruptedData::class.java) {
            BackupCrypto.decrypt(emptyCiphertext, "pass".toCharArray())
        }

        // Oversized ciphertext (> 10MB)
        val validIv = Base64.getEncoder().encodeToString(ByteArray(12) { 1.toByte() })
        val validSalt = Base64.getEncoder().encodeToString(ByteArray(16) { 1.toByte() })
        val hugeCipherBytes = ByteArray(10 * 1024 * 1024 + 1) { 0x42.toByte() }
        val hugeCiphertext = JSONObject().apply {
            put("format", "deutschflow-library-encrypted")
            put("version", 1)
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", 100000)
            put("salt", validSalt)
            put("iv", validIv)
            put("ciphertext", Base64.getEncoder().encodeToString(hugeCipherBytes))
        }
        assertThrows(BackupException.CapacityExceeded::class.java) {
            BackupCrypto.decrypt(hugeCiphertext, "pass".toCharArray())
        }
    }
}
