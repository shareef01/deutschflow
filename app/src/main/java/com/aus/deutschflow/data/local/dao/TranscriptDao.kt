package com.aus.deutschflow.data.local.dao

import androidx.room.*
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    fun getAllTranscripts(): Flow<List<TranscriptEntity>>

    /**
     * How many, without reading any of them.
     *
     * Four call sites wanted a count or an emptiness check and each subscribed to
     * the full list to get it - so every recorded utterance re-read and
     * re-materialised every transcript's text, on the screen the user was actively
     * speaking into.
     */
    @Query("SELECT COUNT(*) FROM transcripts")
    fun countTranscripts(): Flow<Int>

    /** Whether there is anything at all. Stops at the first row. */
    @Query("SELECT EXISTS(SELECT 1 FROM transcripts)")
    fun hasAnyTranscript(): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscript(transcript: TranscriptEntity)

    @Delete
    suspend fun deleteTranscript(transcript: TranscriptEntity)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()
}
