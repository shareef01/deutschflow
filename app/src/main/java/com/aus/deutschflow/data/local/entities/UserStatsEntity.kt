package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Immutable
@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val id: Int = 1,
    val xp: Int = 0,
    val streak: Int = 0,
    val lastActivityTimestamp: Long = 0L
) {

    /**
     * The streak as it stands [now], which is what the screens should show.
     *
     * [streak] is only recalculated when XP is earned (StudyViewModel.nextStreak), so
     * on its own it is the streak as of the last study session. A user who studied ten
     * days running and then stopped saw "10 days" for as long as they stayed away - a
     * streak they no longer had, on the screen meant to motivate them to keep it.
     *
     * Still alive through the whole of the next calendar day, because studying today
     * would extend it; gone once a full day has been missed. Calendar days in [zone],
     * the same rule nextStreak applies when it extends or resets the stored value.
     */
    fun currentStreak(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (streak <= 0 || lastActivityTimestamp <= 0L) return 0
        val daysSince = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(lastActivityTimestamp).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        )
        return if (daysSince <= 1L) streak else 0
    }
}
