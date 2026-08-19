package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Immutable
@Entity(
    tableName = "transcripts",
    indices = [Index(value = ["timestamp"])]
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fullText: String,
    val timestamp: Long = System.currentTimeMillis(),
    
    // Cloud Sync fields — The Bridge
    /** UUID for cross-device identity. */
    val remoteId: String = UUID.randomUUID().toString(),
    /** When this record was last touched. */
    val lastModifiedAt: Long = System.currentTimeMillis()
)
