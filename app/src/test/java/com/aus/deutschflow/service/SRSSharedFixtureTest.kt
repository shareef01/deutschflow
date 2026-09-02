package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler, asserted against the table the TypeScript suite also reads.
 *
 * [SRSEngine] and web/src/lib/ai/srs.ts are the same algorithm written twice, by
 * hand, in two languages - and they had already drifted once without anyone
 * noticing, because "mirror of <path>" in a comment is not a check. Colour tokens
 * are the one shared rule in this repository with a machine check
 * (tools/palette_parity.py), and colour is the one shared rule that has not
 * drifted. This is the same mechanism, for the rule that matters most: a card
 * scheduled differently on the phone and in the browser is a bug no screenshot
 * would ever show.
 *
 * The fixture lives in this module's test resources and web/tests reads it across
 * the tree. One file, not two: a copy would be a third thing to keep in step.
 */
class SRSSharedFixtureTest {

    private val engine = SRSEngine()

    @Test
    fun everyCaseInTheSharedFixtureHolds() {
        val fixture = javaClass.classLoader!!
            .getResourceAsStream("srs-fixture.json")!!
            .bufferedReader()
            .use { it.readText() }

        val cases = JSONObject(fixture).getJSONArray("cases")
        assertTrue("the fixture has cases", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val given = case.getJSONObject("given")
            val expected = case.getJSONObject("expect")

            val result = engine.calculateNextReview(
                VocabularyEntity(
                    germanText = "das Haus",
                    englishTranslation = "the house",
                    interval = given.getInt("interval"),
                    easeFactor = given.getDouble("easeFactor").toFloat(),
                    reviewCount = given.getInt("reviewCount")
                ),
                ReviewQuality.valueOf(case.getString("rating"))
            )

            assertEquals("$name — interval", expected.getInt("interval"), result.interval)
            assertEquals(
                "$name — easeFactor",
                expected.getDouble("easeFactor").toFloat(),
                result.easeFactor,
                0.0001f
            )
            assertEquals("$name — reviewCount", expected.getInt("reviewCount"), result.reviewCount)
        }
    }
}
