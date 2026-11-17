package com.aus.deutschflow.data.local.dao

import androidx.room.*
import com.aus.deutschflow.data.local.entities.ActivityEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID

@Dao
interface ActivityDao {
    /**
     * The days the heatmap can actually draw, newest first.
     *
     * Bounded: the card shows a fixed window, and an unbounded read grows with the
     * table forever - a year of daily study is a row a day, and none of the older
     * ones ever reach the screen.
     */
    @Query("SELECT * FROM activity_log WHERE date >= :since ORDER BY date DESC")
    fun getActivitySince(since: String): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity_log ORDER BY date ASC")
    suspend fun getAllActivity(): List<ActivityEntity>

    @Query("SELECT * FROM activity_log WHERE date = :date LIMIT 1")
    suspend fun getActivityForDate(date: String): ActivityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(activity: ActivityEntity)

    @Transaction
    suspend fun addXp(date: String, amount: Int) {
        val current = getActivityForDate(date)
        val awards = current?.xpAwardsJson?.let(::JSONObject) ?: JSONObject()
        awards.put(UUID.randomUUID().toString(), amount)
        if (current == null) {
            insertOrUpdate(ActivityEntity(date = date, xpGained = amount, xpAwardsJson = awards.toString()))
        } else {
            insertOrUpdate(current.copy(xpGained = current.xpGained + amount, xpAwardsJson = awards.toString()))
        }
    }

    @Query("DELETE FROM activity_log")
    suspend fun deleteAll()
}
