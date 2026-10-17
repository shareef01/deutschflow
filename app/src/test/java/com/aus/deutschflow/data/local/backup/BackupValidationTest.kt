package com.aus.deutschflow.data.local.backup

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
