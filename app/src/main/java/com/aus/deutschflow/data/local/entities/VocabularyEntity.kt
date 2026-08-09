package com.aus.deutschflow.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val germanText: String,
    val englishTranslation: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)
