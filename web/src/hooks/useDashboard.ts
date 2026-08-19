import { useMemo } from "react";
import { db } from "@/lib/db";
import {
  observeUserStats,
  observeActivityLog,
  observeVocabulary,
  todayKey,
} from "@/lib/db/repository";
import { useLive } from "./useLive";
import type { ActivityEntry, VocabularyEntry } from "@/lib/db/schema";

export interface MasteryStats {
  totalWords: number;
  masteredWords: number;
  learningWords: number;
  newWords: number;
}

/**
 * useDashboard — DashboardViewModel port.
 *
 * All three reads go through `useLive`, the app's one Dexie binding: `liveQuery`
 * is a static on Dexie, not a method on the instance, and a hand-rolled
 * useSyncExternalStore has to cache its snapshot or React re-renders forever.
 */
export function useDashboard() {
  const userStats = useLive(() => observeUserStats(db), []) ?? null;
  const activityLog = useLive(() => observeActivityLog(db), []) ?? ([] as ActivityEntry[]);
  const vocabulary = useLive(() => observeVocabulary(db), []) ?? ([] as VocabularyEntry[]);

  /**
   * One axis, so the three figures always sum to the total: a word that has
   * never been answered is new, and everything else is mastered or still being
   * learned. Bucketing "new" on reviewCount and "learning" on interval let a
   * word that was just failed (interval 0, reviewCount 0) count as new again,
   * and left a word with interval 0 but reviews behind it in no bucket at all.
   */
  const masteryStats = useMemo<MasteryStats>(() => {
    const newWords = vocabulary.filter((v) => v.reviewCount === 0);
    const seen = vocabulary.filter((v) => v.reviewCount > 0);
    return {
      totalWords: vocabulary.length,
      masteredWords: seen.filter((v) => v.interval >= MASTERED_INTERVAL_DAYS).length,
      learningWords: seen.filter((v) => v.interval < MASTERED_INTERVAL_DAYS).length,
      newWords: newWords.length,
    };
  }, [vocabulary]);

  /**
   * The same local-calendar key `addActivityXp` writes under. An ISO/UTC key
   * here would roll the heatmap over at the wrong hour for anyone not on UTC,
   * and disagree with the streak, which has always compared local days.
   */
  const todayXp = useMemo(() => {
    const today = todayKey();
    return activityLog.find((log) => log.date === today)?.xpGained ?? 0;
  }, [activityLog]);

  return { userStats, activityLog, masteryStats, todayXp };
}

/** SM-2's graduation point, shared with the Android DashboardViewModel. */
const MASTERED_INTERVAL_DAYS = 21;
