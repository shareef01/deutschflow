package com.aus.deutschflow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aus.deutschflow.data.local.entities.ReviewEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ReviewEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ReviewEventEntity>)

    @Query("SELECT * FROM review_events ORDER BY reviewedAtTimestamp DESC")
    fun getAllEvents(): Flow<List<ReviewEventEntity>>

    @Query("SELECT * FROM review_events WHERE vocabularyId = :vocabId ORDER BY reviewedAtTimestamp DESC")
    fun getEventsForWord(vocabId: Int): Flow<List<ReviewEventEntity>>

    @Query("DELETE FROM review_events")
    suspend fun deleteAll()
}
