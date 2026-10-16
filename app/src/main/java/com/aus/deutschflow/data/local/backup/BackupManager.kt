package com.aus.deutschflow.data.local.backup

import androidx.room.withTransaction
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.dao.*
import com.aus.deutschflow.data.local.entities.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val database: AppDatabase,
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val userStatsDao: UserStatsDao,
    private val activityDao: ActivityDao,
    private val roleplayDao: RoleplayDao,
    private val reviewEventDao: ReviewEventDao
) {

    suspend fun exportBackup(passphrase: CharArray? = null): String {
        val vocabulary = vocabularyDao.getAllVocabularyList()
        val transcripts = transcriptDao.getAllTranscriptsList()
        val userStats = userStatsDao.getUserStatsOnce()
        val activityLog = activityDao.getAllActivity()
        val roleplay = roleplayDao.getConversation()
        val reviewEvents = reviewEventDao.getAllEventsList()

        val backupObj = JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("version", CURRENT_BACKUP_VERSION)
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            put("exportedAt", isoFormat.format(Date()))

            put("vocabulary", JSONArray().apply {
                vocabulary.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("germanText", item.germanText)
                        put("germanTextKey", item.germanTextKey)
                        put("englishTranslation", item.englishTranslation)
                        put("timestamp", item.timestamp)
                        put("exampleSentence", item.exampleSentence)
                        put("article", item.article)
                        put("plural", item.plural)
                        put("conjugation", item.conjugation)
                        put("synonyms", item.synonyms)
                        put("antonyms", item.antonyms)
                        put("nextReview", item.nextReview)
                        put("interval", item.interval)
                        put("easeFactor", item.easeFactor.toDouble())
                        put("reviewCount", item.reviewCount)
                        put("remoteId", item.remoteId)
                        put("lastModifiedAt", item.lastModifiedAt)
                    })
                }
            })

            put("transcripts", JSONArray().apply {
                transcripts.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("fullText", item.fullText)
                        put("timestamp", item.timestamp)
                        put("remoteId", item.remoteId)
                        put("lastModifiedAt", item.lastModifiedAt)
                        put("translation", item.translation)
                        put("analysisJson", item.analysisJson)
                    })
                }
            })

            put("userStats", JSONArray().apply {
                if (userStats != null) {
                    put(JSONObject().apply {
                        put("id", userStats.id)
                        put("xp", userStats.xp)
                        put("streak", userStats.streak)
                        put("lastActivityTimestamp", userStats.lastActivityTimestamp)
                    })
                }
            })

            put("activityLog", JSONArray().apply {
                activityLog.forEach { item ->
                    put(JSONObject().apply {
                        put("date", item.date)
                        put("xpGained", item.xpGained)
                        put("timestamp", item.timestamp)
                    })
                }
            })

            put("reviewEvents", JSONArray().apply {
                reviewEvents.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("vocabularyId", item.vocabularyId)
                        put("rating", item.rating)
                        put("scheduledDays", item.scheduledDays)
                        put("actualDays", item.actualDays)
                        put("reviewedAtTimestamp", item.reviewedAtTimestamp)
                        put("isExtraPractice", item.isExtraPractice)
                        put("remoteId", item.remoteId)
                    })
                }
            })

            put("roleplayMessages", JSONArray().apply {
                roleplay.forEach { item ->
                    put(JSONObject().apply {
                        put("position", item.position)
                        put("scenario", item.scenario)
                        put("role", item.role)
                        put("content", item.content)
                        put("translation", item.translation ?: JSONObject.NULL)
                        put("timestamp", item.timestamp)
                    })
                }
            })
        }

        // Validate own export before offering it
        validateBackupJson(backupObj)

        val plainJson = backupObj.toString(2)
        return if (passphrase != null && passphrase.isNotEmpty()) {
            BackupCrypto.encrypt(plainJson, passphrase)
        } else {
            plainJson
        }
    }

    suspend fun exportToStream(outputStream: OutputStream, passphrase: CharArray? = null) {
        val content = exportBackup(passphrase)
        outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
    }

    suspend fun importFromStream(inputStream: InputStream, passphrase: CharArray? = null): BackupImportResult {
        val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return importBackup(content, passphrase)
    }

    suspend fun importBackup(content: String, passphrase: CharArray? = null): BackupImportResult {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_FILE_BYTES) {
            throw BackupException.CapacityExceeded("Backup exceeds 10 MB size limit.")
        }

        val rawObj = try {
            JSONObject(content)
        } catch (e: Exception) {
            throw BackupException.InvalidFormat("Malformed JSON payload.")
        }

        val backupObj = if (BackupCrypto.isEncryptedBackup(rawObj)) {
            val decryptedPlain = BackupCrypto.decrypt(rawObj, passphrase)
            try {
                JSONObject(decryptedPlain)
            } catch (e: Exception) {
                throw BackupException.CorruptedData("Decrypted backup is not valid JSON.")
            }
        } else {
            rawObj
        }

        validateBackupJson(backupObj)

        return try {
            database.withTransaction {
                applyImport(backupObj)
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException.StorageError("Storage error during backup import: ${e.message}", e)
        }
    }

    private suspend fun applyImport(backup: JSONObject): BackupImportResult {
        val now = System.currentTimeMillis()
        var vocabularyAdded = 0
        var vocabularyMerged = 0
        var transcriptsAdded = 0
        var reviewEventsAdded = 0

        val backupIdToPersistedId = mutableMapOf<Int, Int>()

        // 1. Vocabulary
        val vocabArray = backup.getJSONArray("vocabulary")
        for (i in 0 until vocabArray.length()) {
            val row = vocabArray.getJSONObject(i)
            val germanText = row.getString("germanText").trim()
            val englishTranslation = row.getString("englishTranslation").trim()
            if (germanText.isBlank() || englishTranslation.isBlank()) continue

            val oldBackupId = row.optInt("id", 0)
            val remoteId = row.optString("remoteId").ifBlank { UUID.randomUUID().toString() }
            val lastModifiedAt = row.optLong("lastModifiedAt", row.optLong("timestamp", now))

            val incoming = VocabularyEntity(
                id = 0,
                germanText = germanText,
                germanTextKey = germanKey(germanText),
                englishTranslation = englishTranslation,
                timestamp = row.optLong("timestamp", now),
                exampleSentence = row.optString("exampleSentence", ""),
                article = row.optString("article", ""),
                plural = row.optString("plural", ""),
                conjugation = row.optString("conjugation", ""),
                synonyms = row.optString("synonyms", ""),
                antonyms = row.optString("antonyms", ""),
                nextReview = row.optLong("nextReview", 0L),
                interval = row.optInt("interval", 0),
                easeFactor = row.optDouble("easeFactor", 2.5).toFloat(),
                reviewCount = row.optInt("reviewCount", 0),
                remoteId = remoteId,
                lastModifiedAt = lastModifiedAt
            )

            val existing = vocabularyDao.findByKey(incoming.germanTextKey)
            if (existing != null) {
                val merged = existing.mergedWith(incoming, now).withFurthestScheduleOf(incoming)
                vocabularyDao.updateVocabulary(merged)
                if (oldBackupId > 0) backupIdToPersistedId[oldBackupId] = existing.id
                vocabularyMerged++
            } else {
                vocabularyDao.insertVocabulary(incoming)
                val persisted = vocabularyDao.findByKey(incoming.germanTextKey)
                if (persisted != null && oldBackupId > 0) {
                    backupIdToPersistedId[oldBackupId] = persisted.id
                }
                vocabularyAdded++
            }
        }

        // 2. Transcripts
        val transcriptsArray = backup.getJSONArray("transcripts")
        for (i in 0 until transcriptsArray.length()) {
            val row = transcriptsArray.getJSONObject(i)
            val fullText = row.getString("fullText").trim()
            if (fullText.isBlank()) continue

            val remoteId = row.optString("remoteId").ifBlank { UUID.randomUUID().toString() }
            val lastModifiedAt = row.optLong("lastModifiedAt", row.optLong("timestamp", now))

            val incoming = TranscriptEntity(
                id = 0,
                fullText = fullText,
                timestamp = row.optLong("timestamp", now),
                remoteId = remoteId,
                lastModifiedAt = lastModifiedAt,
                translation = row.optString("translation", ""),
                analysisJson = row.optString("analysisJson", "")
            )

            val existing = transcriptDao.findByRemoteId(remoteId)
            if (existing == null) {
                transcriptDao.insertTranscript(incoming)
                transcriptsAdded++
            } else if (incoming.lastModifiedAt > existing.lastModifiedAt) {
                transcriptDao.insertTranscript(incoming.copy(id = existing.id))
            }
        }

        // 3. User Stats & Activity Log
        val statsArray = backup.getJSONArray("userStats")
        val currentStats = userStatsDao.getUserStatsOnce() ?: UserStatsEntity(id = 1, xp = 0, streak = 0, lastActivityTimestamp = 0L)
        var mergedXp = currentStats.xp
        var mergedLastActivity = currentStats.lastActivityTimestamp

        if (statsArray.length() > 0) {
            val statsObj = statsArray.getJSONObject(0)
            mergedXp = maxOf(currentStats.xp, statsObj.optInt("xp", 0))
            mergedLastActivity = maxOf(currentStats.lastActivityTimestamp, statsObj.optLong("lastActivityTimestamp", 0L))
        }

        val activityArray = backup.getJSONArray("activityLog")
        for (i in 0 until activityArray.length()) {
            val row = activityArray.getJSONObject(i)
            val date = row.getString("date")
            val xpGained = row.getInt("xpGained")
            val ts = row.optLong("timestamp", now)

            val existingActivity = activityDao.getActivityForDate(date)
            if (existingActivity != null) {
                activityDao.insertOrUpdate(existingActivity.copy(xpGained = maxOf(existingActivity.xpGained, xpGained)))
            } else {
                activityDao.insertOrUpdate(ActivityEntity(date = date, xpGained = xpGained, timestamp = ts))
            }
        }

        val allDates = activityDao.getAllActivity().map { it.date }
        val calculatedStreak = calculateStreakFromDates(allDates)
        val finalStreak = maxOf(currentStats.streak, calculatedStreak)

        userStatsDao.insertOrUpdate(
            currentStats.copy(
                xp = mergedXp,
                streak = finalStreak,
                lastActivityTimestamp = mergedLastActivity
            )
        )

        // 4. Roleplay Messages
        if (backup.has("roleplayMessages")) {
            val roleplayArray = backup.getJSONArray("roleplayMessages")
            if (roleplayArray.length() > 0) {
                val currentConv = roleplayDao.getConversation()
                if (currentConv.isEmpty()) {
                    for (i in 0 until roleplayArray.length()) {
                        val row = roleplayArray.getJSONObject(i)
                        roleplayDao.insert(
                            RoleplayMessageEntity(
                                position = row.getInt("position"),
                                scenario = row.getString("scenario"),
                                role = row.getString("role"),
                                content = row.getString("content"),
                                translation = if (row.isNull("translation")) null else row.optString("translation"),
                                timestamp = row.optLong("timestamp", now)
                            )
                        )
                    }
                }
            }
        }

        // 5. Review Events
        if (backup.has("reviewEvents")) {
            val reviewArray = backup.getJSONArray("reviewEvents")
            for (i in 0 until reviewArray.length()) {
                val row = reviewArray.getJSONObject(i)
                val originalVocabId = row.getInt("vocabularyId")
                val targetVocabId = backupIdToPersistedId[originalVocabId]
                // Prevent orphan review events or incorrect cross-assignment to an unrelated target row:
                // Only link review events whose original vocabulary ID mapped cleanly to an imported/merged target row.
                if (targetVocabId == null || targetVocabId <= 0) {
                    continue
                }
                if (vocabularyDao.findById(targetVocabId) == null) {
                    continue
                }

                val remoteId = row.optString("remoteId").ifBlank { UUID.randomUUID().toString() }
                val existing = reviewEventDao.findByRemoteId(remoteId)
                if (existing == null) {
                    reviewEventDao.insert(
                        ReviewEventEntity(
                            id = 0,
                            vocabularyId = targetVocabId,
                            rating = row.getString("rating"),
                            scheduledDays = row.getInt("scheduledDays"),
                            actualDays = row.getInt("actualDays"),
                            reviewedAtTimestamp = row.getLong("reviewedAtTimestamp"),
                            isExtraPractice = row.optBoolean("isExtraPractice", false),
                            remoteId = remoteId
                        )
                    )
                    reviewEventsAdded++
                }
            }
        }

        return BackupImportResult(
            vocabularyAdded = vocabularyAdded,
            vocabularyMerged = vocabularyMerged,
            transcriptsAdded = transcriptsAdded,
            reviewEventsAdded = reviewEventsAdded
        )
    }

    companion object {
        fun isValidCalendarDate(dateStr: String): Boolean {
            if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(dateStr)) return false
            return try {
                val parsed = LocalDate.parse(dateStr)
                parsed.year in 2000..2100
            } catch (e: DateTimeParseException) {
                false
            }
        }

        fun calculateStreakFromDates(dates: List<String>, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int {
            val validDates = dates.filter { isValidCalendarDate(it) }
                .map { LocalDate.parse(it) }
                .distinct()
                .sortedDescending()

            if (validDates.isEmpty()) return 0

            val todayEpoch = today.toEpochDay()
            val yesterdayEpoch = todayEpoch - 1
            val latestEpoch = validDates.first().toEpochDay()

            if (latestEpoch != todayEpoch && latestEpoch != yesterdayEpoch) {
                return 0
            }

            var streak = 0
            var expectedEpoch = latestEpoch
            for (date in validDates) {
                val currentEpoch = date.toEpochDay()
                if (currentEpoch == expectedEpoch) {
                    streak++
                    expectedEpoch--
                } else if (currentEpoch < expectedEpoch) {
                    break
                }
            }
            return streak
        }

        fun validateBackupJson(backup: JSONObject) {
            if (backup.optString("format") != BACKUP_FORMAT) {
                throw BackupException.InvalidFormat("Not a DeutschFlow library export.")
            }
            val version = backup.optInt("version", -1)
            if (version <= 0) {
                throw BackupException.InvalidFormat("Invalid backup version.")
            }
            if (version > CURRENT_BACKUP_VERSION) {
                throw BackupException.UnsupportedVersion("Backup was made by a newer version of DeutschFlow.")
            }

            for (collection in listOf("vocabulary", "transcripts", "userStats", "activityLog")) {
                if (!backup.has(collection) || backup.optJSONArray(collection) == null) {
                    throw BackupException.InvalidFormat("Malformed backup: $collection must be an array.")
                }
            }

            val statsArray = backup.getJSONArray("userStats")
            if (statsArray.length() > MAX_USER_STATS_ROWS) {
                throw BackupException.InvalidFormat("User stats count exceeds limit ($MAX_USER_STATS_ROWS).")
            }

            // Validate vocabulary
            val vocabArray = backup.getJSONArray("vocabulary")
            val vocabIds = mutableSetOf<Int>()
            for (i in 0 until vocabArray.length()) {
                val row = vocabArray.optJSONObject(i) ?: throw BackupException.InvalidFormat("Vocabulary item must be an object.")
                val germanText = row.optString("germanText", "").trim()
                val translation = row.optString("englishTranslation", "").trim()
                if (germanText.isEmpty() || translation.isEmpty()) {
                    throw BackupException.InvalidFormat("Vocabulary text and translation are required.")
                }
                val id = row.optInt("id", 0)
                if (id > 0) {
                    if (vocabIds.contains(id)) throw BackupException.InvalidFormat("Duplicate vocabulary id.")
                    vocabIds.add(id)
                }
                val interval = row.optInt("interval", 0)
                val reviewCount = row.optInt("reviewCount", 0)
                val nextReview = row.optLong("nextReview", 0L)
                val easeFactor = row.optDouble("easeFactor", 2.5)

                if (interval < 0 || interval > 365) throw BackupException.InvalidFormat("Invalid SRS interval.")
                if (easeFactor < 1.3 || easeFactor > 3.0) throw BackupException.InvalidFormat("Invalid ease factor.")
                if (reviewCount < 0 || reviewCount > 100_000) throw BackupException.InvalidFormat("Invalid review count.")
                if (nextReview < 0 || nextReview > MAX_FUTURE_TIMESTAMP) throw BackupException.InvalidFormat("Invalid nextReview timestamp.")

                val isNew = interval == 0 && reviewCount == 0 && nextReview == 0L
                val isLearned = interval > 0 && reviewCount > 0 && nextReview > 0L
                if (!isNew && !isLearned) {
                    throw BackupException.InvalidFormat("Incoherent vocabulary SRS schedule.")
                }
            }

            // Validate transcripts
            val transcriptsArray = backup.getJSONArray("transcripts")
            for (i in 0 until transcriptsArray.length()) {
                val row = transcriptsArray.optJSONObject(i) ?: throw BackupException.InvalidFormat("Transcript item must be an object.")
                val fullText = row.optString("fullText", "").trim()
                if (fullText.isEmpty()) throw BackupException.InvalidFormat("Transcript text is required.")
                val ts = row.optLong("timestamp", 0L)
                if (ts < 0 || ts > MAX_FUTURE_TIMESTAMP) throw BackupException.InvalidFormat("Invalid transcript timestamp.")
            }

            // Validate userStats
            for (i in 0 until statsArray.length()) {
                val row = statsArray.optJSONObject(i) ?: throw BackupException.InvalidFormat("User stats item must be an object.")
                val xp = row.optInt("xp", 0)
                val streak = row.optInt("streak", 0)
                val ts = row.optLong("lastActivityTimestamp", 0L)
                if (xp < 0 || xp > 10_000_000) throw BackupException.InvalidFormat("Invalid XP in stats.")
                if (streak < 0 || streak > 10_000) throw BackupException.InvalidFormat("Invalid streak in stats.")
                if (ts < 0 || ts > MAX_FUTURE_TIMESTAMP) throw BackupException.InvalidFormat("Invalid timestamp in stats.")
            }

            // Validate activityLog
            val activityArray = backup.getJSONArray("activityLog")
            for (i in 0 until activityArray.length()) {
                val row = activityArray.optJSONObject(i) ?: throw BackupException.InvalidFormat("Activity log item must be an object.")
                val date = row.optString("date", "")
                if (!isValidCalendarDate(date)) {
                    throw BackupException.InvalidFormat("Invalid calendar date in activity log: \"$date\".")
                }
                val xpGained = row.optInt("xpGained", 0)
                val ts = row.optLong("timestamp", 0L)
                if (xpGained < 0 || xpGained > 100_000) throw BackupException.InvalidFormat("Invalid xpGained in activity log.")
                if (ts < 0 || ts > MAX_FUTURE_TIMESTAMP) throw BackupException.InvalidFormat("Invalid timestamp in activity log.")
            }

            // Validate reviewEvents
            if (backup.has("reviewEvents")) {
                val reviewArray = backup.optJSONArray("reviewEvents") ?: throw BackupException.InvalidFormat("reviewEvents must be an array.")
                for (i in 0 until reviewArray.length()) {
                    val row = reviewArray.optJSONObject(i) ?: throw BackupException.InvalidFormat("Review event must be an object.")
                    val vocabId = row.optInt("vocabularyId", -1)
                    if (vocabId <= 0 || !vocabIds.contains(vocabId)) {
                        throw BackupException.InvalidFormat("Review event references non-existent or invalid vocabulary ID: $vocabId.")
                    }
                    val rating = row.optString("rating", "")
                    if (rating !in listOf("AGAIN", "HARD", "GOOD", "EASY")) {
                        throw BackupException.InvalidFormat("Invalid review event rating.")
                    }
                    val sched = row.optInt("scheduledDays", 0)
                    val actual = row.optInt("actualDays", 0)
                    val reviewedAt = row.optLong("reviewedAtTimestamp", 0L)
                    val remoteId = row.optString("remoteId", "")
                    if (sched < 0 || sched > 365) throw BackupException.InvalidFormat("Invalid review scheduled days.")
                    if (actual < 0 || reviewedAt < 0 || reviewedAt > MAX_FUTURE_TIMESTAMP) throw BackupException.InvalidFormat("Invalid review timestamp.")
                    if (!UUID_REGEX.matches(remoteId)) throw BackupException.InvalidFormat("Invalid review event remoteId UUID.")
                }
            }

            // Validate roleplayMessages
            if (backup.has("roleplayMessages")) {
                val roleplayArray = backup.optJSONArray("roleplayMessages") ?: throw BackupException.InvalidFormat("roleplayMessages must be an array.")
                val positions = mutableSetOf<Int>()
                var scenario: String? = null
                for (i in 0 until roleplayArray.length()) {
                    val row = roleplayArray.optJSONObject(i) ?: throw BackupException.InvalidFormat("Roleplay message must be an object.")
                    val role = row.optString("role", "")
                    if (role !in listOf("user", "assistant")) throw BackupException.InvalidFormat("Invalid roleplay role.")
                    val content = row.optString("content", "").trim()
                    val scn = row.optString("scenario", "").trim()
                    if (content.isEmpty() || scn.isEmpty()) throw BackupException.InvalidFormat("Roleplay content and scenario are required.")
                    val pos = row.optInt("position", -1)
                    if (pos < 0 || positions.contains(pos)) throw BackupException.InvalidFormat("Invalid or duplicate roleplay message position.")
                    positions.add(pos)
                    if (scenario != null && scenario != scn) throw BackupException.InvalidFormat("Roleplay session contains mismatched scenarios.")
                    scenario = scn
                    val ts = row.optLong("timestamp", 0L)
                    if (ts < 0 || ts > MAX_FUTURE_TIMESTAMP) throw BackupException.InvalidFormat("Invalid roleplay timestamp.")
                }
            }
        }
    }
}
