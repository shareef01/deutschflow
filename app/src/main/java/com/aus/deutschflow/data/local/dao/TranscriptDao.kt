package com.aus.deutschflow.data.local.dao

import androidx.room.*
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    fun getAllTranscripts(): Flow<List<TranscriptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscript(transcript: TranscriptEntity)

    @Delete
    suspend fun deleteTranscript(transcript: TranscriptEntity)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()
}
