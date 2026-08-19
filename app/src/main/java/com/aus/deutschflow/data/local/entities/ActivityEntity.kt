package com.aus.deutschflow.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records daily XP gains for visualization in the Mastery Dashboard.
 * [date] is stored as a string in "YYYY-MM-DD" format for easy daily grouping.
 */
@Entity(tableName = "activity_log")
data class ActivityEntity(
    @PrimaryKey val date: String,
    val xpGained: Int,
    val timestamp: Long = System.currentTimeMillis()
)
