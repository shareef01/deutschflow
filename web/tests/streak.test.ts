import { describe, expect, it } from "vitest";
import { nextStreak } from "@/lib/db/repository";

const DAY = 86_400_000;
const now = Date.now();
const startOfToday = new Date(now).setHours(0, 0, 0, 0);

describe("nextStreak — calendar days in the device's zone", () => {
  it("keeps the streak for a second session on the same calendar day", () => {
    expect(nextStreak(5, startOfToday + 1000, startOfToday + 120_000)).toBe(5);
  });

  it("counts a midnight crossing as the next day (23:59 -> 00:01)", () => {
    expect(nextStreak(5, startOfToday - 60_000, startOfToday + 60_000)).toBe(6);
  });

  it("extends the streak for activity yesterday", () => {
    expect(nextStreak(5, startOfToday - DAY, startOfToday)).toBe(6);
  });

  it("resets the streak after a gap of more than one day", () => {
    expect(nextStreak(5, startOfToday - 3 * DAY, startOfToday)).toBe(1);
  });

  it("starts a fresh streak when there was none", () => {
    expect(nextStreak(0, 0, now)).toBe(1);
  });
});
