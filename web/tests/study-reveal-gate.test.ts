import { describe, expect, it } from "vitest";
import { canGradeCard } from "@/hooks/useStudy";

/**
 * The reveal gate.
 *
 * "Grade this card" is only a meaningful sentence once the answer is on screen.
 * Before the reveal the four buttons were live, so a mistimed tap — or the "1"
 * shortcut — rated a card the learner had not read; on "Again" that reset the
 * interval and review count, scheduling an unseen word as forgotten.
 */
describe("canGradeCard", () => {
  it("refuses to grade before the answer is revealed", () => {
    expect(canGradeCard(false, 5)).toBe(false);
  });

  it("allows grading once revealed", () => {
    expect(canGradeCard(true, 5)).toBe(true);
  });

  it("refuses when the queue is empty, revealed or not", () => {
    expect(canGradeCard(true, 0)).toBe(false);
    expect(canGradeCard(false, 0)).toBe(false);
  });
});
