package com.aus.deutschflow.data.local.dao

import androidx.room.*
import com.aus.deutschflow.data.local.entities.ARCHIVED_VOCABULARY_KEY
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.data.local.entities.germanKey
import kotlinx.coroutines.flow.Flow

/** The row [VocabularyDao.masteryCounts] returns; its column names are these fields. */
data class MasteryCounts(
    val totalWords: Int,
    val masteredWords: Int,
    val learningWords: Int,
    val newWords: Int
)

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary WHERE germanTextKey != '__archived_deleted__' ORDER BY timestamp DESC")
    fun getAllVocabulary(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE germanTextKey != '__archived_deleted__' ORDER BY timestamp DESC")
    suspend fun getAllVocabularyList(): List<VocabularyEntity>

    /** How many, without reading any of them. See TranscriptDao.countTranscripts. */
    @Query("SELECT COUNT(*) FROM vocabulary WHERE germanTextKey != '__archived_deleted__'")
    fun countVocabulary(): Flow<Int>

    /**
     * The words ready for review: those whose nextReview has passed.
     *
     * Scheduled reviews come first, in the order they fell due; words never answered
     * (nextReview = 0) follow, newest first. Sorting on nextReview alone put every
     * unseen word ahead of every review, so a library with a backlog of new material
     * buried the reviews it was the scheduler's whole job to surface. A review has a
     * date it is owed on; new material does not.
     *
     * Archived placeholder rows (__archived_deleted__) are strictly excluded so they never
     * enter the study queue.
     */
    @Query(
        "SELECT * FROM vocabulary WHERE germanTextKey != '__archived_deleted__' AND nextReview <= :currentTime " +
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

    /**
     * The row holding this word, whatever case or umlaut spelling it was written in.
     *
     * Matched on the folded key rather than on germanText's NOCASE collation, which
     * only ever folded ASCII - see [VocabularyEntity.germanTextKey].
     */
    @Query("SELECT * FROM vocabulary WHERE germanTextKey = :key LIMIT 1")
    suspend fun findByKey(key: String): VocabularyEntity?

    /** Convenience for callers holding the word as the user wrote it. */
    suspend fun findByGermanText(germanText: String): VocabularyEntity? =
        findByKey(germanKey(germanText))

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM vocabulary WHERE germanTextKey != '__archived_deleted__'")
    suspend fun countOnce(): Int

    @Query("SELECT * FROM vocabulary WHERE germanTextKey != '__archived_deleted__' ORDER BY id ASC LIMIT 1 OFFSET :offset")
    suspend fun nthById(offset: Int): VocabularyEntity?

    /**
     * The word of the day for [wordDay], read as one row rather than the library.
     *
     * The same choice as DailyWord.select - position `wordDay mod size` in id order -
     * but without materialising every row to make it. DailyWord used to load the whole
     * library for this, and it runs on every widget redraw, which follows every write
     * to the library. One transaction, so the count and the fetch see the same table.
     */
    @Transaction
    suspend fun wordOfTheDay(wordDay: Long): VocabularyEntity? {
        val size = countOnce()
        if (size == 0) return null
        return nthById(Math.floorMod(wordDay, size.toLong()).toInt())
    }

    /**
     * The library's mastery split, counted by SQLite rather than by loading every row.
     *
     * The Dashboard used to map the whole table on every change to it. The buckets are
     * those DashboardViewModel defines: never answered is new; answered and on an
     * interval of [masteredDays] or more is mastered; answered on a shorter one is
     * learning - one axis, so they always sum to the total.
     */
    @Query(
        "SELECT COUNT(*) AS totalWords, " +
            "COALESCE(SUM(CASE WHEN reviewCount > 0 AND `interval` >= :masteredDays THEN 1 ELSE 0 END), 0) AS masteredWords, " +
            "COALESCE(SUM(CASE WHEN reviewCount > 0 AND `interval` < :masteredDays THEN 1 ELSE 0 END), 0) AS learningWords, " +
            "COALESCE(SUM(CASE WHEN reviewCount = 0 THEN 1 ELSE 0 END), 0) AS newWords " +
            "FROM vocabulary WHERE germanTextKey != '__archived_deleted__'"
    )
    fun masteryCounts(masteredDays: Int): Flow<MasteryCounts>

    /** The row as it is now, for callers holding a snapshot that may have gone stale. */
    @Query("SELECT * FROM vocabulary WHERE id = :id")
    suspend fun findById(id: Int): VocabularyEntity?

    /**
     * Writes a review's outcome and nothing else.
     *
     * A review must not be a full-row `@Update`: Study holds the deck as snapshots
     * taken when the sitting began, and writing one of those back reverted any edit
     * the user had made in the library since - the word, its translation, its grammar.
     *
     * @return the number of rows changed; 0 when the word no longer exists.
     */
    @Query(
        "UPDATE vocabulary SET nextReview = :nextReview, `interval` = :interval, " +
            "easeFactor = :easeFactor, reviewCount = :reviewCount, lastModifiedAt = :lastModifiedAt " +
            "WHERE id = :id"
    )
    suspend fun updateSchedule(
        id: Int,
        nextReview: Long,
        interval: Int,
        easeFactor: Float,
        reviewCount: Int,
        lastModifiedAt: Long
    ): Int

    /**
     * Moves a word's review history onto another row.
     *
     * review_events carries no foreign key, so nothing else keeps a card's history
     * attached when its id changes - and a merge or an undo is exactly that.
     */
    @Query("UPDATE review_events SET vocabularyId = :to WHERE vocabularyId = :from")
    suspend fun reassignReviewEvents(from: Int, to: Int)

    /**
     * Puts a deleted word back, for the library's Undo.
     *
     * Under its original id when that is possible, which it always is unless another
     * row has claimed the word since: the table is AUTOINCREMENT, so a freed id is
     * never handed out again. Keeping the id is what keeps the word's review history -
     * deletion leaves the events in place for exactly this - attached to it. Restoring
     * under a fresh id, as Undo used to, cut the word off from its own past.
     *
     * If the word was saved again in the meantime, the two are folded together as
     * [save] would, and the history follows the survivor.
     */
    @Transaction
    suspend fun restore(entry: VocabularyEntity) {
        val now = System.currentTimeMillis()
        val key = germanKey(entry.germanText)
        val occupant = findByKey(key)

        when {
            occupant == null && entry.id != 0 && findById(entry.id) == null ->
                insertVocabulary(
                    entry.copy(germanTextKey = key, lastModifiedAt = maxOf(entry.lastModifiedAt, now))
                )

            occupant == null -> save(entry.copy(id = 0))

            else -> {
                save(entry.copy(id = 0))
                if (entry.id != 0) reassignReviewEvents(from = entry.id, to = occupant.id)
            }
        }
    }

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
    suspend fun save(entry: VocabularyEntity) {
        val key = germanKey(entry.germanText)
        if (key == ARCHIVED_VOCABULARY_KEY || entry.germanTextKey == ARCHIVED_VOCABULARY_KEY) {
            // The internal archived identity is reserved; user actions cannot claim or overwrite it.
            return
        }

        // Recomputed here rather than trusted from the caller: the key is derived
        // from germanText, and `copy(germanText = ...)` on the edit path would
        // otherwise carry the old word's key into the new one.
        val now = System.currentTimeMillis()
        val vocabulary = entry.copy(
            germanTextKey = key,
            lastModifiedAt = maxOf(entry.lastModifiedAt, now)
        )
        val existing = findByKey(vocabulary.germanTextKey)
        if (existing != null && existing.germanTextKey == ARCHIVED_VOCABULARY_KEY) {
            // Cannot merge user vocabulary into the internal archived entity.
            return
        }

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
            //
            // A rename takes its learning with it: the survivor keeps whichever of the
            // two schedules is further along, and the departing row's review history
            // is moved onto the survivor before the row is deleted. Keeping the
            // survivor's schedule unconditionally threw away months of progress
            // whenever the renamed card was the one being studied.
            else -> {
                if (vocabulary.id != 0) {
                    reassignReviewEvents(from = vocabulary.id, to = existing.id)
                    deleteById(vocabulary.id)
                }
                updateVocabulary(
                    existing.mergedWith(vocabulary, now).withFurthestScheduleOf(vocabulary)
                )
            }
        }
    }
}
