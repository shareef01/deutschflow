package com.aus.deutschflow.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.data.local.backup.BackupException
import com.aus.deutschflow.data.local.backup.BackupManager
import com.aus.deutschflow.data.local.backup.CURRENT_BACKUP_VERSION
import com.aus.deutschflow.data.local.backup.MAX_BACKUP_FILE_BYTES
import com.aus.deutschflow.data.local.backup.MAX_PLAINTEXT_BACKUP_BYTES
import com.aus.deutschflow.data.local.backup.MAX_ENCRYPTED_BACKUP_FILE_BYTES
import com.aus.deutschflow.data.local.backup.MAX_STREAM_READ_BYTES
import com.aus.deutschflow.data.local.backup.MAX_BASE64_CIPHERTEXT_LENGTH
import com.aus.deutschflow.data.local.dao.ReviewEventDao
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
import java.io.InputStream
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
                nextReview = now + 86400000L * 3
            )
        )
        val persistedHaus = vocabDao.findByGermanText("Haus")!!
        sourceDb.reviewEventDao().insert(
            ReviewEventEntity(
                vocabularyId = persistedHaus.id,
                rating = "GOOD",
                scheduledDays = 3,
                actualDays = 3,
                reviewedAtTimestamp = now,
                isExtraPractice = false,
                remoteId = UUID.randomUUID().toString()
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
                nextReview = now + 86400000L * 3
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
        // Generate string larger than 50MB
        val padding = "A".repeat(1024 * 1024)
        for (i in 0..50) {
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

    @Test
    fun importReviewEvents_withConflictingTargetDatabaseIds_mapsCleanlyAndPreventsCrossAssignment() = runBlocking {
        // Pre-populate target database with a word that receives target ID 1 ("Apfel")
        targetDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "Apfel",
                germanTextKey = germanKey("Apfel"),
                englishTranslation = "apple",
                timestamp = System.currentTimeMillis() - 50000
            )
        )
        val apple = targetDb.vocabularyDao().findByGermanText("Apfel")
        assertNotNull(apple)
        val appleId = apple!!.id

        // Construct a backup where:
        // Backup vocabulary has "Banane" with backup ID = 1 (conflicts with target ID 1!)
        // Backup reviewEvents has a review event for backup ID = 1 ("Banane").
        // In the target database, "Banane" will be assigned target ID 2 (since ID 1 is occupied by "Apfel").
        // The review event MUST be assigned to target ID 2 ("Banane"), NEVER to target ID 1 ("Apfel")!
        val reviewRemoteId = UUID.randomUUID().toString()
        val backupJson = JSONObject().apply {
            put("format", "deutschflow-library")
            put("version", CURRENT_BACKUP_VERSION)
            put("vocabulary", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 1) // Backup ID 1
                    put("germanText", "Banane")
                    put("englishTranslation", "banana")
                    put("interval", 2)
                    put("easeFactor", 2.5f)
                    put("reviewCount", 1)
                    put("nextReview", System.currentTimeMillis() + 86400000L)
                })
            })
            put("transcripts", org.json.JSONArray())
            put("userStats", org.json.JSONArray())
            put("activityLog", org.json.JSONArray())
            put("reviewEvents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("vocabularyId", 1) // Belongs to "Banane", backup ID 1
                    put("rating", "GOOD")
                    put("scheduledDays", 2)
                    put("actualDays", 2)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", reviewRemoteId)
                })
            })
        }

        val result = targetBackupManager.importBackup(backupJson.toString(), null)
        assertEquals(1, result.vocabularyAdded)
        assertEquals(1, result.reviewEventsAdded)

        // Verify target database:
        // Apfel has ID 1, Banane has ID 2
        val persistedApple = targetDb.vocabularyDao().findById(appleId)
        assertNotNull(persistedApple)
        assertEquals("Apfel", persistedApple?.germanText)

        val banana = targetDb.vocabularyDao().findByGermanText("Banane")
        assertNotNull(banana)
        assertNotEquals(appleId, banana!!.id)

        // The imported review event MUST link to Banane's ID, NOT Apfel's ID!
        val importedReview = targetDb.reviewEventDao().findByRemoteId(reviewRemoteId)
        assertNotNull(importedReview)
        assertEquals(banana.id, importedReview!!.vocabularyId)
        assertNotEquals(appleId, importedReview.vocabularyId)
    }

    @Test
    fun importReviewEvents_withNewVocabulary_mapsCleanlyToTargetIdWithoutCrossAssignment() = runBlocking {
        // Pre-populate target database with a word that receives target ID 1 ("Apfel")
        targetDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "Apfel",
                germanTextKey = germanKey("Apfel"),
                englishTranslation = "apple",
                timestamp = System.currentTimeMillis() - 50000
            )
        )
        val apple = targetDb.vocabularyDao().findByGermanText("Apfel")
        assertNotNull(apple)
        val appleId = apple!!.id

        val reviewRemoteId = UUID.randomUUID().toString()
        val backupJson = JSONObject().apply {
            put("format", "deutschflow-library")
            put("version", CURRENT_BACKUP_VERSION)
            put("vocabulary", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 2)
                    put("germanText", "Kirsche")
                    put("englishTranslation", "cherry")
                    put("interval", 1)
                    put("easeFactor", 2.5f)
                    put("reviewCount", 1)
                    put("nextReview", System.currentTimeMillis() + 86400000L)
                })
            })
            put("transcripts", org.json.JSONArray())
            put("userStats", org.json.JSONArray())
            put("activityLog", org.json.JSONArray())
            put("reviewEvents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("vocabularyId", 2)
                    put("rating", "GOOD")
                    put("scheduledDays", 2)
                    put("actualDays", 2)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", reviewRemoteId)
                })
            })
        }

        val result = targetBackupManager.importBackup(backupJson.toString(), null)
        assertEquals(1, result.vocabularyAdded)
        assertEquals(1, result.reviewEventsAdded)

        // Ensure Apfel has no reviews attached to it!
        val allReviews = targetDb.reviewEventDao().getAllEventsList()
        for (rev in allReviews) {
            assertNotEquals("Review event must never be cross-assigned to pre-existing Apfel", appleId, rev.vocabularyId)
        }
    }

    @Test
    fun importReviewEvents_withUnmappedReviewEvent_recoversToArchivedPlaceholderAndNeverCrossAssignsToTargetId() = runBlocking {
        // Pre-populate target database with a word that receives target ID 1 ("Apfel")
        targetDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "Apfel",
                germanTextKey = germanKey("Apfel"),
                englishTranslation = "apple",
                timestamp = System.currentTimeMillis() - 50000
            )
        )
        val apple = targetDb.vocabularyDao().findByGermanText("Apfel")
        assertNotNull(apple)
        val appleId = apple!!.id

        val orphanRemoteId1 = UUID.randomUUID().toString()
        val orphanRemoteId2 = UUID.randomUUID().toString()

        val backupJson = JSONObject().apply {
            put("format", "deutschflow-library")
            put("version", CURRENT_BACKUP_VERSION)
            put("vocabulary", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 2)
                    put("germanText", "Kirsche")
                    put("englishTranslation", "cherry")
                    put("interval", 1)
                    put("easeFactor", 2.5f)
                    put("reviewCount", 1)
                    put("nextReview", System.currentTimeMillis() + 86400000L)
                })
            })
            put("transcripts", org.json.JSONArray())
            put("userStats", org.json.JSONArray())
            put("activityLog", org.json.JSONArray())
            put("reviewEvents", org.json.JSONArray().apply {
                // Orphan 1: references ID 99999 which does NOT exist in backup vocabulary
                put(JSONObject().apply {
                    put("vocabularyId", 99999)
                    put("rating", "GOOD")
                    put("scheduledDays", 2)
                    put("actualDays", 2)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", orphanRemoteId1)
                })
                // Orphan 2: references ID 1 (matches targetDb's Apfel ID 1, but is NOT in the backup vocabulary)
                put(JSONObject().apply {
                    put("vocabularyId", 1)
                    put("rating", "HARD")
                    put("scheduledDays", 1)
                    put("actualDays", 1)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", orphanRemoteId2)
                })
            })
        }

        val result = targetBackupManager.importBackup(backupJson.toString(), null)
        assertEquals(1, result.vocabularyAdded)
        assertEquals(2, result.reviewEventsAdded)

        val allReviews = targetDb.reviewEventDao().getAllEventsList()
        assertEquals(2, allReviews.size)

        val archivedPlaceholder = targetDb.vocabularyDao().findByKey(ARCHIVED_VOCABULARY_KEY)
        assertNotNull("Archived placeholder must be created for recovered reviews", archivedPlaceholder)

        allReviews.forEach { review ->
            assertNotEquals("Orphan review event must NEVER be cross-assigned to Apfel ID", appleId, review.vocabularyId)
            assertEquals("Unmapped review must be linked to archived placeholder ID", archivedPlaceholder!!.id, review.vocabularyId)
        }

        val kirsche = targetDb.vocabularyDao().findByGermanText("Kirsche")
        assertNotNull("Kirsche must be inserted", kirsche)
        val remainingApple = targetDb.vocabularyDao().findByGermanText("Apfel")
        assertNotNull(remainingApple)
        assertEquals(appleId, remainingApple!!.id)
    }

    private fun createPaddedStream(prefix: ByteArray, totalBytes: Long): InputStream {
        return object : InputStream() {
            private var bytesRead: Long = 0

            override fun read(): Int {
                if (bytesRead >= totalBytes) return -1
                val b = if (bytesRead < prefix.size) {
                    prefix[bytesRead.toInt()].toInt() and 0xFF
                } else {
                    ' '.code
                }
                bytesRead++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead >= totalBytes) return -1
                val available = minOf(len.toLong(), totalBytes - bytesRead).toInt()
                for (i in 0 until available) {
                    val idx = bytesRead + i
                    b[off + i] = if (idx < prefix.size) {
                        prefix[idx.toInt()]
                    } else {
                        ' '.code.toByte()
                    }
                }
                bytesRead += available
                return available
            }
        }
    }

    @Test
    fun importFromStream_withExactPlaintextLimit_isReadAndProcessedWithoutCapacityException() {
        runBlocking {
            // Build a valid minimal backup and pad with spaces to exactly MAX_PLAINTEXT_BACKUP_BYTES (50 MiB)
            val baseJson = JSONObject().apply {
                put("format", "deutschflow-library")
                put("version", CURRENT_BACKUP_VERSION)
                put("vocabulary", org.json.JSONArray())
                put("transcripts", org.json.JSONArray())
                put("userStats", org.json.JSONArray())
                put("activityLog", org.json.JSONArray())
                put("reviewEvents", org.json.JSONArray())
            }.toString()

            val baseBytes = baseJson.toByteArray(Charsets.UTF_8)
            val stream = createPaddedStream(baseBytes, MAX_PLAINTEXT_BACKUP_BYTES.toLong())

            val result = targetBackupManager.importFromStream(stream, null)
            assertNotNull(result)
        }
    }

    @Test
    fun importFromStream_withExactEncryptedStreamLimit_isReadAndProcessedWithoutCapacityException() {
        runBlocking {
            // Build a valid encrypted backup envelope and pad with spaces to exactly MAX_STREAM_READ_BYTES (70 MiB)
            sourceDb.vocabularyDao().insertVocabulary(
                VocabularyEntity(
                    germanText = "Grenze",
                    germanTextKey = germanKey("Grenze"),
                    englishTranslation = "boundary, border",
                    timestamp = System.currentTimeMillis()
                )
            )
            val encryptedEnvelope = sourceBackupManager.exportBackup(testPassphrase)
            val baseBytes = encryptedEnvelope.toByteArray(Charsets.UTF_8)
            val stream = createPaddedStream(baseBytes, MAX_STREAM_READ_BYTES.toLong())

            val result = targetBackupManager.importFromStream(stream, testPassphrase)
            assertNotNull(result)
            assertEquals(1, result.vocabularyAdded)
        }
    }

    @Test
    fun importFromStream_withOneByteOverStreamLimit_throwsCapacityExceededWithoutExhaustingMemory() {
        runBlocking {
            val baseJson = JSONObject().apply {
                put("format", "deutschflow-library")
                put("version", CURRENT_BACKUP_VERSION)
                put("vocabulary", org.json.JSONArray())
                put("transcripts", org.json.JSONArray())
                put("userStats", org.json.JSONArray())
                put("activityLog", org.json.JSONArray())
            }.toString()

            val baseBytes = baseJson.toByteArray(Charsets.UTF_8)
            // One byte over MAX_STREAM_READ_BYTES (70 MiB + 1 byte)
            val stream = createPaddedStream(baseBytes, MAX_STREAM_READ_BYTES.toLong() + 1L)

            assertThrows(BackupException.CapacityExceeded::class.java) {
                runBlocking {
                    targetBackupManager.importFromStream(stream)
                }
            }
        }
    }

    @Test
    fun importBackup_unencryptedPlaintextOneByteOverLimit_throwsCapacityExceeded() {
        runBlocking {
            // Unencrypted plaintext exceeding MAX_PLAINTEXT_BACKUP_BYTES (50 MiB) must be rejected
            val baseJson = JSONObject().apply {
                put("format", "deutschflow-library")
                put("version", CURRENT_BACKUP_VERSION)
                put("vocabulary", org.json.JSONArray())
                put("transcripts", org.json.JSONArray())
                put("userStats", org.json.JSONArray())
                put("activityLog", org.json.JSONArray())
            }.toString()

            val baseBytes = baseJson.toByteArray(Charsets.UTF_8)
            // Stream of MAX_PLAINTEXT_BACKUP_BYTES + 1L is within stream read limit (70 MiB),
            // but importBackup validates unencrypted payload <= MAX_PLAINTEXT_BACKUP_BYTES (50 MiB)
            val stream = createPaddedStream(baseBytes, MAX_PLAINTEXT_BACKUP_BYTES.toLong() + 1L)

            assertThrows(BackupException.CapacityExceeded::class.java) {
                runBlocking {
                    targetBackupManager.importFromStream(stream, null)
                }
            }
        }
    }

    @Test
    fun importFromStream_withVeryLargeStreamingInput_abortsEarlyWithoutCrashingOrOOM() {
        runBlocking {
            // Stream generator that delivers data exceeding MAX_STREAM_READ_BYTES without allocating a buffer
            val hugeStream = object : InputStream() {
                private var bytesEmitted = 0
                private val maxEmit = MAX_STREAM_READ_BYTES + 10 * 1024 * 1024

                override fun read(): Int {
                    if (bytesEmitted++ < maxEmit) return ' '.code
                    return -1
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (bytesEmitted >= maxEmit) return -1
                    val toRead = minOf(len, maxEmit - bytesEmitted)
                    for (i in 0 until toRead) {
                        b[off + i] = ' '.code.toByte()
                    }
                    bytesEmitted += toRead
                    return toRead
                }
            }

            assertThrows(BackupException.CapacityExceeded::class.java) {
                runBlocking {
                    targetBackupManager.importFromStream(hugeStream)
                }
            }
        }
    }

    @Test
    fun importFromStream_withTruncatedStream_throwsInvalidFormat() {
        runBlocking {
            val truncatedJson = "{\"format\": \"deutschflow-library\", \"version\": 2, \"vocabulary\": ["
            val stream = ByteArrayInputStream(truncatedJson.toByteArray(Charsets.UTF_8))

            assertThrows(BackupException.InvalidFormat::class.java) {
                runBlocking {
                    targetBackupManager.importFromStream(stream)
                }
            }
        }
    }

    @Test
    fun importFromStream_withMalformedUtf8_throwsInvalidFormat() {
        runBlocking {
            // Byte sequence containing invalid UTF-8 (e.g. 0xFE, 0xFF)
            val malformedUtf8 = byteArrayOf(0x7B, 0x22, 0x66, 0x6F, 0x72, 0x6D, 0x61, 0x74, 0x22, 0x3A, 0xFE.toByte(), 0xFF.toByte(), 0x7D)
            val stream = ByteArrayInputStream(malformedUtf8)

            assertThrows(BackupException.InvalidFormat::class.java) {
                runBlocking {
                    targetBackupManager.importFromStream(stream)
                }
            }
        }
    }

    @Test
    fun importFromStream_withEmptyStream_throwsInvalidFormat() {
        runBlocking {
            val emptyStream = ByteArrayInputStream(ByteArray(0))

            assertThrows(BackupException.InvalidFormat::class.java) {
                runBlocking {
                    targetBackupManager.importFromStream(emptyStream)
                }
            }
        }
    }

    @Test
    fun importFromStream_withEncryptedBackupStream_preservesCompatibilityAndRestoresCleanly() = runBlocking {
        val now = System.currentTimeMillis()
        sourceDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "der Schmetterling",
                germanTextKey = germanKey("der Schmetterling"),
                englishTranslation = "the butterfly",
                timestamp = now - 10000,
                interval = 4,
                easeFactor = 2.6f,
                reviewCount = 2,
                nextReview = now + 86400000L
            )
        )

        val outStream = ByteArrayOutputStream()
        sourceBackupManager.exportToStream(outStream, testPassphrase)
        assertTrue(outStream.size() > 0)

        val inStream = ByteArrayInputStream(outStream.toByteArray())
        val result = targetBackupManager.importFromStream(inStream, testPassphrase)

        assertEquals(1, result.vocabularyAdded)
        val imported = targetDb.vocabularyDao().findByGermanText("der Schmetterling")
        assertNotNull(imported)
        assertEquals("the butterfly", imported?.englishTranslation)
    }

    @Test
    fun importReviewEvents_mergeAgainstExistingVocabulary_linksReviewsToExistingTargetId() = runBlocking {
        // Target DB already contains "Hund" which gets assigned auto-increment target ID
        targetDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "Hund",
                germanTextKey = germanKey("Hund"),
                englishTranslation = "dog",
                timestamp = System.currentTimeMillis() - 50000,
                interval = 1,
                easeFactor = 2.5f,
                reviewCount = 1
            )
        )
        val targetDog = targetDb.vocabularyDao().findByGermanText("Hund")
        assertNotNull(targetDog)
        val targetDogId = targetDog!!.id

        // Incoming backup has "Hund" with backup ID = 999 and a review event referencing ID 999
        val reviewRemoteId = UUID.randomUUID().toString()
        val backupJson = JSONObject().apply {
            put("format", "deutschflow-library")
            put("version", CURRENT_BACKUP_VERSION)
            put("vocabulary", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 999) // Backup ID 999
                    put("germanText", "Hund")
                    put("englishTranslation", "dog (merged)")
                    put("interval", 3)
                    put("easeFactor", 2.6f)
                    put("reviewCount", 2)
                    put("nextReview", System.currentTimeMillis() + 86400000L)
                })
            })
            put("transcripts", org.json.JSONArray())
            put("userStats", org.json.JSONArray())
            put("activityLog", org.json.JSONArray())
            put("reviewEvents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("vocabularyId", 999) // References backup ID 999
                    put("rating", "GOOD")
                    put("scheduledDays", 3)
                    put("actualDays", 3)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", reviewRemoteId)
                })
            })
        }

        val result = targetBackupManager.importBackup(backupJson.toString(), null)
        assertEquals(0, result.vocabularyAdded)
        assertEquals(1, result.vocabularyMerged)
        assertEquals(1, result.reviewEventsAdded)

        // Verify the review event in target DB is attached to targetDogId, NOT 999!
        val review = targetDb.reviewEventDao().findByRemoteId(reviewRemoteId)
        assertNotNull(review)
        assertEquals(targetDogId, review!!.vocabularyId)
    }

    @Test
    fun importReviewEvents_repeatedImport_preventsDuplicateReviewEventsAndVocabulary() = runBlocking {
        val rev1RemoteId = UUID.randomUUID().toString()
        val rev2RemoteId = UUID.randomUUID().toString()

        val backupJson = JSONObject().apply {
            put("format", "deutschflow-library")
            put("version", CURRENT_BACKUP_VERSION)
            put("vocabulary", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 10)
                    put("germanText", "Katze")
                    put("englishTranslation", "cat")
                })
                put(JSONObject().apply {
                    put("id", 20)
                    put("germanText", "Maus")
                    put("englishTranslation", "mouse")
                })
            })
            put("transcripts", org.json.JSONArray())
            put("userStats", org.json.JSONArray())
            put("activityLog", org.json.JSONArray())
            put("reviewEvents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("vocabularyId", 10)
                    put("rating", "GOOD")
                    put("scheduledDays", 2)
                    put("actualDays", 2)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", rev1RemoteId)
                })
                put(JSONObject().apply {
                    put("vocabularyId", 20)
                    put("rating", "EASY")
                    put("scheduledDays", 4)
                    put("actualDays", 4)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", rev2RemoteId)
                })
            })
        }

        // 1. Initial import
        val firstResult = targetBackupManager.importBackup(backupJson.toString(), null)
        assertEquals(2, firstResult.vocabularyAdded)
        assertEquals(0, firstResult.vocabularyMerged)
        assertEquals(2, firstResult.reviewEventsAdded)

        val vocabAfterFirst = targetDb.vocabularyDao().getAllVocabularyList()
        val reviewsAfterFirst = targetDb.reviewEventDao().getAllEventsList()
        assertEquals(2, vocabAfterFirst.size)
        assertEquals(2, reviewsAfterFirst.size)

        // 2. Repeated import of the exact same backup payload
        val secondResult = targetBackupManager.importBackup(backupJson.toString(), null)
        assertEquals(0, secondResult.vocabularyAdded)
        assertEquals(2, secondResult.vocabularyMerged)
        assertEquals(0, secondResult.reviewEventsAdded) // Deduplicated, none added

        // Verify database counts remain exactly 2 and 2
        val vocabAfterSecond = targetDb.vocabularyDao().getAllVocabularyList()
        val reviewsAfterSecond = targetDb.reviewEventDao().getAllEventsList()
        assertEquals(2, vocabAfterSecond.size)
        assertEquals(2, reviewsAfterSecond.size)
    }

    @Test
    fun importBackup_whenDatabaseFailsMidway_rollsBackAllWritesTransactionally() = runBlocking {
        // Pre-populate target database with an initial baseline item ("Sonne")
        targetDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(
                germanText = "Sonne",
                germanTextKey = germanKey("Sonne"),
                englishTranslation = "sun",
                timestamp = System.currentTimeMillis() - 100000
            )
        )
        val initialVocab = targetDb.vocabularyDao().getAllVocabularyList()
        assertEquals(1, initialVocab.size)
        assertEquals("Sonne", initialVocab[0].germanText)

        // Backup containing new vocabulary ("Mond", "Stern") and a review event
        val backupJson = JSONObject().apply {
            put("format", "deutschflow-library")
            put("version", CURRENT_BACKUP_VERSION)
            put("vocabulary", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 101)
                    put("germanText", "Mond")
                    put("englishTranslation", "moon")
                })
                put(JSONObject().apply {
                    put("id", 102)
                    put("germanText", "Stern")
                    put("englishTranslation", "star")
                })
            })
            put("transcripts", org.json.JSONArray())
            put("userStats", org.json.JSONArray())
            put("activityLog", org.json.JSONArray())
            put("reviewEvents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("vocabularyId", 101)
                    put("rating", "GOOD")
                    put("scheduledDays", 1)
                    put("actualDays", 1)
                    put("reviewedAtTimestamp", System.currentTimeMillis())
                    put("remoteId", UUID.randomUUID().toString())
                })
            })
        }

        // Construct a BackupManager with an injected failure on reviewEventDao.insert()
        val failingReviewDao = object : ReviewEventDao by targetDb.reviewEventDao() {
            override suspend fun insert(event: ReviewEventEntity) {
                throw android.database.sqlite.SQLiteException("Injected disk I/O failure during review event insert")
            }
        }

        val failingBackupManager = BackupManager(
            database = targetDb,
            vocabularyDao = targetDb.vocabularyDao(),
            transcriptDao = targetDb.transcriptDao(),
            userStatsDao = targetDb.userStatsDao(),
            activityDao = targetDb.activityDao(),
            roleplayDao = targetDb.roleplayDao(),
            reviewEventDao = failingReviewDao
        )

        // Attempting to import must fail with BackupException.StorageError
        assertThrows(BackupException.StorageError::class.java) {
            runBlocking {
                failingBackupManager.importBackup(backupJson.toString(), null)
            }
        }

        // Verify total rollback: "Mond" and "Stern" must NOT be in the database!
        val vocabAfterRollback = targetDb.vocabularyDao().getAllVocabularyList()
        assertEquals(1, vocabAfterRollback.size)
        assertEquals("Sonne", vocabAfterRollback[0].germanText)

        val reviewsAfterRollback = targetDb.reviewEventDao().getAllEventsList()
        assertEquals(0, reviewsAfterRollback.size)
    }

    @Test
    fun roundTrip_smallPlaintextBackup_preservesAllData() = runBlocking {
        val now = System.currentTimeMillis()
        val vocabDao = sourceDb.vocabularyDao()

        vocabDao.insertVocabulary(
            VocabularyEntity(
                germanText = "einfach",
                germanTextKey = germanKey("einfach"),
                englishTranslation = "simple",
                timestamp = now,
                interval = 3,
                easeFactor = 2.5f,
                reviewCount = 2,
                nextReview = now + 86400000L * 3
            )
        )

        val unencrypted = sourceBackupManager.exportBackup(passphrase = null)
        val result = targetBackupManager.importBackup(unencrypted, passphrase = null)

        assertEquals(1, result.vocabularyAdded)
        val targetItem = targetDb.vocabularyDao().findByGermanText("einfach")
        assertNotNull(targetItem)
        assertEquals(3, targetItem!!.interval)
    }

    @Test
    fun roundTrip_encryptedBackup_near7MiB_preservesAllData() = runBlocking {
        val now = System.currentTimeMillis()
        val vocabDao = sourceDb.vocabularyDao()
        val transcriptDao = sourceDb.transcriptDao()
        val reviewDao = sourceDb.reviewEventDao()

        val vocab = VocabularyEntity(
            germanText = "großartig",
            germanTextKey = germanKey("großartig"),
            englishTranslation = "magnificent, great",
            timestamp = now - 50000,
            interval = 10,
            easeFactor = 2.6f,
            reviewCount = 4,
            nextReview = now + 86400000L * 10
        )
        vocabDao.insertVocabulary(vocab)
        val persistedVocab = vocabDao.findByGermanText("großartig")!!

        val revRemoteId = UUID.randomUUID().toString()
        reviewDao.insert(
            ReviewEventEntity(
                vocabularyId = persistedVocab.id,
                rating = "GOOD",
                scheduledDays = 10,
                actualDays = 10,
                reviewedAtTimestamp = now - 1000,
                isExtraPractice = false,
                remoteId = revRemoteId
            )
        )

        // Build ~7 MiB of data using 70 transcripts referencing shared string
        val sharedText = "Deutsches Übungsmaterial für DeutschFlow. ".repeat(2500) // ~105 KB
        for (i in 0 until 70) {
            transcriptDao.insertTranscript(
                TranscriptEntity(
                    fullText = "Lektion $i: $sharedText",
                    translation = "Lesson $i translation.",
                    analysisJson = "{\"lesson\": $i}",
                    timestamp = now - (i * 1000L),
                    remoteId = UUID.randomUUID().toString()
                )
            )
        }

        val outStream = ByteArrayOutputStream()
        sourceBackupManager.exportToStream(outStream, testPassphrase)
        val encryptedBytes = outStream.toByteArray()
        assertTrue("Encrypted backup should be >= 7 MiB", encryptedBytes.size >= 7 * 1024 * 1024)

        val inStream = ByteArrayInputStream(encryptedBytes)
        val result = targetBackupManager.importFromStream(inStream, testPassphrase)

        assertEquals(1, result.vocabularyAdded)
        assertEquals(70, result.transcriptsAdded)
        assertEquals(1, result.reviewEventsAdded)

        val targetVocab = targetDb.vocabularyDao().findByGermanText("großartig")
        assertNotNull(targetVocab)
        assertEquals(10, targetVocab!!.interval)

        val targetReviews = targetDb.reviewEventDao().getAllEventsList()
        assertEquals(1, targetReviews.size)
        assertEquals(targetVocab.id, targetReviews[0].vocabularyId)
    }

    @Test
    fun roundTrip_encryptedBackup_near8MiB_preservesAllData() = runBlocking {
        // Plaintext near 8 MiB previously failed because AES-GCM + Base64 expanded it
        // to ~10.7 MiB, exceeding the legacy 10 MiB stream limit.
        val now = System.currentTimeMillis()
        val vocabDao = sourceDb.vocabularyDao()
        val transcriptDao = sourceDb.transcriptDao()

        vocabDao.insertVocabulary(
            VocabularyEntity(
                germanText = "Erfolg",
                germanTextKey = germanKey("Erfolg"),
                englishTranslation = "success",
                timestamp = now - 20000,
                interval = 21,
                easeFactor = 2.7f,
                reviewCount = 5,
                nextReview = now + 86400000L * 21
            )
        )

        val sharedText = "Deutsches Übungsmaterial für DeutschFlow. ".repeat(2500) // ~105 KB
        for (i in 0 until 80) { // 80 * 105 KB = ~8.4 MB plaintext
            transcriptDao.insertTranscript(
                TranscriptEntity(
                    fullText = "Kapitel $i: $sharedText",
                    translation = "Chapter $i translation.",
                    analysisJson = "{\"chapter\": $i}",
                    timestamp = now - (i * 500L),
                    remoteId = UUID.randomUUID().toString()
                )
            )
        }

        val outStream = ByteArrayOutputStream()
        sourceBackupManager.exportToStream(outStream, testPassphrase)
        val encryptedBytes = outStream.toByteArray()
        // Encrypted envelope must exceed 10 MiB to prove the fix works!
        assertTrue(
            "Encrypted envelope for ~8.4 MiB plaintext must exceed 10 MiB (actual: ${encryptedBytes.size} bytes)",
            encryptedBytes.size > 10 * 1024 * 1024
        )
        assertTrue(
            "Encrypted envelope must be within 15 MiB limit",
            encryptedBytes.size <= MAX_ENCRYPTED_BACKUP_FILE_BYTES
        )

        val inStream = ByteArrayInputStream(encryptedBytes)
        val result = targetBackupManager.importFromStream(inStream, testPassphrase)

        assertEquals(1, result.vocabularyAdded)
        assertEquals(80, result.transcriptsAdded)

        val targetVocab = targetDb.vocabularyDao().findByGermanText("Erfolg")
        assertNotNull(targetVocab)
        assertEquals(21, targetVocab!!.interval)
        assertEquals(80, targetDb.transcriptDao().getAllTranscriptsList().size)
    }

    @Test
    fun importBackup_withMalformedBase64Ciphertext_throwsCorruptedData() = runBlocking {
        sourceDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(germanText = "Kugelschreiber", germanTextKey = "kugelschreiber", englishTranslation = "ballpoint pen")
        )
        val encrypted = sourceBackupManager.exportBackup(testPassphrase)
        val json = JSONObject(encrypted)
        json.put("ciphertext", "###NOT_VALID_BASE64_DATA???%%%&&&")

        try {
            targetBackupManager.importBackup(json.toString(), testPassphrase)
            fail("Expected CorruptedData or InvalidFormat")
        } catch (e: BackupException) {
            assertTrue(e is BackupException.CorruptedData || e is BackupException.InvalidFormat)
        }
    }

    @Test
    fun importBackup_withTamperedCiphertextAuthTag_throwsCorruptedDataOrWrongPassphrase() = runBlocking {
        sourceDb.vocabularyDao().insertVocabulary(
            VocabularyEntity(germanText = "Feder", germanTextKey = "feder", englishTranslation = "feather")
        )
        val encrypted = sourceBackupManager.exportBackup(testPassphrase)
        val json = JSONObject(encrypted)
        val rawBase64 = json.getString("ciphertext")
        val decoded = android.util.Base64.decode(rawBase64, android.util.Base64.DEFAULT)
        // Invert the last byte (part of the 16-byte GCM authentication tag)
        decoded[decoded.size - 1] = (decoded[decoded.size - 1].toInt() xor 0xFF).toByte()
        json.put("ciphertext", android.util.Base64.encodeToString(decoded, android.util.Base64.NO_WRAP))

        try {
            targetBackupManager.importBackup(json.toString(), testPassphrase)
            fail("Expected authentication tag verification failure")
        } catch (e: BackupException) {
            assertTrue(e is BackupException.CorruptedData || e is BackupException.WrongPassphrase)
        }
    }
}
