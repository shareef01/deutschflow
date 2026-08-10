package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one word the app is showing today.
 *
 * The widget and the notification each used to call `randomOrNull()` on the library,
 * independently. So the two never agreed on what "the" word was, and because the
 * widget is redrawn after every library write, its "WORD OF THE DAY" also changed
 * whenever the user added or deleted anything.
 */
@Singleton
class DailyWord @Inject constructor(
    private val vocabularyDao: VocabularyDao
) {

    suspend fun today(): VocabularyEntity? = select(
        words = vocabularyDao.getAllVocabulary().firstOrNull().orEmpty(),
        epochDay = LocalDate.now().toEpochDay()
    )

    companion object {

        /**
         * Picks by date rather than at random, so every caller on a given day picks
         * the same word and picks it again on every redraw.
         *
         * Ordered by id rather than taking the list as it arrives: the query sorts by
         * timestamp, and two words saved in the same millisecond have no guaranteed
         * order between them.
         *
         * Editing the library can still move today's word, because the choice is
         * derived from the size of the list rather than stored. That is the trade for
         * holding no state - and a word that moves when the user edits the library is
         * a far smaller lie than one that moves on every redraw.
         */
        internal fun select(words: List<VocabularyEntity>, epochDay: Long): VocabularyEntity? {
            if (words.isEmpty()) return null

            val ordered = words.sortedBy { it.id }
            return ordered[Math.floorMod(epochDay, ordered.size.toLong()).toInt()]
        }
    }
}
