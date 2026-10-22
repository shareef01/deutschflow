package com.aus.deutschflow.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which Transcript dock fits the height left. Heights are what a Pixel 7 gives the
 * screen: about 476dp in portrait with the keyboard up, 305dp in landscape, and
 * 58dp in landscape with the keyboard up.
 */
class TranscriptDockLayoutTest {

    @Test
    fun `portrait keeps the stacked dock, keyboard or not`() {
        assertEquals(DockLayout.STACKED, transcriptDockLayout(760.dp, imeVisible = false, typing = true))
        assertEquals(DockLayout.STACKED, transcriptDockLayout(476.dp, imeVisible = true, typing = true))
    }

    @Test
    fun `landscape without the keyboard uses one row`() {
        assertEquals(DockLayout.ROW, transcriptDockLayout(305.dp, imeVisible = false, typing = true))
        assertEquals(DockLayout.ROW, transcriptDockLayout(305.dp, imeVisible = false, typing = false))
    }

    @Test
    fun `landscape with the keyboard up shows only the field`() {
        assertEquals(DockLayout.TYPING_ONLY, transcriptDockLayout(58.dp, imeVisible = true, typing = true))
    }

    @Test
    fun `a keyboard opened for something else does not hide the microphone`() {
        assertEquals(DockLayout.ROW, transcriptDockLayout(58.dp, imeVisible = true, typing = false))
    }

    @Test
    fun `the threshold itself is tall enough to stack`() {
        assertEquals(DockLayout.STACKED, transcriptDockLayout(ShortTranscriptHeight, imeVisible = true, typing = true))
    }
}
