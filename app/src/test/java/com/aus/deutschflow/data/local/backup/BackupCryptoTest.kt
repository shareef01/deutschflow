package com.aus.deutschflow.data.local.backup

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
}
