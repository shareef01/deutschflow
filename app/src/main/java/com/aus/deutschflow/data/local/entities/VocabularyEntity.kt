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
    indices = [Index(value = ["timestamp"])]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
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
    val exampleSentence: String = ""
)
