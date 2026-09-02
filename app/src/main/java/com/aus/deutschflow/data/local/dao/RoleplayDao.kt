package com.aus.deutschflow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aus.deutschflow.data.local.entities.RoleplayMessageEntity

@Dao
interface RoleplayDao {

    /**
     * The saved conversation, oldest turn first — the order the chat renders in.
     *
     * A one-shot read rather than a Flow: the ViewModel owns the live list and
     * writes to this table, so observing it would feed the ViewModel its own
     * writes back and fight the optimistic append the UI depends on.
     */
    @Query("SELECT * FROM roleplay_messages ORDER BY position ASC")
    suspend fun getConversation(): List<RoleplayMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: RoleplayMessageEntity)

    /**
     * Starting a new scenario clears the old one.
     *
     * Only the current conversation is kept. There is no history UI to browse
     * past sessions, so retaining them would grow a table nothing reads and
     * nothing prunes.
     */
    @Query("DELETE FROM roleplay_messages")
    suspend fun deleteAll()
}
