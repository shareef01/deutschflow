package com.aus.deutschflow.data.local.dao

import androidx.room.*
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY timestamp DESC")
    fun getAllVocabulary(): Flow<List<VocabularyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVocabulary(vocabulary: VocabularyEntity)

    @Delete
    suspend fun deleteVocabulary(vocabulary: VocabularyEntity)

    @Update
    suspend fun updateVocabulary(vocabulary: VocabularyEntity)
}
