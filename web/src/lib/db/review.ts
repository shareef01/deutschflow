import type { DeutschFlowDB, VocabularyEntry } from "./schema";
import { rewardXp, XP_PER_CARD } from "./repository";
import { calculateNextReview, ReviewQuality } from "../ai/srs";

export class StaleReviewError extends Error {
  constructor() { super("This card changed after the study session was loaded."); }
}

/** Validate and commit against one database snapshot, including across browser tabs. */
export async function recordReview(
  db: DeutschFlowDB,
  expected: VocabularyEntry,
  quality: ReviewQuality,
  isExtraPractice: boolean,
): Promise<VocabularyEntry> {
  return db.transaction("rw", db.vocabulary, db.userStats, db.activityLog, db.reviewEvents, async () => {
    const current = expected.id === undefined ? undefined : await db.vocabulary.get(expected.id);
    if (!current || (Object.keys(expected) as (keyof VocabularyEntry)[])
      .some((field) => current[field] !== expected[field])) {
      throw new StaleReviewError();
    }
    const now = Date.now();
    const scheduled = !isExtraPractice || quality === ReviewQuality.AGAIN
      ? calculateNextReview(current, quality) : current;
    const persisted = { ...scheduled, lastModifiedAt: Math.max(now, current.lastModifiedAt + 1) };
    const previous = await db.reviewEvents.where("vocabularyId").equals(current.id!)
      .sortBy("reviewedAtTimestamp");
    const lastReview = previous.at(-1)?.reviewedAtTimestamp;
    // A first review has no preceding review interval.
    const actualDays = lastReview === undefined ? 0 : Math.max(0, Math.floor((now - lastReview) / 86_400_000));
    await db.vocabulary.update(current.id!, {
      nextReview: persisted.nextReview, interval: persisted.interval,
      easeFactor: persisted.easeFactor, reviewCount: persisted.reviewCount,
      lastModifiedAt: persisted.lastModifiedAt,
    });
    if (!isExtraPractice && quality >= ReviewQuality.GOOD) await rewardXp(db, XP_PER_CARD);
    const ratings = { [ReviewQuality.AGAIN]: "AGAIN", [ReviewQuality.HARD]: "HARD",
      [ReviewQuality.GOOD]: "GOOD", [ReviewQuality.EASY]: "EASY" } as const;
    await db.reviewEvents.add({
      vocabularyId: current.id!, rating: ratings[quality], scheduledDays: current.interval,
      actualDays, reviewedAtTimestamp: now, isExtraPractice, remoteId: crypto.randomUUID(),
    });
    return persisted;
  });
}
