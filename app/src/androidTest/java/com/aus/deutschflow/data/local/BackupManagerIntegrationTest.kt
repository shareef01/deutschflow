package com.aus.deutschflow.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.backup.BackupException
import com.aus.deutschflow.data.local.backup.BackupManager
import com.aus.deutschflow.data.local.backup.CURRENT_BACKUP_VERSION
import com.aus.deutschflow.data.local.backup.MAX_BACKUP_FILE_BYTES
import com.aus.deutschflow.data.local.entities.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupManagerIntegrationTest {

    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase
    private lateinit var sourceBackupManager: BackupManager
    private lateinit var targetBackupManager: BackupManager

    private val testPassphrase = "Sicheres-Master-Passwort-2026!".toCharArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        sourceBackupManager = BackupManager(
            database = sourceDb,
            vocabularyDao = sourceDb.vocabularyDao(),
            transcriptDao = sourceDb.transcriptDao(),
            userStatsDao = sourceDb.userStatsDao(),
            activityDao = sourceDb.activityDao(),
            roleplayDao = sourceDb.roleplayDao(),
            reviewEventDao = sourceDb.reviewEventDao()
        )

        targetBackupManager = BackupManager(
            database = targetDb,
            vocabularyDao = targetDb.vocabularyDao(),
            transcriptDao = targetDb.transcriptDao(),
            userStatsDao = targetDb.userStatsDao(),
            activityDao = targetDb.activityDao(),
            roleplayDao = targetDb.roleplayDao(),
            reviewEventDao = targetDb.reviewEventDao()
        )
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    @Test
    fun roundTripEncryptedBackup_restoresExactDataAndRelationships() = runBlocking {
        val now = System.currentTimeMillis()
        val vocabDao = sourceDb.vocabularyDao()

        // 1. Populate Vocabulary: include duplicate-looking but distinct German words
        val masse = VocabularyEntity(
            germanText = "Masse",
            germanTextKey = germanKey("Masse"),
            englishTranslation = "mass, dough",
            timestamp = now - 100000,
            interval = 6,
            easeFactor = 2.4f,
            reviewCount = 3,
            nextReview = now + 86400000L
        )
        val masseWord = VocabularyEntity(
            germanText = "Maße",
            germanTextKey = germanKey("Maße"),
            englishTranslation = "measurements, dimensions",
            timestamp = now - 90000,
            interval = 1,
            easeFactor = 2.5f,
            reviewCount = 1,
            nextReview = now + 43200000L
        )
        val strasse = VocabularyEntity(
            germanText = "Straße",
            germanTextKey = germanKey("Straße"),
            englishTranslation = "street",
            timestamp = now - 80000,
            interval = 15,
            easeFactor = 2.6f,
            reviewCount = 5,
            nextReview = now + 200000000L
        )
        vocabDao.insertVocabulary(masse)
        vocabDao.insertVocabulary(masseWord)
        vocabDao.insertVocabulary(strasse)

        val persistedMasse = vocabDao.findByGermanText("Masse")!!
        val persistedMasseWord = vocabDao.findByGermanText("Maße")!!
        val persistedStrasse = vocabDao.findByGermanText("Straße")!!

        assertNotEquals(persistedMasse.id, persistedMasseWord.id)
        assertNotEquals(persistedMasse.germanTextKey, persistedMasseWord.germanTextKey)

        // 2. Transcripts
        val transcriptDao = sourceDb.transcriptDao()
        val transcript = TranscriptEntity(
            fullText = "Guten Tag, wie geht es Ihnen heute?",
            translation = "Good day, how are you doing today?",
            analysisJson = """{"grammar":"Formal greeting"}""",
            timestamp = now - 50000,
            remoteId = UUID.randomUUID().toString()
        )
        transcriptDao.insertTranscript(transcript)

        // 3. User stats & Activity log
        val todayStr = LocalDate.now(ZoneOffset.UTC).toString()
        val yesterdayStr = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()
        sourceDb.activityDao().insertOrUpdate(ActivityEntity(date = yesterdayStr, xpGained = 50, timestamp = now - 86400000L))
        sourceDb.activityDao().insertOrUpdate(ActivityEntity(date = todayStr, xpGained = 75, timestamp = now))
        sourceDb.userStatsDao().insertOrUpdate(UserStatsEntity(id = 1, xp = 125, streak = 2, lastActivityTimestamp = now))

        // 4. Roleplay Conversation
        val roleplayDao = sourceDb.roleplayDao()
        roleplayDao.insert(RoleplayMessageEntity(position = 0, scenario = "bakery", role = "assistant", content = "Guten Morgen! Was darf es sein?", timestamp = now - 40000))
        roleplayDao.insert(RoleplayMessageEntity(position = 1, scenario = "bakery", role = "user", content = "Ich möchte zwei Brötchen, bitte.", timestamp = now - 30000))

        // 5. Review Events linking to Vocabulary IDs
        val reviewDao = sourceDb.reviewEventDao()
        reviewDao.insert(
            ReviewEventEntity(
                vocabularyId = persistedMasse.id,
                rating = "GOOD",
                scheduledDays = 6,
                actualDays = 6,
                reviewedAtTimestamp = now - 10000,
                isExtraPractice = false,
                remoteId = UUID.randomUUID().toString()
            )
        )
        reviewDao.insert(
            ReviewEventEntity(
                vocabularyId = persistedMasseWord.id,
                rating = "EASY",
                scheduledDays = 1,
                actualDays = 1,
                reviewedAtTimestamp = now - 5000,
                isExtraPractice = false,
                remoteId = UUID.randomUUID().toString()
            )
        )

        // Export encrypted backup via stream
        val outStream = ByteArrayOutputStream()
        sourceBackupManager.exportToStream(outStream, testPassphrase)
        val encryptedPayload = outStream.toByteArray()
        assertTrue("Exported payload must not be empty", encryptedPayload.isNotEmpty())

        // Ensure no API keys or plaintext secrets appear in encrypted stream
        val rawExportStr = String(encryptedPayload, Charsets.UTF_8)
        assertFalse("Encrypted backup must not expose plaintext German text", rawExportStr.contains("Guten Tag"))
        assertFalse("Backup must not contain api_key", rawExportStr.contains("api_key"))
        assertFalse("Backup must not contain gsk_", rawExportStr.contains("gsk_"))

        // Import into clean target database
        val inStream = ByteArrayInputStream(encryptedPayload)
        val result = targetBackupManager.importFromStream(inStream, testPassphrase)

        assertEquals("Should add 3 vocabulary items", 3, result.vocabularyAdded)
        assertEquals("Should merge 0 vocabulary items in fresh DB", 0, result.vocabularyMerged)
        assertEquals("Should add 1 transcript", 1, result.transcriptsAdded)
        assertEquals("Should add 2 review events", 2, result.reviewEventsAdded)

        // Verify Vocabulary preservation
        val targetVocabDao = targetDb.vocabularyDao()
        val restoredMasse = targetVocabDao.findByGermanText("Masse")
        val restoredMasseWord = targetVocabDao.findByGermanText("Maße")
        val restoredStrasse = targetVocabDao.findByGermanText("Straße")

        assertNotNull("Masse must exist", restoredMasse)
        assertNotNull("Maße must exist", restoredMasseWord)
        assertNotNull("Straße must exist", restoredStrasse)

        assertEquals("Masse translation preserved", "mass, dough", restoredMasse!!.englishTranslation)
        assertEquals("Maße translation preserved", "measurements, dimensions", restoredMasseWord!!.englishTranslation)
        assertEquals("Masse interval preserved", 6, restoredMasse.interval)
        assertEquals("Maße interval preserved", 1, restoredMasseWord.interval)

        // Verify Review Events re-mapped to new persisted IDs
        val targetReviews = targetDb.reviewEventDao().getAllEventsList()
        assertEquals(2, targetReviews.size)

        val masseReview = targetReviews.find { it.vocabularyId == restoredMasse.id }
        val masseWordReview = targetReviews.find { it.vocabularyId == restoredMasseWord.id }

        assertNotNull("Review for Masse must be linked to restored Masse ID", masseReview)
        assertEquals("GOOD", masseReview!!.rating)
        assertNotNull("Review for Maße must be linked to restored Maße ID", masseWordReview)
        assertEquals("EASY", masseWordReview!!.rating)

        // Verify Transcripts
        val targetTranscripts = targetDb.transcriptDao().getAllTranscriptsList()
        assertEquals(1, targetTranscripts.size)
        assertEquals("Guten Tag, wie geht es Ihnen heute?", targetTranscripts[0].fullText)
        assertEquals("Good day, how are you doing today?", targetTranscripts[0].translation)

        // Verify User Stats & Streak
        val targetStats = targetDb.userStatsDao().getUserStatsOnce()
        assertNotNull(targetStats)
        assertEquals(125, targetStats!!.xp)
        assertEquals(2, targetStats.streak)

        // Verify Roleplay Messages
        val targetRoleplay = targetDb.roleplayDao().getConversation()
        assertEquals(2, targetRoleplay.size)
        assertEquals("Guten Morgen! Was darf es sein?", targetRoleplay[0].content)
        assertEquals("Ich möchte zwei Brötchen, bitte.", targetRoleplay[1].content)
    }

    @Test
    fun importIntoPopulatedDatabase_mergesCorrectlyAndIsIdempotent() = runBlocking {
        val now = System.currentTimeMillis()
        val vocabDao = sourceDb.vocabularyDao()

        vocabDao.insertVocabulary(
            VocabularyEntity(
                germanText = "Haus",
                germanTextKey = germanKey("Haus"),
                englishTranslation = "house",
                timestamp = now,
                interval = 3,
                easeFactor = 2.5f,
                reviewCount = 2,
                nextReview = now + 86400000L
            )
        )

        val exportJson = sourceBackupManager.exportBackup(passphrase = null)

        // Pre-populate targetDb with Haus but with older interval
        targetDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "Haus",
                germanTextKey = germanKey("Haus"),
                englishTranslation = "building, house",
                timestamp = now - 50000,
                interval = 1,
                easeFactor = 2.5f,
                reviewCount = 1,
                nextReview = now
            )
        )

        // First import -> merge
        val result1 = targetBackupManager.importBackup(exportJson, passphrase = null)
        assertEquals(0, result1.vocabularyAdded)
        assertEquals(1, result1.vocabularyMerged)

        val merged = targetDb.vocabularyDao().findByGermanText("Haus")!!
        assertEquals("Haus interval should take incoming higher review progress", 3, merged.interval)

        // Second import -> idempotent, no duplicates
        val result2 = targetBackupManager.importBackup(exportJson, passphrase = null)
        assertEquals(0, result2.vocabularyAdded)
        assertEquals(1, result2.vocabularyMerged)

        val totalVocab = targetDb.vocabularyDao().getAllVocabularyList()
        assertEquals("Should still only have 1 Haus entry", 1, totalVocab.size)
    }

    @Test
    fun wrongPassphrase_throwsWrongPassphraseException() = runBlocking {
        sourceDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(germanText = "Baum", germanTextKey = "baum", englishTranslation = "tree")
        )
        val encrypted = sourceBackupManager.exportBackup("Correct-Passphrase".toCharArray())

        try {
            targetBackupManager.importBackup(encrypted, "Wrong-Passphrase".toCharArray())
            fail("Expected BackupException.WrongPassphrase")
        } catch (e: BackupException.WrongPassphrase) {
            // expected
        }

        assertTrue("Target database must remain empty", targetDb.vocabularyDao().getAllVocabularyList().isEmpty())
    }

    @Test
    fun truncatedEncryptedPayload_throwsCorruptedDataOrWrongPassphrase() = runBlocking {
        sourceDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(germanText = "Auto", germanTextKey = "auto", englishTranslation = "car")
        )
        val encrypted = sourceBackupManager.exportBackup("Valid-Passphrase".toCharArray())
        val json = JSONObject(encrypted)
        val cipherText = json.getString("ciphertext")
        // Truncate ciphertext in half
        json.put("ciphertext", cipherText.substring(0, cipherText.length / 2))

        try {
            targetBackupManager.importBackup(json.toString(), "Valid-Passphrase".toCharArray())
            fail("Expected corrupted data or passphrase error")
        } catch (e: BackupException) {
            assertTrue(e is BackupException.CorruptedData || e is BackupException.WrongPassphrase)
        }
    }

    @Test
    fun invalidJson_throwsInvalidFormat() = runBlocking {
        try {
            targetBackupManager.importBackup("this is clearly not json", null)
            fail("Expected BackupException.InvalidFormat")
        } catch (e: BackupException.InvalidFormat) {
            // expected
        }
    }

    @Test
    fun unsupportedSchemaVersion_throwsUnsupportedVersion() = runBlocking {
        val validJson = JSONObject(sourceBackupManager.exportBackup(null))
        validJson.put("version", CURRENT_BACKUP_VERSION + 99)

        try {
            targetBackupManager.importBackup(validJson.toString(), null)
            fail("Expected BackupException.UnsupportedVersion")
        } catch (e: BackupException.UnsupportedVersion) {
            // expected
        }
    }

    @Test
    fun oversizedBackup_throwsCapacityExceeded() = runBlocking {
        val hugeBuilder = StringBuilder("{\"format\":\"deutschflow-library\",\"version\":2,\"payload\":\"")
        // Generate string larger than 10MB
        val padding = "A".repeat(1024 * 1024)
        for (i in 0..10) {
            hugeBuilder.append(padding)
        }
        hugeBuilder.append("\"}")

        try {
            targetBackupManager.importBackup(hugeBuilder.toString(), null)
            fail("Expected BackupException.CapacityExceeded")
        } catch (e: BackupException.CapacityExceeded) {
            // expected
        }
    }

    @Test
    fun atomicRollback_onFailureDoesNotPartiallyCommit() = runBlocking {
        // Pre-populate target with 1 item
        targetDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(germanText = "Schule", germanTextKey = "schule", englishTranslation = "school")
        )

        // Construct invalid backup with valid vocabulary but invalid reviewEvent
        val backupJson = JSONObject().apply {
            put("format", "deutschflow-library")
            put("version", CURRENT_BACKUP_VERSION)
            put("vocabulary", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 1)
                    put("germanText", "Buch")
                    put("englishTranslation", "book")
                })
            })
            put("transcripts", org.json.JSONArray())
            put("userStats", org.json.JSONArray())
            put("activityLog", org.json.JSONArray())
            put("reviewEvents", org.json.JSONArray().apply {
                // Invalid rating that fails validation or causes failure
                put(JSONObject().apply {
                    put("vocabularyId", 1)
                    put("rating", "INVALID_RATING")
                    put("scheduledDays", 5)
                    put("actualDays", 5)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", UUID.randomUUID().toString())
                })
            })
        }

        try {
            targetBackupManager.importBackup(backupJson.toString(), null)
            fail("Expected validation failure")
        } catch (e: BackupException.InvalidFormat) {
            // expected
        }

        // Verify target database was not modified at all ("Buch" must NOT be in DB)
        val vocab = targetDb.vocabularyDao().getAllVocabularyList()
        assertEquals(1, vocab.size)
        assertEquals("Schule", vocab[0].germanText)
    }
}
