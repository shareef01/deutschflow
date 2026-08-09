package com.aus.deutschflow.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val id: Int = 1,
    val xp: Int = 0,
    val streak: Int = 0,
    val lastActivityTimestamp: Long = 0L
)
