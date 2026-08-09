package com.aus.deutschflow.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fullText: String,
    val timestamp: Long = System.currentTimeMillis()
)
