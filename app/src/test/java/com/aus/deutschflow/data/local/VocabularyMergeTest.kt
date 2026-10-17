package com.aus.deutschflow.data.local

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides what a word looks like after being met twice.
 *
 * A pure function over a plain data class, so it is tested here rather than through a
 * database - and it is worth testing on its own because MIGRATION_6_7 has to reproduce
 * it in SQL for the duplicates that already exist, and the two have to agree.
 */
class VocabularyMergeTest {

    private fun word(
        id: Int = 1,
        german: String = "Hund",
        english: String = "dog",
        timestamp: Long = 1_000,
        example: String = "",
        article: String = "",
        plural: String = "",
        conjugation: String = "",
        lastModifiedAt: Long = 1_000
    ) = VocabularyEntity(
        id = id,
        germanText = german,
        englishTranslation = english,
        timestamp = timestamp,
        exampleSentence = example,
        article = article,
        plural = plural,
        conjugation = conjugation,
        lastModifiedAt = lastModifiedAt
    )

    @Test
    fun `an interrogation fills in the grammar a hand-typed word lacked`() {
        val typed = word(english = "dog")
        val interrogated = word(
            id = 0,
            english = "dog",
            timestamp = 2_000,
            example = "Der Hund schläft.",
            article = "der",
            plural = "Hunde"
        )

        val merged = typed.mergedWith(interrogated)

        assertEquals("der", merged.article)
        assertEquals("Hunde", merged.plural)
        assertEquals("Der Hund schläft.", merged.exampleSentence)
    }

    @Test
    fun `a blank field in the newcomer never erases what was already known`() {
        val known = word(
            example = "Der Hund schläft.",
            article = "der",
            plural = "Hunde",
            conjugation = "laufen"
        )

        // A word saved again from a transcript carries none of the grammar.
        val merged = known.mergedWith(word(id = 0, example = "", article = "", plural = ""))

        assertEquals("der", merged.article)
        assertEquals("Hunde", merged.plural)
        assertEquals("laufen", merged.conjugation)
        assertEquals("Der Hund schläft.", merged.exampleSentence)
    }

    @Test
    fun `the surviving row keeps its own id and spelling`() {
        val existing = word(id = 7, german = "Hund")

        val merged = existing.mergedWith(word(id = 0, german = "hund"))

        assertEquals(7, merged.id)
        // Re-saving a word should not silently recapitalise it in the library.
        assertEquals("Hund", merged.germanText)
    }

    @Test
    fun `the later timestamp wins, whichever side it is on`() {
        assertEquals(2_000, word(timestamp = 1_000).mergedWith(word(timestamp = 2_000)).timestamp)
        // An older sighting must not drag a word back down the list.
        assertEquals(2_000, word(timestamp = 2_000).mergedWith(word(timestamp = 1_000)).timestamp)
    }

    @Test
    fun `a newcomer's translation replaces the old one when it has any`() {
        val merged = word(english = "dog").mergedWith(word(id = 0, english = "hound"))

        assertEquals("hound", merged.englishTranslation)
    }

    @Test
    fun `mergedWith advances lastModifiedAt to max of existing, incoming, and now`() {
        val existing = word(lastModifiedAt = 1_000)
        val incoming = word(id = 0, lastModifiedAt = 2_000)
        val now = 3_000L

        val merged = existing.mergedWith(incoming, now = now)
        assertEquals(3_000L, merged.lastModifiedAt)

        // If incoming has a higher lastModifiedAt than now
        val futureIncoming = word(id = 0, lastModifiedAt = 5_000)
        val mergedFuture = existing.mergedWith(futureIncoming, now = now)
        assertEquals(5_000L, mergedFuture.lastModifiedAt)

        // If existing has higher lastModifiedAt
        val pastIncoming = word(id = 0, lastModifiedAt = 500)
        val mergedPast = word(lastModifiedAt = 4_000).mergedWith(pastIncoming, now = 2_000)
        assertEquals(4_000L, mergedPast.lastModifiedAt)
    }

    /** The lookup's "not a noun" placeholder must not erase a real article. */
    @Test
    fun `a none article never overwrites a real one`() {
        val noun = word(german = "Essen", english = "food", article = "das")
        val verb = word(id = 0, german = "essen", english = "to eat", article = "none")

        assertEquals("das", noun.mergedWith(verb).article)
    }

    @Test
    fun `a real article still replaces a none left by an older build`() {
        val stale = word(article = "none")
        assertEquals("der", stale.mergedWith(word(id = 0, article = "der")).article)
    }

    // --- choosing a schedule: the same ranking MIGRATION_12_13 / 14_15 use -------

    private fun scheduled(reviewCount: Int, interval: Int, ease: Float = 2.5f, next: Long = 0) =
        word().copy(reviewCount = reviewCount, interval = interval, easeFactor = ease, nextReview = next)

