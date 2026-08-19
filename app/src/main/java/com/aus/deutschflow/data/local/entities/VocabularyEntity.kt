package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Immutable so Compose treats the vocabulary list as skippable when it is handed to a
 * composable unchanged; the DAO only ever swaps whole entities, never mutates one.
 */
@Immutable
@Entity(
    tableName = "vocabulary",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["germanText"], unique = true),
        // Index for the SRS engine: the study screen only wants cards ready for review.
        Index(value = ["nextReview"])
    ]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val germanText: String,
    val englishTranslation: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''")
    val exampleSentence: String = "",
    @ColumnInfo(defaultValue = "''")
    val article: String = "",
    @ColumnInfo(defaultValue = "''")
    val plural: String = "",
    @ColumnInfo(defaultValue = "''")
    val conjugation: String = "",
    @ColumnInfo(defaultValue = "''")
    val synonyms: String = "",
    @ColumnInfo(defaultValue = "''")
    val antonyms: String = "",

    // Spaced Repetition (SRS) fields — Ebbinghaus Engine
    /** Timestamp when the word is next due for review. 0 means it's a new word. */
    @ColumnInfo(defaultValue = "0")
    val nextReview: Long = 0,
    /** Current interval in days between reviews. */
    @ColumnInfo(defaultValue = "0")
    val interval: Int = 0,
    /** The difficulty multiplier (SuperMemo-2 style). Defaults to 2.5. */
    @ColumnInfo(defaultValue = "2.5")
    val easeFactor: Float = 2.5f,
    /** How many times this word has been successfully reviewed. */
    @ColumnInfo(defaultValue = "0")
    val reviewCount: Int = 0,

    // Cloud Sync fields — The Bridge
    /** UUID for cross-device identity. */
    @ColumnInfo(defaultValue = "''")
    val remoteId: String = UUID.randomUUID().toString(),
    /** When this record was last touched, to decide which copy wins a sync. */
    @ColumnInfo(defaultValue = "0")
    val lastModifiedAt: Long = System.currentTimeMillis()
) {

    fun mergedWith(incoming: VocabularyEntity): VocabularyEntity = copy(
        englishTranslation = incoming.englishTranslation.ifBlank { englishTranslation },
        exampleSentence = incoming.exampleSentence.ifBlank { exampleSentence },
        article = incoming.article.ifBlank { article },
        plural = incoming.plural.ifBlank { plural },
        conjugation = incoming.conjugation.ifBlank { conjugation },
        synonyms = incoming.synonyms.ifBlank { synonyms },
        antonyms = incoming.antonyms.ifBlank { antonyms },
        timestamp = maxOf(timestamp, incoming.timestamp),
        // SRS data is usually kept from the existing row unless the incoming one
        // explicitly has newer progress (which wouldn't happen in the current UI).
        nextReview = nextReview,
        interval = interval,
        easeFactor = easeFactor,
        reviewCount = reviewCount
    )
}
