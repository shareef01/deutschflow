package com.aus.deutschflow.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roleplay scroll rule, on the JVM.
 *
 * The conversation used to pin itself to the bottom whenever a message arrived, so
 * a reader who had scrolled back to re-read an exchange was pulled away from it. The
 * rule that replaced it is arithmetic over `LazyListState.layoutInfo`, which is why
 * it can be pinned here instead of only by eye on a device.
 */
class ConversationScrollTest {

    /** A list of [total] items whose viewport is 600px tall, scrolled to [offset]. */
    private fun at(
        offset: Int,
        itemSize: Int = 200,
        total: Int = 10,
        viewport: Int = 600
    ): Boolean {
        // The item occupying the top of the viewport, and how far into it we are.
        val index = offset / itemSize
        val lastVisibleIndex = ((offset + viewport - 1) / itemSize).coerceAtMost(total - 1)
        val lastVisibleOffset = lastVisibleIndex * itemSize - offset
        return isAtConversationBottom(
            lastVisibleIndex = lastVisibleIndex,
            lastVisibleOffset = lastVisibleOffset,
            lastVisibleSize = itemSize,
            totalItems = total,
            viewportEndOffset = viewport
        ).also { require(index >= 0) }
    }

    @Test
    fun `an empty or unlaid-out list counts as being at the end`() {
        assertTrue(isAtConversationBottom(-1, 0, 0, 0, 600))
        assertTrue(isAtConversationBottom(-1, 0, 0, 5, 600))
    }

    @Test
    fun `the last message fully in view is the end`() {
        // 10 items x 200px = 2000px of content, 600px viewport: the end is 1400px down.
        assertTrue(at(offset = 1400))
    }

    @Test
    fun `a reader parked at the top is not at the end`() {
        assertFalse(at(offset = 0))
        assertFalse(at(offset = 400))
    }

    @Test
    fun `a partly visible last message within the threshold still counts as the end`() {
        // 40px short of the end: inside the 48px slack.
        assertTrue(at(offset = 1400 - 40))
    }

    @Test
    fun `a last message more than the threshold below the fold does not`() {
        assertFalse(at(offset = 1400 - 60))
    }

    @Test
    fun `a list shorter than its viewport is always at the end`() {
        // Three small items in a tall viewport.
        assertTrue(
            isAtConversationBottom(
                lastVisibleIndex = 2,
                lastVisibleOffset = 120,
                lastVisibleSize = 60,
                totalItems = 3,
                viewportEndOffset = 600
            )
        )
    }

    @Test
    fun `a threshold of zero demands the exact end`() {
        assertTrue(
            isAtConversationBottom(9, 400, 200, 10, 600, thresholdPx = 0)
        )
        assertFalse(
            isAtConversationBottom(9, 401, 200, 10, 600, thresholdPx = 0)
        )
    }

    @Test
    fun `the follow decision is the position decision`() {
        // Same inputs, same answer: the caller must take it from the pre-growth
        // position, and there is no second rule to keep in step.
        assertTrue(
            shouldFollowConversation(9, 400, 200, 10, 600) ==
                isAtConversationBottom(9, 400, 200, 10, 600)
        )
    }
}
