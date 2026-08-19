package com.aus.deutschflow.data.local.dao

import androidx.room.*
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY timestamp DESC")
    fun getAllVocabulary(): Flow<List<VocabularyEntity>>

    /**
     * The words ready for review: those whose nextReview has passed.
     *
     * Scheduled reviews come first, in the order they fell due; words never answered
     * (nextReview = 0) follow, newest first. Sorting on nextReview alone put every
     * unseen word ahead of every review, so a library with a backlog of new material
     * buried the reviews it was the scheduler's whole job to surface. A review has a
     * date it is owed on; new material does not.
     */
    @Query(
        "SELECT * FROM vocabulary WHERE nextReview <= :currentTime " +
            "ORDER BY CASE WHEN nextReview = 0 THEN 1 ELSE 0 END, nextReview ASC, timestamp DESC"
    )
    fun getDueVocabulary(currentTime: Long): Flow<List<VocabularyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVocabulary(vocabulary: VocabularyEntity)

    @Delete
    suspend fun deleteVocabulary(vocabulary: VocabularyEntity)

    @Update
    suspend fun updateVocabulary(vocabulary: VocabularyEntity)

    @Query("DELETE FROM vocabulary")
    suspend fun deleteAll()

    /** NOCASE, because the column is: "hund" finds the row saved as "Hund". */
    @Query("SELECT * FROM vocabulary WHERE germanText = :germanText LIMIT 1")
    suspend fun findByGermanText(germanText: String): VocabularyEntity?

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun deleteById(id: Int)

    /**
     * The one way a word enters or changes in the library.
     *
     * `germanText` is unique, so a plain insert or update would throw the moment a word
     * was saved twice - or edited into a name another row already holds. Rather than let
     * that reach the user as a crash, or drop one of the two rows, the collision is
     * resolved by folding them together: see [VocabularyEntity.mergedWith].
     *
     * One transaction, because the read decides what the write does. Two callers saving
     * the same word at once would otherwise both find nothing and both insert.
     */
    @Transaction
    suspend fun save(vocabulary: VocabularyEntity) {
        val existing = findByGermanText(vocabulary.germanText)

        when {
            // Nothing holds that word yet: a new entry, or a rename onto a free name.
            existing == null ->
                if (vocabulary.id == 0) insertVocabulary(vocabulary)
                else updateVocabulary(vocabulary)

            // The row already is this one. An edit the user typed wins outright; it is
            // a correction, not a second sighting of the word.
            existing.id == vocabulary.id -> updateVocabulary(vocabulary)

            // Another row owns the word. Fold into it and let the newcomer go - which
            // for a save is a row that never existed, and for a rename is the row being
            // renamed onto its new twin.
            else -> {
                if (vocabulary.id != 0) deleteById(vocabulary.id)
                updateVocabulary(existing.mergedWith(vocabulary))
            }
        }
    }
}
