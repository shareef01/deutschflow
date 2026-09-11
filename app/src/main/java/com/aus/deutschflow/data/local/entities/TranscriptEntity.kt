package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
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
    
    // Stable identity and modification metadata retained for data portability.
    //
    // The SQL defaults are not decoration. Without them Room's CREATE TABLE for a
    // fresh install carries no DEFAULT while MIGRATION_10_11 adds one, so an
    // upgraded database and a newly created one disagree on the same version -
    // and any insert that omits the column succeeds on one and throws
    // SQLITE_CONSTRAINT_NOTNULL on the other. VocabularyEntity declares its own
    // for the same reason.
    /** UUID that is independent from the local Room id. */
    @ColumnInfo(defaultValue = "''")
    val remoteId: String = UUID.randomUUID().toString(),
    /** When this record was last touched. */
    @ColumnInfo(defaultValue = "0")
    val lastModifiedAt: Long = System.currentTimeMillis(),
    /** Cached translation from AI. */
    @ColumnInfo(defaultValue = "''")
    val translation: String = "",
    /** Cached structured analysis (keywords, grammar notes) from AI. */
    @ColumnInfo(defaultValue = "''")
    val analysisJson: String = ""
)
