package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Immutable
@Entity(
    tableName = "review_events",
    indices = [
        Index(value = ["vocabularyId"]),
        Index(value = ["reviewedAtTimestamp"])
    ]
)
data class ReviewEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vocabularyId: Int,
    val rating: String, // "AGAIN", "HARD", "GOOD", "EASY"
    val scheduledDays: Int,
    val actualDays: Int,
    val reviewedAtTimestamp: Long,
    val isExtraPractice: Boolean,
    @ColumnInfo(defaultValue = "''")
    val remoteId: String = UUID.randomUUID().toString()
)
