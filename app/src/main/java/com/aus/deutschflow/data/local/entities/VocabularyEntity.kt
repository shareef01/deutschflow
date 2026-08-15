package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable so Compose treats the vocabulary list as skippable when it is handed to a
 * composable unchanged; the DAO only ever swaps whole entities, never mutates one.
 */
@Immutable
@Entity(
    tableName = "vocabulary",
    indices = [
        Index(value = ["timestamp"]),
        // One row per word. Saving a word already in the library used to mint a second
        // copy of it - easy to do now that a chip saves with one tap - and the copies
        // then showed up as repeat cards in Study, inflated the count in Settings, and
        // weighted that word in the daily rotation.
        Index(value = ["germanText"], unique = true)
    ]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /**
     * NOCASE so "hund" and "Hund" are the same entry rather than two.
     *
     * SQLite's NOCASE folds ASCII only, so it does not equate "Äpfel" with "äpfel".
     * That is the limit of what the built-in collations offer without shipping ICU,
     * and it costs little here: German capitalises its nouns, and the words arrive
     * either from the model, which spells them correctly, or from a user typing the
     * word they mean.
     */
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val germanText: String,
    val englishTranslation: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * The example the model produced for this word, or empty for a word typed in by
     * hand. The detail screen falls back to a generated sentence when it is empty.
     *
     * The SQL default is declared as well as the Kotlin one so that a fresh install
     * and a database upgraded by MIGRATION_2_3 end up with identical DDL - a Kotlin
     * default alone is invisible to SQLite, which would leave the two diverging.
     */
    @ColumnInfo(defaultValue = "''")
    val exampleSentence: String = "",
    /**
     * The grammatical article ("der"/"die"/"das"/"none"), plural form and, for verbs,
     * the infinitive - filled by the single-word interrogation and empty for words
     * typed by hand. SQL defaults declared so a fresh install and the migrated
     * database share identical DDL.
     */
    @ColumnInfo(defaultValue = "''")
    val article: String = "",
    @ColumnInfo(defaultValue = "''")
    val plural: String = "",
    @ColumnInfo(defaultValue = "''")
    val conjugation: String = ""
) {

    /**
     * Folds [incoming] into this row, which is the one that survives.
     *
     * A word can be met more than once and learned a bit more each time: typed by hand
     * first, then interrogated, which is where the article and plural come from. So a
     * field the newcomer fills in wins, and a field it leaves blank keeps whatever was
     * already known - the merge can only ever add to what the row holds.
     *
     * [id] and [germanText] stay as they are: this row's identity is not up for
     * negotiation, and re-saving a word should not silently recapitalise it in the
     * library. [timestamp] takes the later of the two, so a word touched again surfaces
     * at the top of the list where the user just put it.
     */
    fun mergedWith(incoming: VocabularyEntity): VocabularyEntity = copy(
        englishTranslation = incoming.englishTranslation.ifBlank { englishTranslation },
        exampleSentence = incoming.exampleSentence.ifBlank { exampleSentence },
        article = incoming.article.ifBlank { article },
        plural = incoming.plural.ifBlank { plural },
        conjugation = incoming.conjugation.ifBlank { conjugation },
        timestamp = maxOf(timestamp, incoming.timestamp)
    )
}
