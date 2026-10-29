/**
 * Whether a growing list should follow its own tail.
 *
 * A chat that pins itself to the bottom is right for someone watching the
 * conversation and wrong for someone re-reading it. The decision is a pure
 * function of the scroll geometry so it can be tested without a browser or a
 * compositor — the behaviour it replaced (scroll on every change) was wrong in a
 * way no screenshot could show.
 */

/**
 * How close to the end still counts as "watching".
 *
 * Not zero: a conversation is at its end while the last line is comfortably
 * readable, and demanding a pixel-perfect offset meant that a reader one notch
 * up from the bottom silently stopped following.
 */
export const FOLLOW_THRESHOLD_PX = 48;

export interface ScrollMetrics {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
}

/** True when the viewport is showing the end of the content. */
export function isAtBottom(
  metrics: ScrollMetrics,
  threshold: number = FOLLOW_THRESHOLD_PX
): boolean {
  const { scrollTop, scrollHeight, clientHeight } = metrics;
  // Content shorter than the viewport has no scroll position to be away from.
  if (scrollHeight <= clientHeight) return true;
  return scrollHeight - scrollTop - clientHeight <= threshold;
}

/**
 * Whether new content should pull the viewport to it.
 *
 * Deliberately the same question as `isAtBottom`: the rule is "follow only a
 * reader who was already at the end", and it has to be answered with the
 * position from *before* the new content grew the container — measured after,
 * the reader who was at the bottom is 200px away and never followed again.
 */
export function shouldFollowConversation(
  metrics: ScrollMetrics,
  threshold: number = FOLLOW_THRESHOLD_PX
): boolean {
  return isAtBottom(metrics, threshold);
}

/** The parts of a scroll container this needs. Structural, so it can be faked. */
export interface ScrollBoxLike {
  clientHeight: number;
  scrollHeight: number;
  scrollTop: number;
}

export const MAX_SETTLE_FRAMES = 8;

/**
 * Scrolls a container to its end, waiting for it to have a layout box first.
 *
 * Assigning `scrollTop = scrollHeight` is a no-op when the container has not
 * been laid out yet — both read as 0 — and that is exactly the state a pane is in
 * on the frame its first content arrives. The opening roleplay message was never
 * brought into view for this reason, so the reader began at the top of the
 * conversation and the next turn appeared to yank them to the bottom.
 *
 * @param schedule runs the next attempt after a frame (`requestAnimationFrame`).
 * @param onApplied called once the scroll has actually been applied — the caller
 *   needs to know *when*, not just whether, so it can keep ignoring its own
 *   scroll echo until the sequence is over.
 * @returns true once the scroll has been applied.
 */
export function settleScrollToEnd(
  box: ScrollBoxLike,
  schedule: (run: () => void) => void,
  onApplied?: () => void,
  framesLeft: number = MAX_SETTLE_FRAMES
): boolean {
  if (box.clientHeight <= 0 || box.scrollHeight <= 0) {
    if (framesLeft <= 0) return false;
    schedule(() => settleScrollToEnd(box, schedule, onApplied, framesLeft - 1));
    return false;
  }
  box.scrollTop = box.scrollHeight;
  onApplied?.();
  return true;
}
