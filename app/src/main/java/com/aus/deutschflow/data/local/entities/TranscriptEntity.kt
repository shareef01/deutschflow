package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "transcripts",
    indices = [Index(value = ["timestamp"])]
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fullText: String,
    val timestamp: Long = System.currentTimeMillis()
)
