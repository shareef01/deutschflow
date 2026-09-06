import { describe, expect, it } from "vitest";
import {
  nextStreak,
  daysBetween,
  getHeatmapCutoffDate,
  HEATMAP_DAYS,
} from "@/lib/db/repository";

const DAY = 86_400_000;
const now = Date.now();
const startOfToday = new Date(now).setHours(0, 0, 0, 0);

describe("nextStreak & daysBetween — calendar days in the device's zone", () => {
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

  it("computes exact day differences for ordinary consecutive days, same day, and 2-day gap", () => {
    const d1 = new Date(2026, 0, 10, 14, 0).getTime();
    const d1Later = new Date(2026, 0, 10, 20, 0).getTime();
    const d2 = new Date(2026, 0, 11, 9, 0).getTime();
    const d3 = new Date(2026, 0, 13, 10, 0).getTime();

    expect(daysBetween(d1, d1Later)).toBe(0);
    expect(nextStreak(3, d1, d1Later)).toBe(3);

    expect(daysBetween(d1, d2)).toBe(1);
    expect(nextStreak(3, d1, d2)).toBe(4);

    expect(daysBetween(d1, d3)).toBe(3);
    expect(nextStreak(3, d1, d3)).toBe(1);
  });

  it("handles Europe/Berlin spring-forward DST transition (23-hour day in March)", () => {
    // March 29, 2026 is spring-forward in Germany (CET to CEST: 02:00 -> 03:00)
    // March 28 is standard day, March 29 is 23h, March 30 is CEST
    const mar28Late = new Date(2026, 2, 28, 23, 59).getTime();
    const mar29Early = new Date(2026, 2, 29, 0, 1).getTime();
    const mar29Late = new Date(2026, 2, 29, 23, 59).getTime();
    const mar30Early = new Date(2026, 2, 30, 0, 1).getTime();

    // Midnight crossing into DST day
    expect(daysBetween(mar28Late, mar29Early)).toBe(1);
    expect(nextStreak(10, mar28Late, mar29Early)).toBe(11);

    // Same DST day activity
    expect(daysBetween(mar29Early, mar29Late)).toBe(0);
    expect(nextStreak(11, mar29Early, mar29Late)).toBe(11);

    // Midnight crossing out of DST day into March 30
    expect(daysBetween(mar29Late, mar30Early)).toBe(1);
    expect(nextStreak(11, mar29Late, mar30Early)).toBe(12);

    // 2-day gap across the DST boundary
    expect(daysBetween(mar28Late, mar30Early)).toBe(2);
    expect(nextStreak(10, mar28Late, mar30Early)).toBe(1);
  });

  it("handles Europe/Berlin fall-back DST transition (25-hour day in October)", () => {
    // October 25, 2026 is fall-back in Germany (CEST to CET: 03:00 -> 02:00, 25-hour day)
    const oct24Late = new Date(2026, 9, 24, 23, 59).getTime();
    const oct25Early = new Date(2026, 9, 25, 0, 1).getTime();
    const oct25Late = new Date(2026, 9, 25, 23, 59).getTime();
    const oct26Early = new Date(2026, 9, 26, 0, 1).getTime();

    // Midnight crossing into 25h fall-back day
    expect(daysBetween(oct24Late, oct25Early)).toBe(1);
    expect(nextStreak(20, oct24Late, oct25Early)).toBe(21);

    // Same fall-back day
    expect(daysBetween(oct25Early, oct25Late)).toBe(0);
    expect(nextStreak(21, oct25Early, oct25Late)).toBe(21);

    // Midnight crossing out of 25h day into October 26
    expect(daysBetween(oct25Late, oct26Early)).toBe(1);
    expect(nextStreak(21, oct25Late, oct26Early)).toBe(22);

    // 2-day gap across fall-back
    expect(daysBetween(oct24Late, oct26Early)).toBe(2);
    expect(nextStreak(20, oct24Late, oct26Early)).toBe(1);
  });

  it("heatmap cutoff represents exactly 84 local calendar dates", () => {
    // Test across spring DST boundary
    const mayFirst = new Date(2026, 4, 1, 12, 0); // May 1, 2026
    const cutoffSpring = getHeatmapCutoffDate(mayFirst);
    expect(daysBetween(cutoffSpring.getTime(), mayFirst.getTime())).toBe(HEATMAP_DAYS);

    // Test across fall DST boundary
    const novFirst = new Date(2026, 10, 1, 12, 0); // Nov 1, 2026
    const cutoffFall = getHeatmapCutoffDate(novFirst);
    expect(daysBetween(cutoffFall.getTime(), novFirst.getTime())).toBe(HEATMAP_DAYS);

    // Verify today's cutoff is exactly 84 days
    const today = new Date();
    const cutoffToday = getHeatmapCutoffDate(today);
    expect(daysBetween(cutoffToday.getTime(), today.getTime())).toBe(84);
  });
});
