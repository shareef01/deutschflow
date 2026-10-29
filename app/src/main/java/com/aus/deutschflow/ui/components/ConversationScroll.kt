package com.aus.deutschflow.ui.components

/**
 * Whether a growing list is showing its own end.
 *
 * The roleplay conversation used to scroll itself to the bottom whenever a message
 * arrived, so a reader who had scrolled back to re-read an exchange was pulled away
 * from it mid-sentence. The rule that replaced it is "follow only a reader who was
 * already at the end", and it lives here — as arithmetic over the numbers
 * `LazyListState.layoutInfo` already reports — so it can be tested on the JVM rather
 * than only by eye on a device.
 *
 * The web client has the same rule in `web/src/lib/ui/scroll-follow.ts`; the two
 * are written to the same shape so a change to one is obviously a change to both.
 */

/**
 * How close to the end still counts as "following".
 *
 * Not zero: a conversation is at its end while the last line is comfortably
 * readable, and demanding a pixel-perfect offset meant that a reader one notch up
 * from the bottom silently stopped following.
 */
const val FOLLOW_THRESHOLD_PX = 48

/**
 * @param lastVisibleIndex index of the last item the viewport shows, or -1 when the
 *   list has not been laid out yet.
 * @param lastVisibleOffset top edge of that item, in pixels from the list's start.
 * @param lastVisibleSize height of that item in pixels.
 * @param totalItems how many items the list holds, including the pending bubble.
 * @param viewportEndOffset the list's viewport height in pixels.
 */
fun isAtConversationBottom(
    lastVisibleIndex: Int,
    lastVisibleOffset: Int,
    lastVisibleSize: Int,
    totalItems: Int,
    viewportEndOffset: Int,
    thresholdPx: Int = FOLLOW_THRESHOLD_PX
): Boolean {
    // Nothing laid out yet, or nothing to be away from: both mean "at the end".
    if (totalItems <= 0 || lastVisibleIndex < 0) return true

    // A list shorter than its viewport shows its last item by definition, and that
    // item's bottom edge is above the viewport's — so the overhang is negative.
    val overhang = (lastVisibleOffset + lastVisibleSize) - viewportEndOffset

    // Away from the end only if the final item is not the visible one, or if its
    // bottom edge sits further than the threshold below the fold.
    return lastVisibleIndex >= totalItems - 1 && overhang <= thresholdPx
}

/**
 * Whether new content should pull the viewport to it.
 *
 * Deliberately the same question: the decision must be taken from the position the
 * reader had *before* the new message grew the list. Measured after, a reader who
 * was at the bottom is now a bubble away from it and never followed again.
 */
fun shouldFollowConversation(
    lastVisibleIndex: Int,
    lastVisibleOffset: Int,
    lastVisibleSize: Int,
    totalItems: Int,
    viewportEndOffset: Int,
    thresholdPx: Int = FOLLOW_THRESHOLD_PX
): Boolean = isAtConversationBottom(
    lastVisibleIndex,
    lastVisibleOffset,
    lastVisibleSize,
    totalItems,
    viewportEndOffset,
    thresholdPx
)
