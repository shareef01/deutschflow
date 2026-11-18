package com.aus.deutschflow.data.local.backup

import com.aus.deutschflow.data.local.dao.ActivityDao
import com.aus.deutschflow.data.local.dao.MasteryCounts
import com.aus.deutschflow.data.local.dao.ReviewEventDao
import com.aus.deutschflow.data.local.dao.RoleplayDao
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.ActivityEntity
import com.aus.deutschflow.data.local.entities.ReviewEventEntity
import com.aus.deutschflow.data.local.entities.RoleplayMessageEntity
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidationTest {

    private fun createValidBackupJson(
        format: String = BACKUP_FORMAT,
        version: Int = CURRENT_BACKUP_VERSION,
        vocabulary: JSONArray = JSONArray(),
        transcripts: JSONArray = JSONArray(),
        userStats: JSONArray = JSONArray(),
        activityLog: JSONArray = JSONArray()
    ): JSONObject {
        return JSONObject().apply {
            put("format", format)
            put("version", version)
            put("exportedAt", System.currentTimeMillis())
            put("vocabulary", vocabulary)
            put("transcripts", transcripts)
            put("userStats", userStats)
            put("activityLog", activityLog)
        }
    }

    @Test
    fun `valid empty v2 backup passes validation`() {
        val backup = createValidBackupJson()
        BackupManager.validateBackupJson(backup)
    }

    @Test
    fun `valid v1 backup passes validation`() {
        val backup = createValidBackupJson(version = 1)
        BackupManager.validateBackupJson(backup)
    }

    @Test
    fun `accepts the historical Float ease floor and rejects a poisoned study timestamp`() {
        val card = JSONObject()
            .put("id", 1)
            .put("germanText", "Haus")
            .put("englishTranslation", "house")
            .put("easeFactor", 1.3f.toDouble())
        val backup = createValidBackupJson(version = 2, vocabulary = JSONArray().put(card))
        BackupManager.validateBackupJson(JSONObject(backup.toString()))

        card.put("lastStudiedAt", Long.MAX_VALUE)
        val error = assertThrows(BackupException.InvalidFormat::class.java) {
            BackupManager.validateBackupJson(backup)
        }
        assertEquals("Invalid lastStudiedAt timestamp.", error.message)
    }

    @Test
    fun `rejects invalid format`() {
        val backup = createValidBackupJson(format = "anki-deck")
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupManager.validateBackupJson(backup)
        }
    }

    @Test
    fun `rejects unsupported future version`() {
        val backup = createValidBackupJson(version = 99)
        assertThrows(BackupException.UnsupportedVersion::class.java) {
            BackupManager.validateBackupJson(backup)
        }
    }

    @Test
    fun `rejects missing required collections`() {
        val backup = JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("version", 2)
            put("vocabulary", JSONArray())
            // Missing transcripts, userStats, activityLog
        }
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupManager.validateBackupJson(backup)
        }
    }

    @Test
    fun `validates populated vocabulary with correct SRS schedule`() {
        val vocab = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 1)
                put("germanText", "der Baum")
                put("englishTranslation", "the tree")
                put("interval", 6)
                put("easeFactor", 2.5)
                put("reviewCount", 2)
                put("nextReview", System.currentTimeMillis() + 86400000L)
            })
            put(JSONObject().apply {
                put("id", 2)
                put("germanText", "lernen")
                put("englishTranslation", "to learn")
                put("interval", 0)
                put("easeFactor", 2.5)
                put("reviewCount", 0)
                put("nextReview", 0L)
            })
        }
        val backup = createValidBackupJson(vocabulary = vocab)
        BackupManager.validateBackupJson(backup)
    }

    @Test
    fun `rejects incoherent SRS schedule`() {
        val vocab = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 1)
                put("germanText", "der Baum")
                put("englishTranslation", "the tree")
                put("interval", 5) // Learned interval but 0 reviewCount / 0 nextReview
                put("easeFactor", 2.5)
                put("reviewCount", 0)
                put("nextReview", 0L)
            })
        }
        val backup = createValidBackupJson(vocabulary = vocab)
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupManager.validateBackupJson(backup)
        }
    }

    @Test
    fun `rejects duplicate vocabulary IDs`() {
        val vocab = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 42)
                put("germanText", "Hund")
                put("englishTranslation", "dog")
                put("interval", 0)
                put("easeFactor", 2.5)
                put("reviewCount", 0)
                put("nextReview", 0L)
            })
            put(JSONObject().apply {
                put("id", 42)
                put("germanText", "Katze")
                put("englishTranslation", "cat")
                put("interval", 0)
                put("easeFactor", 2.5)
                put("reviewCount", 0)
                put("nextReview", 0L)
            })
        }
        val backup = createValidBackupJson(vocabulary = vocab)
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupManager.validateBackupJson(backup)
        }
    }

    @Test
    fun `rejects invalid calendar date in activity log`() {
        val activity = JSONArray().apply {
            put(JSONObject().apply {
                put("date", "2026-02-31") // Invalid date
                put("xpGained", 10)
                put("timestamp", 1000L)
            })
        }
        val backup = createValidBackupJson(activityLog = activity)
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupManager.validateBackupJson(backup)
        }
    }

    @Test
    fun `calculateStreakFromDates calculates consecutive days correctly`() {
        val dates = listOf("2026-10-15", "2026-10-14", "2026-10-13")
        val streak = BackupManager.calculateStreakFromDates(dates, today = LocalDate.parse("2026-10-15"))
        assertEquals(3, streak)

        // Today not logged yet, but yesterday was logged
        val streakYesterday = BackupManager.calculateStreakFromDates(dates, today = LocalDate.parse("2026-10-16"))
        assertEquals(3, streakYesterday)

        // Missed two days
        val brokenStreak = BackupManager.calculateStreakFromDates(dates, today = LocalDate.parse("2026-10-18"))
        assertEquals(0, brokenStreak)

        // Tolerates +1 day timezone shift (e.g. backup created just after local midnight ahead of UTC)
        val timezoneShiftStreak = BackupManager.calculateStreakFromDates(dates, today = LocalDate.parse("2026-10-14"))
        assertEquals(3, timezoneShiftStreak)
    }

    @Test
    fun `deterministicTranscriptId produces identical UUID for identical fullText and timestamp`() {
        val id1 = BackupManager.deterministicTranscriptId("Guten Morgen", 1725667200000L)
        val id2 = BackupManager.deterministicTranscriptId("Guten Morgen", 1725667200000L)
        assertEquals(id1, id2)
    }

    @Test
    fun `rejects review event referencing negative vocabulary ID`() {
        val vocab = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 10)
                put("germanText", "Wasser")
                put("englishTranslation", "water")
                put("interval", 0)
                put("easeFactor", 2.5)
                put("reviewCount", 0)
                put("nextReview", 0L)
            })
        }
        val reviews = JSONArray().apply {
            put(JSONObject().apply {
                put("vocabularyId", -1) // Negative ID is malformed
                put("rating", "GOOD")
                put("scheduledDays", 1)
                put("actualDays", 1)
                put("reviewedAtTimestamp", System.currentTimeMillis())
                put("remoteId", java.util.UUID.randomUUID().toString())
            })
        }
        val backup = createValidBackupJson(vocabulary = vocab).apply {
            put("reviewEvents", reviews)
        }
        assertThrows(BackupException.InvalidFormat::class.java) {
            BackupManager.validateBackupJson(backup)
        }
    }

    @Test
    fun `accepts review event referencing unmapped vocabulary ID for archival recovery`() {
        val vocab = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 10)
                put("germanText", "Wasser")
                put("englishTranslation", "water")
                put("interval", 0)
                put("easeFactor", 2.5)
                put("reviewCount", 0)
                put("nextReview", 0L)
            })
        }
        val reviews = JSONArray().apply {
            put(JSONObject().apply {
                put("vocabularyId", 99) // ID 99 not in vocabulary array, accepted and routed to archived entity
                put("rating", "GOOD")
                put("scheduledDays", 1)
                put("actualDays", 1)
                put("reviewedAtTimestamp", System.currentTimeMillis())
                put("remoteId", java.util.UUID.randomUUID().toString())
            })
        }
        val backup = createValidBackupJson(vocabulary = vocab).apply {
            put("reviewEvents", reviews)
        }
        // Does not throw
        BackupManager.validateBackupJson(backup)
    }

    @Test
    fun `accepts review event referencing existing vocabulary ID`() {
        val vocab = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 10)
                put("germanText", "Wasser")
                put("englishTranslation", "water")
                put("interval", 0)
                put("easeFactor", 2.5)
                put("reviewCount", 0)
                put("nextReview", 0L)
            })
        }
        val reviews = JSONArray().apply {
            put(JSONObject().apply {
                put("vocabularyId", 10) // Matches vocabulary ID 10
                put("rating", "GOOD")
                put("scheduledDays", 1)
                put("actualDays", 1)
                put("reviewedAtTimestamp", System.currentTimeMillis())
                put("remoteId", java.util.UUID.randomUUID().toString())
            })
        }
        val backup = createValidBackupJson(vocabulary = vocab).apply {
            put("reviewEvents", reviews)
        }
        BackupManager.validateBackupJson(backup)
    }

    @Test
    fun `applyImport deduplicates v1 transcripts, reports roleplay restore vs skip, and derives stats from activityLog`() = runTest {
        val vocabMap = mutableMapOf<Int, VocabularyEntity>()
        val transcriptList = mutableListOf<TranscriptEntity>()
        var storedStats: UserStatsEntity? = null
        val activityMap = mutableMapOf<String, ActivityEntity>()
        val roleplayList = mutableListOf<RoleplayMessageEntity>()
        val reviewList = mutableListOf<ReviewEventEntity>()
        var nextVocabId = 1
        var nextTranscriptId = 1

        val fakeVocabularyDao = object : VocabularyDao {
            override fun getAllVocabulary(): Flow<List<VocabularyEntity>> = emptyFlow()
            override suspend fun getAllVocabularyList(): List<VocabularyEntity> =
                vocabMap.values.filter { it.germanTextKey != "__archived_deleted__" }
            override fun countVocabulary(): Flow<Int> = emptyFlow()
            override fun getDueVocabulary(currentTime: Long): Flow<List<VocabularyEntity>> = emptyFlow()
            override suspend fun insertVocabulary(vocabulary: VocabularyEntity) {
                val id = if (vocabulary.id == 0) nextVocabId++ else vocabulary.id
                vocabMap[id] = vocabulary.copy(id = id)
            }
            override suspend fun deleteVocabulary(vocabulary: VocabularyEntity) { vocabMap.remove(vocabulary.id) }
            override suspend fun updateVocabulary(vocabulary: VocabularyEntity) { vocabMap[vocabulary.id] = vocabulary }
            override suspend fun deleteAll() { vocabMap.clear() }
            override suspend fun findByKey(key: String): VocabularyEntity? =
                vocabMap.values.firstOrNull { it.germanTextKey == key }
            override suspend fun deleteById(id: Int) { vocabMap.remove(id) }
            override suspend fun countOnce(): Int = vocabMap.size
            override suspend fun nthById(offset: Int): VocabularyEntity? = vocabMap.values.elementAtOrNull(offset)
            override fun masteryCounts(masteredDays: Int): Flow<MasteryCounts> = emptyFlow()
            override suspend fun findById(id: Int): VocabularyEntity? = vocabMap[id]
            override suspend fun updateSchedule(
                id: Int,
                nextReview: Long,
                interval: Int,
                easeFactor: Float,
                reviewCount: Int,
                lastModifiedAt: Long,
                lastStudiedAt: Long
            ): Int = 1
            override suspend fun reassignReviewEvents(from: Int, to: Int) {}
            override suspend fun latestStudyTimestampForVocab(vocabularyId: Int, maxTimestamp: Long): Long? =
                reviewList.filter { it.vocabularyId == vocabularyId && (!it.isExtraPractice || it.rating == "AGAIN") && it.reviewedAtTimestamp in 1L..maxTimestamp }
                    .maxOfOrNull { it.reviewedAtTimestamp }
        }

        val manager = BackupManager(
            vocabularyDao = fakeVocabularyDao,
            transcriptDao = object : TranscriptDao {
                override fun getAllTranscripts(): Flow<List<TranscriptEntity>> = emptyFlow()
                override suspend fun getAllTranscriptsList(): List<TranscriptEntity> = transcriptList.toList()
                override suspend fun findByRemoteId(remoteId: String): TranscriptEntity? =
                    transcriptList.firstOrNull { it.remoteId == remoteId }
                override suspend fun findByContentAndTimestamp(fullText: String, timestamp: Long): TranscriptEntity? =
                    transcriptList.firstOrNull { it.fullText == fullText && it.timestamp == timestamp }
                override fun countTranscripts(): Flow<Int> = emptyFlow()
                override fun hasAnyTranscript(): Flow<Boolean> = emptyFlow()
                override suspend fun insertTranscript(transcript: TranscriptEntity): Long {
                    val id = if (transcript.id == 0) nextTranscriptId++ else transcript.id
                    transcriptList.removeAll { it.id == id }
                    transcriptList.add(transcript.copy(id = id))
                    return id.toLong()
                }
                override suspend fun updateAnalysis(id: Int, translation: String, analysisJson: String, modifiedAt: Long) {}
                override suspend fun deleteTranscript(transcript: TranscriptEntity) { transcriptList.remove(transcript) }
                override suspend fun deleteAll() { transcriptList.clear() }
            },
            userStatsDao = object : UserStatsDao {
                override fun getUserStats(): Flow<UserStatsEntity?> = emptyFlow()
                override suspend fun getUserStatsOnce(): UserStatsEntity? = storedStats
                override suspend fun insertOrUpdate(stats: UserStatsEntity) { storedStats = stats }
                override suspend fun deleteAll() { storedStats = null }
            },
            activityDao = object : ActivityDao {
                override fun getActivitySince(since: String): Flow<List<ActivityEntity>> = emptyFlow()
                override suspend fun getAllActivity(): List<ActivityEntity> = activityMap.values.toList()
                override suspend fun getActivityForDate(date: String): ActivityEntity? = activityMap[date]
                override suspend fun insertOrUpdate(activity: ActivityEntity) { activityMap[activity.date] = activity }
                override suspend fun deleteAll() { activityMap.clear() }
            },
            roleplayDao = object : RoleplayDao {
                override suspend fun getConversation(): List<RoleplayMessageEntity> = roleplayList.sortedBy { it.position }
                override suspend fun insert(message: RoleplayMessageEntity) {
                    roleplayList.removeAll { it.position == message.position }
                    roleplayList.add(message)
                }
                override suspend fun deleteAll() { roleplayList.clear() }
            },
            reviewEventDao = object : ReviewEventDao {
                override suspend fun insert(event: ReviewEventEntity) { reviewList.add(event) }
                override suspend fun insertAll(events: List<ReviewEventEntity>) { reviewList.addAll(events) }
                override fun getAllEvents(): Flow<List<ReviewEventEntity>> = emptyFlow()
                override suspend fun getAllEventsList(): List<ReviewEventEntity> = reviewList.toList()
                override suspend fun findByRemoteId(remoteId: String): ReviewEventEntity? =
                    reviewList.firstOrNull { it.remoteId == remoteId }
                override fun getEventsForWord(vocabId: Int): Flow<List<ReviewEventEntity>> = emptyFlow()
                override suspend fun latestReviewTimestamp(vocabId: Int, atOrBefore: Long): Long? =
                    reviewList.filter { it.vocabularyId == vocabId && (!it.isExtraPractice || it.rating == "AGAIN") && it.reviewedAtTimestamp <= atOrBefore }
                        .maxOfOrNull { it.reviewedAtTimestamp }
                override suspend fun deleteAll() { reviewList.clear() }
            }
        )

        // Seed an untouched assistant greeting in local roleplay
        roleplayList.add(
            RoleplayMessageEntity(
                position = 0,
                scenario = "bakery",
                role = "assistant",
                content = "Guten Morgen!",
                timestamp = 1000L
            )
        )

        val todayStr = LocalDate.now().toString()
        // Stale local streak of 25 that should be recalculated to 1 from activityLog
        storedStats = UserStatsEntity(id = 1, xp = 50, streak = 25, lastActivityTimestamp = 0L)

        val v1Backup = createValidBackupJson(
            version = 2,
            transcripts = JSONArray().put(
                JSONObject().apply {
                    put("fullText", "Guten Abend")
                    put("timestamp", 1725667200000L)
                    // No remoteId (legacy v1 transcript)
                }
            ),
            userStats = JSONArray(), // Empty userStats
            activityLog = JSONArray().apply {
                put(JSONObject().put("date", todayStr).put("xpGained", 40).put("timestamp", 1729123200000L))
                put(JSONObject().put("date", LocalDate.now().minusDays(1).toString()).put("xpGained", 0).put("timestamp", 1729000000000L))
            }
        ).apply {
            put(
                "roleplayMessages",
                JSONArray().apply {
                    put(JSONObject().put("position", 0).put("scenario", "doctor").put("role", "assistant").put("content", "Was fehlt Ihnen?").put("timestamp", 2000L))
                    put(JSONObject().put("position", 1).put("scenario", "doctor").put("role", "user").put("content", "Ich habe Fieber.").put("timestamp", 2500L))
                }
            )
        }

        val res1 = manager.applyImport(v1Backup)
        assertEquals(1, res1.transcriptsAdded)
        assertEquals("Untouched local greeting is replaced by backup roleplay", 2, res1.roleplayMessagesAdded)
        assertEquals(0, res1.roleplayMessagesSkipped)
        assertEquals("Zero-XP day is ignored and stale streak 25 is replaced by calculated streak 1", 1, storedStats?.streak)
        assertEquals("lastActivityTimestamp derived from active activityLog when userStats is empty", 1729123200000L, storedStats?.lastActivityTimestamp)

        // Second import of the exact same backup is idempotent (0 added, 0 skipped)
        val res2 = manager.applyImport(v1Backup)
        assertEquals(0, res2.transcriptsAdded)
        assertEquals(1, transcriptList.size)
        assertEquals(0, res2.roleplayMessagesAdded)
        assertEquals(0, res2.roleplayMessagesSkipped)
        assertEquals(2, roleplayList.size)

        // Third import with a different roleplay conversation is skipped (preserving local user turns) and reported
        val conflictingRoleplayBackup = JSONObject(v1Backup.toString()).apply {
            put(
                "roleplayMessages",
                JSONArray().put(
                    JSONObject().put("position", 0).put("scenario", "cafe").put("role", "assistant").put("content", "Willkommen im Café!").put("timestamp", 3000L)
                )
            )
        }
        val res3 = manager.applyImport(conflictingRoleplayBackup)
        assertEquals(0, res3.roleplayMessagesAdded)
        assertEquals(1, res3.roleplayMessagesSkipped)
        assertEquals("Existing local user conversation is preserved", "doctor", roleplayList.first().scenario)

        // Verify exportBackup reconstructs lastStudiedAt from schedule-governing reviewEvents
        vocabMap[1] = VocabularyEntity(
            id = 1,
            germanText = "das Haus",
            germanTextKey = "das haus",
            englishTranslation = "the house",
            timestamp = 1000L,
            nextReview = 5000L,
            interval = 1,
            easeFactor = 2.5f,
            reviewCount = 1,
            lastModifiedAt = 2000L
        )
        reviewList.add(
            ReviewEventEntity(
                id = 1,
                vocabularyId = 1,
                rating = "GOOD",
                scheduledDays = 1,
                actualDays = 1,
                reviewedAtTimestamp = 4321000L,
                isExtraPractice = false
            )
        )
        val exported = JSONObject(manager.exportBackup())
        val exportedVocab = exported.getJSONArray("vocabulary").getJSONObject(0)
        assertEquals(4321000L, exportedVocab.getLong("lastStudiedAt"))

        // Verify Android preserves lastStudiedAt when imported vocabulary has scheduling metadata (lastStudiedAt) but NO reviewEvents
        val backupWithoutReviewEvents = createValidBackupJson(
            version = 2,
            vocabulary = JSONArray().put(
                JSONObject().apply {
                    put("id", 55)
                    put("germanText", "der Bahnhof")
                    put("englishTranslation", "the train station")
                    put("timestamp", 1000L)
                    put("nextReview", 9999000L)
                    put("interval", 6)
                    put("easeFactor", 2.5)
                    put("reviewCount", 2)
                    put("lastModifiedAt", 5000L)
                    put("lastStudiedAt", 7654321L)
                }
            )
        )
        manager.applyImport(backupWithoutReviewEvents)
        assertTrue(
            "lastStudiedAt is persisted directly on VocabularyEntity without creating __archived_deleted__",
            vocabMap.values.none { it.germanTextKey == "__archived_deleted__" }
        )
        val bahnhofEntity = fakeVocabularyDao.findByGermanText("der Bahnhof")!!
        assertEquals(7654321L, bahnhofEntity.lastStudiedAt)

        // Rename the imported word in-app via VocabularyDao.save and verify lastStudiedAt survives rename and export
        fakeVocabularyDao.save(bahnhofEntity.copy(germanText = "der Hauptbahnhof"))
        val exportedAfterRename = JSONObject(manager.exportBackup())
        val exportedVocabArray = exportedAfterRename.getJSONArray("vocabulary")
        val hauptbahnhofJson = (0 until exportedVocabArray.length())
            .map { exportedVocabArray.getJSONObject(it) }
            .first { it.getString("germanText") == "der Hauptbahnhof" }
        assertEquals(
            "lastStudiedAt must survive Android import, in-app rename, and export round-trip without reviewEvents",
            7654321L,
            hauptbahnhofJson.getLong("lastStudiedAt")
        )
    }
}
