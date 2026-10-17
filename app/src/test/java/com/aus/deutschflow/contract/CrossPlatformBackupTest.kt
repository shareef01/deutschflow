package com.aus.deutschflow.contract

import com.aus.deutschflow.data.local.backup.BackupCrypto
import com.aus.deutschflow.data.local.backup.BackupException
import com.aus.deutschflow.data.local.backup.BackupManager
import com.aus.deutschflow.data.local.entities.germanKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals("die straße", strasse.getString("germanTextKey"))
        assertEquals(germanKey("die Straße"), strasse.getString("germanTextKey"))

        val gross = vocab.getJSONObject(1)
        assertEquals("groß", gross.getString("germanText"))
        assertEquals("groß", gross.getString("germanTextKey"))
        assertEquals(germanKey("groß"), gross.getString("germanTextKey"))

        val suess = vocab.getJSONObject(2)
        assertEquals("süß", suess.getString("germanText"))
        assertEquals("sueß", suess.getString("germanTextKey"))
        assertEquals(germanKey("süß"), suess.getString("germanTextKey"))

        val events = androidBackup.getJSONArray("reviewEvents")
        assertEquals(3, events.length())
        assertEquals(101, events.getJSONObject(0).getInt("vocabularyId"))

        val stats = androidBackup.getJSONArray("userStats").getJSONObject(0)
        assertEquals(350, stats.getInt("xp"))
        assertEquals(8, stats.getInt("streak"))
    }

    @Test
    fun `canonical German key preserves distinctions between linguistic pairs like Maße and Masse`() {
        assertNotEquals("Maße and Masse must never share identical canonical identity keys", germanKey("Maße"), germanKey("Masse"))
        assertNotEquals("Buße and Busse must never share identical canonical identity keys", germanKey("Buße"), germanKey("Busse"))
        assertNotEquals("Straße and Strasse must never share identical canonical identity keys", germanKey("Straße"), germanKey("Strasse"))
        assertEquals("maße", germanKey("Maße"))
        assertEquals("masse", germanKey("Masse"))
    }

    @Test
    fun `canonical German key handles composed and decomposed Unicode identically`() {
        val composed = "Übung"
        val decomposed = "U\u0308bung"
        assertEquals(germanKey(composed), germanKey(decomposed))
        assertEquals("uebung", germanKey(composed))

        val composedA = "Äpfel"
        val decomposedA = "A\u0308pfel"
        assertEquals(germanKey(composedA), germanKey(decomposedA))
        assertEquals("aepfel", germanKey(composedA))
    }

    @Test
    fun `accepts nullable and unresolvable vocabularyId in reviewEvents without throwing InvalidFormat`() {
        val backupWithNullable = JSONObject(vectors.getJSONObject("webPlaintext").toString()).apply {
            val revEvents = getJSONArray("reviewEvents")
            revEvents.put(JSONObject().apply {
                put("vocabularyId", JSONObject.NULL) // Nullable reference from Web
                put("rating", "AGAIN")
                put("scheduledDays", 0)
                put("actualDays", 0)
                put("reviewedAtTimestamp", 1729123200000L)
                put("isExtraPractice", false)
                put("remoteId", "f1111111-1111-4111-8111-111111111111")
            })
            revEvents.put(JSONObject().apply {
                put("vocabularyId", 9999) // Unresolvable legacy/orphaned ID
                put("rating", "HARD")
                put("scheduledDays", 1)
                put("actualDays", 1)
                put("reviewedAtTimestamp", 1729123200000L)
                put("isExtraPractice", false)
                put("remoteId", "f2222222-2222-4222-8222-222222222222")
            })
        }

        // Must validate successfully without throwing InvalidFormat
        BackupManager.validateBackupJson(backupWithNullable)
        val events = backupWithNullable.getJSONArray("reviewEvents")
        assertEquals(4, events.length())
    }
}