    @Test
    fun `the further-along schedule is taken whole`() {
        val fresh = scheduled(reviewCount = 0, interval = 0)
        val studied = scheduled(reviewCount = 6, interval = 40, ease = 2.7f, next = 99)

        val result = fresh.withFurthestScheduleOf(studied)

        assertEquals(6, result.reviewCount)
        assertEquals(40, result.interval)
        assertEquals(2.7f, result.easeFactor, 0.0001f)
        assertEquals(99L, result.nextReview)
    }

    @Test
    fun `a less-studied schedule never replaces a further one`() {
        val studied = scheduled(reviewCount = 6, interval = 40)
        val result = studied.withFurthestScheduleOf(scheduled(reviewCount = 0, interval = 0))
        assertEquals(6, result.reviewCount)
        assertEquals(40, result.interval)
    }

    @Test
    fun `interval breaks a tie on review count, and a full tie keeps this row`() {
        val shorter = scheduled(reviewCount = 3, interval = 6, ease = 2.1f)
        val longer = scheduled(reviewCount = 3, interval = 15, ease = 2.4f)

        assertEquals(15, shorter.withFurthestScheduleOf(longer).interval)
        assertEquals(2.4f, shorter.withFurthestScheduleOf(longer).easeFactor, 0.0001f)

        val twin = scheduled(reviewCount = 3, interval = 6, ease = 1.9f)
        assertEquals(2.1f, shorter.withFurthestScheduleOf(twin).easeFactor, 0.0001f)
    }

    @Test
    fun `choosing a schedule leaves the content alone`() {
        val content = word(english = "dog", article = "der")
        val result = content.withFurthestScheduleOf(scheduled(reviewCount = 4, interval = 10).copy(englishTranslation = "x"))
        assertEquals("dog", result.englishTranslation)
        assertEquals("der", result.article)
    }

    @Test
    fun `preserves a recent lapse over an older mastered schedule`() {
        val matureSurvivor = scheduled(reviewCount = 5, interval = 30, ease = 2.6f, next = 200_000L)
            .copy(lastModifiedAt = 1000L)
        val recentLapse = scheduled(reviewCount = 0, interval = 0, ease = 2.4f, next = 0L)
            .copy(lastModifiedAt = 5000L)

        val merged = matureSurvivor.withFurthestScheduleOf(recentLapse, now = 5000L)
        assertEquals(0, merged.reviewCount)
        assertEquals(0, merged.interval)
        assertEquals(0L, merged.nextReview)
        assertEquals(2.4f, merged.easeFactor, 0.0001f)
    }

    @Test
    fun `preserves a recent remastering over an older lapse`() {
        val lapsedSurvivor = scheduled(reviewCount = 0, interval = 0, ease = 2.3f, next = 0L)
            .copy(lastModifiedAt = 1000L)
        val remastered = scheduled(reviewCount = 4, interval = 15, ease = 2.5f, next = 300_000L)
            .copy(lastModifiedAt = 5000L)

        val merged = lapsedSurvivor.withFurthestScheduleOf(remastered, now = 5000L)
        assertEquals(4, merged.reviewCount)
        assertEquals(15, merged.interval)
        assertEquals(300_000L, merged.nextReview)
        assertEquals(2.5f, merged.easeFactor, 0.0001f)
    }

    @Test
    fun `favors due or lapsed card when timestamps are tied to avoid burying review`() {
        val futureCard = scheduled(reviewCount = 3, interval = 20, ease = 2.5f, next = 150_000L)
            .copy(lastModifiedAt = 2000L)
        val dueCard = scheduled(reviewCount = 0, interval = 0, ease = 2.3f, next = 0L)
            .copy(lastModifiedAt = 2000L)

        val merged = futureCard.withFurthestScheduleOf(dueCard, now = 50_000L)
        assertEquals(0, merged.interval)
        assertEquals(0L, merged.nextReview)
        assertEquals(2.3f, merged.easeFactor, 0.0001f)
    }

    @Test
    fun `favors sooner review when both future schedules have tied timestamps`() {
        val furtherCard = scheduled(reviewCount = 4, interval = 25, ease = 2.6f, next = 250_000L)
            .copy(lastModifiedAt = 1000L)
        val soonerCard = scheduled(reviewCount = 3, interval = 10, ease = 2.5f, next = 100_000L)
            .copy(lastModifiedAt = 1000L)

        val merged = furtherCard.withFurthestScheduleOf(soonerCard, now = 50_000L)
        assertEquals(100_000L, merged.nextReview)
        assertEquals(10, merged.interval)
    }

    @Test
    fun `ensures unreviewed content edits never wipe out existing study progress`() {
        val studiedSurvivor = scheduled(reviewCount = 5, interval = 30, ease = 2.6f, next = 200_000L)
            .copy(lastModifiedAt = 1000L)
        val unreviewedEdit = scheduled(reviewCount = 0, interval = 0, ease = 2.5f, next = 0L)
            .copy(lastModifiedAt = 9000L)

        val merged = studiedSurvivor.withFurthestScheduleOf(unreviewedEdit, now = 9000L)
        assertEquals(5, merged.reviewCount)
        assertEquals(30, merged.interval)
        assertEquals(200_000L, merged.nextReview)
    }
}
