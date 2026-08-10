package com.aus.deutschflow.data.local.dao

import androidx.room.*
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserStatsDao {
    @Query("SELECT * FROM user_stats WHERE id = 1")
    fun getUserStats(): Flow<UserStatsEntity?>

    /**
     * One-shot read for the read-modify-write in StudyViewModel.rewardXP.
     *
     * Collecting [getUserStats] there would run the query on Room's own query
     * executor rather than the caller's transaction, so the read could see outside
     * the transaction it is meant to be inside.
     */
    @Query("SELECT * FROM user_stats WHERE id = 1")
    suspend fun getUserStatsOnce(): UserStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stats: UserStatsEntity)

    @Query("DELETE FROM user_stats")
    suspend fun deleteAll()
}
