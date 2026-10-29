import { describe, expect, it } from "vitest";
import {
  FOLLOW_THRESHOLD_PX,
  MAX_SETTLE_FRAMES,
  isAtBottom,
  settleScrollToEnd,
  shouldFollowConversation,
} from "@/lib/ui/scroll-follow";

/**
 * The roleplay scroll rule.
 *
 * The chat used to pin itself to the bottom on every change — including each
 * interim speech-recognition result, several times a second — so re-reading an
 * earlier exchange while the microphone was open was impossible. This is the
 * decision that replaced it, tested as arithmetic rather than through a browser.
 */

const viewport = { clientHeight: 600 };

/** A list taller than its viewport, scrolled to `offset`. */
const at = (offset: number, contentHeight = 1600) => ({
  ...viewport,
  scrollTop: offset,
  scrollHeight: contentHeight,
});

describe("isAtBottom", () => {
  it("is true with the end in view", () => {
    // 1600 - 1000 - 600 = 0
    expect(isAtBottom(at(1000))).toBe(true);
  });

  it("is true within the slack of the end", () => {
    expect(isAtBottom(at(1000 - (FOLLOW_THRESHOLD_PX - 1)))).toBe(true);
    expect(isAtBottom(at(1000 - FOLLOW_THRESHOLD_PX))).toBe(true);
  });

  it("is false once the reader is meaningfully away from the end", () => {
    expect(isAtBottom(at(1000 - (FOLLOW_THRESHOLD_PX + 1)))).toBe(false);
    expect(isAtBottom(at(0))).toBe(false);
  });

  it("is true when the content does not fill the viewport", () => {
    expect(isAtBottom({ clientHeight: 600, scrollTop: 0, scrollHeight: 200 })).toBe(true);
  });

  it("accepts a wider or narrower threshold", () => {
    expect(isAtBottom(at(900), 200)).toBe(true);
    expect(isAtBottom(at(900), 50)).toBe(false);
  });
});

describe("shouldFollowConversation", () => {
  it("follows a reader who is watching the conversation", () => {
    expect(shouldFollowConversation(at(1000))).toBe(true);
  });

  it("leaves a reader who scrolled back exactly where they are", () => {
    expect(shouldFollowConversation(at(0))).toBe(false);
    expect(shouldFollowConversation(at(400))).toBe(false);
  });

  /**
   * The streaming case, which is the one that broke: a reply (or an interim
   * recognition result) grows the content between two measurements. The decision
   * has to be made from the position the reader had *before* the growth, or a
   * reader who was at the bottom is suddenly 300px away and stops following.
   */
  it("decides from the pre-growth position, not the grown one", () => {
    const before = at(1000, 1600);
    const afterGrowth = at(1000, 1900);

    expect(shouldFollowConversation(before)).toBe(true);
    // Measured after the growth the same reader now looks "away from the end"…
    expect(shouldFollowConversation(afterGrowth)).toBe(false);
    // …which is why the caller keeps the decision from before.
  });

  it("stays decided while interim text arrives", () => {
    // A reader parked at the top is unaffected by any number of partial results:
    // the values that describe their position do not change.
    const reading = at(0);
    for (let i = 0; i < 10; i++) {
      expect(shouldFollowConversation(reading)).toBe(false);
    }
  });
});

describe("settleScrollToEnd", () => {
  /** A fake frame scheduler that can be flushed by the test. */
  const scheduler = () => {
    const queue: Array<() => void> = [];
    return {
      schedule: (run: () => void) => queue.push(run),
      flush: () => {
        while (queue.length > 0) queue.shift()!();
      },
      get pending() {
        return queue.length;
      },
    };
  };

  it("scrolls immediately once the container has a box", () => {
    const frames = scheduler();
    const box = { clientHeight: 300, scrollHeight: 900, scrollTop: 0 };

    expect(settleScrollToEnd(box, frames.schedule)).toBe(true);
    expect(box.scrollTop).toBe(900);
    expect(frames.pending).toBe(0);
  });

  it("waits for layout instead of assigning 0 to 0", () => {
    const frames = scheduler();
    // The state a pane is in on the frame its first content arrives: no box yet,
    // so `scrollTop = scrollHeight` would be a no-op and the message would never
    // be seen.
    const box = { clientHeight: 0, scrollHeight: 0, scrollTop: 0 };

    expect(settleScrollToEnd(box, frames.schedule)).toBe(false);
    expect(box.scrollTop).toBe(0);
    expect(frames.pending).toBe(1);

    // The box resolves on the next frame.
    box.clientHeight = 300;
    box.scrollHeight = 900;
    frames.flush();

    expect(box.scrollTop).toBe(900);
  });

  it("gives up rather than retrying forever", () => {
    const frames = scheduler();
    const box = { clientHeight: 0, scrollHeight: 0, scrollTop: 0 };

    let attempts = 0;
    const countingSchedule = (run: () => void) => {
      attempts++;
      frames.schedule(run);
    };

    settleScrollToEnd(box, countingSchedule);
    frames.flush();

    expect(attempts).toBe(MAX_SETTLE_FRAMES);
    expect(box.scrollTop).toBe(0);
  });
});
