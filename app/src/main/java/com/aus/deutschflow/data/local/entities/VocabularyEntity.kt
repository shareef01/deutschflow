package com.aus.deutschflow.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val germanText: String,
    val englishTranslation: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    /**
     * The example Gemini produced for this word, or empty for a word typed in by
     * hand. The detail screen falls back to a generated sentence when it is empty.
     *
     * The SQL default is declared as well as the Kotlin one so that a fresh install
     * and a database upgraded by MIGRATION_2_3 end up with identical DDL - a Kotlin
     * default alone is invisible to SQLite, which would leave the two diverging.
     */
    @ColumnInfo(defaultValue = "''")
    val exampleSentence: String = ""
)
