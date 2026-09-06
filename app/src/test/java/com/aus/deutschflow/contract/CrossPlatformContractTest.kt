package com.aus.deutschflow.contract

import com.aus.deutschflow.data.local.entities.germanKey
import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.SRSEngine
import com.aus.deutschflow.ui.viewmodel.PracticeViewModel
import com.aus.deutschflow.ui.viewmodel.StudyViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class CrossPlatformContractTest {

    private fun loadContract(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("cross-platform-contract.json")
            ?: error("Missing cross-platform-contract.json in test resources")
        val content = InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
        return JSONObject(content)
    }

    @Test
    fun `germanKey matches contract fixture`() {
        val contract = loadContract()
        val cases = contract.getJSONArray("germanKeyCases")
        assertTrue("Has germanKey cases", cases.length() > 0)

        for (i in 0 until cases.length()) {
            val caseObj = cases.getJSONObject(i)
            val input = caseObj.getString("input")
            val expected = caseObj.getString("expected")
            assertEquals("German key for $input", expected, germanKey(input))
        }
    }

    @Test
    fun `SRS and XP constants match contract fixture`() {
        val contract = loadContract()
        val srs = contract.getJSONObject("srsConstants")

        assertEquals(srs.getDouble("MIN_EASE_FACTOR").toFloat(), SRSEngine.MIN_EASE_FACTOR, 0.0001f)
        assertEquals(srs.getDouble("MAX_EASE_FACTOR").toFloat(), SRSEngine.MAX_EASE_FACTOR, 0.0001f)
        assertEquals(srs.getInt("MAX_INTERVAL_DAYS"), SRSEngine.MAX_INTERVAL_DAYS)
        assertEquals(srs.getInt("XP_PER_CARD"), StudyViewModel.XP_PER_CARD)
        assertEquals(srs.getInt("DAILY_XP_GOAL"), StudyViewModel.DAILY_XP_GOAL)
    }

    @Test
    fun `AI constants match contract fixture`() {
        val contract = loadContract()
        val ai = contract.getJSONObject("aiConstants")

        assertEquals(ai.getString("MODEL_NAME"), GroqHelper.MODEL_NAME)
        assertEquals(ai.getInt("MAX_AI_INPUT_CHARS"), GroqHelper.MAX_AI_INPUT_CHARS)
        assertEquals(ai.getInt("MAX_ROLEPLAY_USER_CHARS"), GroqHelper.MAX_ROLEPLAY_USER_CHARS)
        assertEquals(ai.getInt("MAX_ROLEPLAY_SCENARIO_CHARS"), GroqHelper.MAX_ROLEPLAY_SCENARIO_CHARS)
        assertEquals(ai.getInt("MAX_ROLEPLAY_MESSAGE_CHARS"), GroqHelper.MAX_ROLEPLAY_MESSAGE_CHARS)
        assertEquals(ai.getInt("MAX_ROLEPLAY_HISTORY_TURNS"), GroqHelper.MAX_ROLEPLAY_HISTORY_TURNS)
        assertEquals(ai.getInt("MAX_ROLEPLAY_HISTORY_CHARS"), GroqHelper.MAX_ROLEPLAY_HISTORY_CHARS)
        assertEquals(ai.getInt("MAX_ROLEPLAY_REPLY_CHARS"), GroqHelper.MAX_ROLEPLAY_REPLY_CHARS)
        assertEquals(ai.getInt("MAX_ROLEPLAY_CONTEXT_CHARS"), GroqHelper.MAX_ROLEPLAY_CONTEXT_CHARS)
    }

    @Test
    fun `practice scoring matches contract fixture`() {
        val contract = loadContract()
        val cases = contract.getJSONArray("practiceScoringCases")
        assertTrue("Has practice scoring cases", cases.length() > 0)

        for (i in 0 until cases.length()) {
            val caseObj = cases.getJSONObject(i)
            val name = caseObj.getString("name")
            val target = caseObj.getString("target")
            val spoken = caseObj.getString("spoken")
            val expectedFeedback = caseObj.getString("expectedFeedback")
            val expectedCorrectCount = caseObj.getInt("expectedCorrectCount")
            val expectedTotalCount = caseObj.getInt("expectedTotalCount")

            val (results, feedback) = PracticeViewModel.evaluateMatch(target, spoken)

            assertEquals("$name: feedback", expectedFeedback, feedback.name)
            assertEquals("$name: correct count", expectedCorrectCount, results.count { it.isCorrect })
            assertEquals("$name: total count", expectedTotalCount, results.size)
        }
    }
}
