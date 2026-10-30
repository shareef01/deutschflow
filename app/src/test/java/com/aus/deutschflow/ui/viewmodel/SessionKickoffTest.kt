package com.aus.deutschflow.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a Study screen entry loads a deck. See StudyViewModel.ensureSessionStarted. */
class SessionKickoffTest {

    @Test
    fun `the first entry loads the deck`() {
        assertTrue(StudyViewModel.needsKickoff(kickoffDone = false, loadedEmptyDeck = false))
    }

    @Test
    fun `a sitting in progress is left alone on a tab switch`() {
        assertFalse(StudyViewModel.needsKickoff(kickoffDone = true, loadedEmptyDeck = false))
    }

    @Test
    fun `a deck loaded from an empty library reloads on the next entry`() {
        // The library filled after the first entry, e.g. a backup import in Settings.
        assertTrue(StudyViewModel.needsKickoff(kickoffDone = true, loadedEmptyDeck = true))
    }
}
