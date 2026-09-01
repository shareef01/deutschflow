package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Folds a German word to the form all its spellings share.
 *
 * Full Unicode case folding, then the standard transliteration for keyboards
 * without umlauts - ue for ü, oe for ö, ae for ä, ss for ß - which is what anyone
 * typing German on an English keyboard produces, and what the recogniser's output
 * has to match.
 *
 * `lowercase()` with no locale is deliberate: it is locale-invariant in Kotlin, and
 * a default-locale one would map I to a dotless ı under a Turkish locale and stop
 * matching. Mirrors foldGermanKey in web/src/lib/db/schema.ts.
 */
fun germanKey(text: String): String = text.trim().lowercase()
    .replace("ä", "ae")
    .replace("ö", "oe")
    .replace("ü", "ue")
    .replace("ß", "ss")

/**
 * Immutable so Compose treats the vocabulary list as skippable when it is handed to a
 * composable unchanged; the DAO only ever swaps whole entities, never mutates one.
 */
@Immutable
@Entity(
    tableName = "vocabulary",
    indices = [
        Index(value = ["timestamp"]),
        // Uniqueness lives on the folded key, not on the word as written - see
        // [germanTextKey]. germanText keeps its NOCASE collation because search and
        // ordering still read it.
        Index(value = ["germanTextKey"], unique = true),
        // Index for the SRS engine: the study screen only wants cards ready for review.
        Index(value = ["nextReview"])
    ]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val germanText: String,
    /**
     * The word folded to the form all its spellings share, and the column uniqueness
     * is enforced on.
     *
     * Uniqueness used to be SQLite's NOCASE collation on [germanText], and NOCASE
     * folds ASCII A-Z and nothing else - so "Hund" and "hund" were one word while
     * "Übung" and "übung" were two, as were "Öl"/"öl" and "Ärger"/"ärger". Every
     * German noun beginning with an umlaut escaped deduplication, which is a poor
     * showing for an app about German. The fold here is the same one Practice has
     * always used for scoring ([PracticeViewModel.foldGerman]), so the app finally
     * answers "are these the same word" one way instead of two - and it folds
     * Straße to Strasse, which is the correct German equivalence.
     *
     * Derived, never entered. [VocabularyDao.save] recomputes it on every write, so
     * a `copy(germanText = ...)` that forgets to update it cannot store a stale key.
     */
    @ColumnInfo(defaultValue = "''")
    val germanTextKey: String = germanKey(germanText),
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
