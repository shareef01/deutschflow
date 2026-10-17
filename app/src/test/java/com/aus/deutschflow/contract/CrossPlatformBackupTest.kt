package com.aus.deutschflow.contract

import com.aus.deutschflow.data.local.backup.BackupCrypto
import com.aus.deutschflow.data.local.backup.BackupException
import com.aus.deutschflow.data.local.backup.BackupManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStreamReader

class CrossPlatformBackupTest {

    private lateinit var vectors: JSONObject
    private lateinit var passphraseChars: CharArray
    private lateinit var wrongPassphraseChars: CharArray

    @Before
    fun setUp() {
        val stream = javaClass.classLoader?.getResourceAsStream("cross-platform-backup-vectors.json")
            ?: error("Missing cross-platform-backup-vectors.json in test resources")
        val content = InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
        vectors = JSONObject(content)
        passphraseChars = vectors.getString("passphrase").toCharArray()
        wrongPassphraseChars = vectors.getString("wrongPassphrase").toCharArray()
    }

    @Test
    fun `web plaintext export passes Android BackupManager validation`() {
        val webBackup = vectors.getJSONObject("webPlaintext")
        // validateBackupJson checks version, format, arrays, types, and schema compliance
        BackupManager.validateBackupJson(webBackup)

        assertEquals("deutschflow-library", webBackup.getString("format"))
        assertEquals(2, webBackup.getInt("version"))

        val vocab = webBackup.getJSONArray("vocabulary")
        assertEquals(2, vocab.length())

        val words = (0 until vocab.length()).map { vocab.getJSONObject(it).getString("germanText") }
        assertTrue("Contains die Brücke with umlaut", words.contains("die Brücke"))
        assertTrue("Contains Fußball with eszett", words.contains("Fußball"))

        val stats = webBackup.getJSONArray("userStats").getJSONObject(0)
        assertEquals(420, stats.getInt("xp"))
        assertEquals(14, stats.getInt("streak"))

        val events = webBackup.getJSONArray("reviewEvents")
        assertEquals(2, events.length())
        assertEquals("GOOD", events.getJSONObject(0).getString("rating"))
        assertEquals("EASY", events.getJSONObject(1).getString("rating"))
    }

    @Test
    fun `decrypts encrypted Web export on Android with matching passphrase`() {
        val encryptedJson = vectors.getJSONObject("webEncrypted")
        assertTrue(BackupCrypto.isEncryptedBackup(encryptedJson))

        val decrypted = BackupCrypto.decrypt(encryptedJson, passphraseChars)
        val decryptedObj = JSONObject(decrypted)
        BackupManager.validateBackupJson(decryptedObj)

        val vocab = decryptedObj.getJSONArray("vocabulary")
        assertEquals(2, vocab.length())
        assertEquals("die Brücke", vocab.getJSONObject(0).getString("germanText"))
        assertEquals("Fußball", vocab.getJSONObject(1).getString("germanText"))
    }

    @Test
    fun `decrypts encrypted Android export on Android with matching passphrase`() {
        val encryptedJson = vectors.getJSONObject("androidEncrypted")
        assertTrue(BackupCrypto.isEncryptedBackup(encryptedJson))

        val decrypted = BackupCrypto.decrypt(encryptedJson, passphraseChars)
        val decryptedObj = JSONObject(decrypted)
        BackupManager.validateBackupJson(decryptedObj)

        val vocab = decryptedObj.getJSONArray("vocabulary")
        assertEquals(3, vocab.length())
        val words = (0 until vocab.length()).map { vocab.getJSONObject(it).getString("germanText") }
        assertTrue(words.contains("die Straße"))
        assertTrue(words.contains("groß"))
        assertTrue(words.contains("süß"))
    }

    @Test
    fun `rejects decryption with wrong passphrase`() {
        val encryptedJson = vectors.getJSONObject("androidEncrypted")
        assertThrows(BackupException.WrongPassphrase::class.java) {
            BackupCrypto.decrypt(encryptedJson, wrongPassphraseChars)
        }
    }

    @Test
    fun `rejects decryption with corrupted ciphertext`() {
        val corruptedJson = vectors.getJSONObject("corruptedEncrypted")
        assertThrows(BackupException.WrongPassphrase::class.java) {
            BackupCrypto.decrypt(corruptedJson, passphraseChars)
        }
    }

    @Test
    fun `preserves German Unicode special characters, review events, and stats across platforms`() {
        val androidBackup = vectors.getJSONObject("androidPlaintext")
        BackupManager.validateBackupJson(androidBackup)

        val vocab = androidBackup.getJSONArray("vocabulary")
        assertEquals(3, vocab.length())

        val strasse = vocab.getJSONObject(0)
        assertEquals("die Straße", strasse.getString("germanText"))
        assertEquals("Straßen", strasse.getString("plural"))
        assertEquals("die strasse", strasse.getString("germanTextKey"))

        val events = androidBackup.getJSONArray("reviewEvents")
        assertEquals(3, events.length())
        assertEquals(101, events.getJSONObject(0).getInt("vocabularyId"))

        val stats = androidBackup.getJSONArray("userStats").getJSONObject(0)
        assertEquals(350, stats.getInt("xp"))
        assertEquals(8, stats.getInt("streak"))
    }
}
