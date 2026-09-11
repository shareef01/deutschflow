import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getAllVocabulary, getDueVocabulary, rewardXp, XP_PER_CARD, updateVocabulary } from "@/lib/db/repository";
import { getAutoPlay } from "@/lib/db/settings";
import { tts } from "@/lib/speech/tts";
import type { VocabularyEntry } from "@/lib/db/schema";
import type { TKey } from "@/lib/i18n";
import { ReviewQuality, calculateNextReview } from "@/lib/ai/srs";

export type StudyStatus = "loading" | "ready" | "error";

export async function loadStudySession(database = db, now = Date.now()) {
  const all = await getAllVocabulary(database);
  const due = await getDueVocabulary(database, now);
  const isExtra = due.length === 0;
  const list = isExtra ? all : due;
  return {
    totalWords: all.length,
    dueCount: due.length,
    isExtraPractice: isExtra,
    studyList: shuffle(list),
  };
}

export function useStudy() {
  const [studyList, setStudyList] = useState<VocabularyEntry[]>([]);
  const [totalWords, setTotalWords] = useState(0);
  const [dueCount, setDueCount] = useState(0);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isFlipped, setIsFlipped] = useState(false);
  const [status, setStatus] = useState<StudyStatus>("loading");
  const [loadError, setLoadError] = useState<TKey | null>(null);

  /**
   * True when this sitting is extra practice rather than the scheduler's queue.
   *
   * Nothing was due, so the whole library was offered instead. Worth keeping — but a
   * bonus sitting must not be indistinguishable from a scheduled one, which it was:
   * answering Good on a card due in 90 days re-multiplied its interval from today,
   * so practising early pushed the material further away.
   */
  const [isExtraPractice, setIsExtraPractice] = useState(false);

  /** A review that could not be written, so the screen can say so rather than lie. */
  const [reviewError, setReviewError] = useState<TKey | null>(null);

  /**
   * A review in flight, read and written synchronously.
   *
   * Two rapid taps both capture the same `studyList[currentIndex]` before the
   * first await chain completes: two SRS updates on one card, two XP payouts,
   * and the second splice overwriting the first from a stale list. State alone
   * cannot guard this — both taps read it before either re-render lands.
   */
  const inFlight = useRef(false);
  const initGeneration = useRef(0);

  const ttsError = useSyncExternalStore(tts.subscribe, tts.getSnapshot, tts.getSnapshot)?.error ?? null;

  const startSession = useCallback(async () => {
    const generation = ++initGeneration.current;
    setStatus("loading");
    setLoadError(null);
    try {
      const session = await loadStudySession(db);
      if (generation !== initGeneration.current) return;
      setTotalWords(session.totalWords);
      setDueCount(session.dueCount);
      setCurrentIndex(0);
      setIsFlipped(false);
      setIsExtraPractice(session.isExtraPractice);
      setStudyList(session.studyList);
      setStatus("ready");
    } catch {
      if (generation !== initGeneration.current) return;
      setStatus("error");
      setLoadError("study.loadError");
    }
  }, []);

  /** Re-drills the whole library. Always extra practice, by definition. */
  const restartSession = useCallback(async () => {
    const all = await getAllVocabulary(db);
    setTotalWords(all.length);
    setCurrentIndex(0);
    setIsFlipped(false);
    setIsExtraPractice(true);
    setStudyList(shuffle(all));
  }, []);

  useEffect(() => {
    tts.dismissError();
    void startSession();
  }, [startSession]);

  const flipCard = useCallback(() => setIsFlipped((flipped) => !flipped), []);

  const submitReview = useCallback(async (quality: ReviewQuality) => {
    if (inFlight.current) return;
    const card = studyList[currentIndex];
    if (!card) return;
    inFlight.current = true;

    try {
      // 1. Calculate next SRS state.
      //
      // On extra practice a success changes nothing: the card was not due, and
      // rewarding the user for drilling by pushing the word further away is the
      // opposite of what they asked for. A failure still counts — finding out early
      // that a card is not known is real information.
      const rescheduled = calculateNextReview(card, quality);
      const persisted =
        !isExtraPractice || quality === ReviewQuality.AGAIN ? rescheduled : card;

      const now = Date.now();
      const actualDays = card.timestamp ? Math.max(0, Math.floor((now - card.timestamp) / 86_400_000)) : 0;
      const ratingMap: Record<ReviewQuality, "AGAIN" | "HARD" | "GOOD" | "EASY"> = {
        [ReviewQuality.AGAIN]: "AGAIN",
        [ReviewQuality.HARD]: "HARD",
        [ReviewQuality.GOOD]: "GOOD",
        [ReviewQuality.EASY]: "EASY",
      };

      // 2. Persist the schedule, review event, and XP together
      await db.transaction("rw", db.vocabulary, db.userStats, db.activityLog, db.reviewEvents, async () => {
        await updateVocabulary(db, persisted);
        if (!isExtraPractice && quality >= ReviewQuality.GOOD) {
          await rewardXp(db, XP_PER_CARD);
        }
        await db.reviewEvents.add({
          vocabularyId: card.id ?? 0,
          rating: ratingMap[quality],
          scheduledDays: card.interval,
          actualDays,
          reviewedAtTimestamp: now,
          isExtraPractice,
          remoteId: crypto.randomUUID(),
        });
      });

      // 3. Update the queue.
      //
      // Computed here rather than inside a setStudyList updater. Updaters must be
      // pure, and that one called setCurrentIndex from inside itself; StrictMode
      // runs updaters twice, and React reserves the right to re-run them.
      const nextList = [...studyList];
      nextList.splice(currentIndex, 1);
      if (quality === ReviewQuality.AGAIN) {
        // Not a success, so the card goes to the back of the sitting rather than
        // leaving it — the schedule already has it due immediately.
        nextList.push(persisted);
      }

      setStudyList(nextList);
      // Removing at currentIndex slides the next card into that slot, so the index
      // only moves when it ran off the end.
      setCurrentIndex(currentIndex >= nextList.length ? 0 : currentIndex);
      setIsFlipped(false);
    } catch {
      // The transaction rolled back, so the card is exactly where it was.
      setReviewError("study.reviewNotSaved");
    } finally {
      inFlight.current = false;
    }
  }, [studyList, currentIndex, isExtraPractice]);

  const skipCard = useCallback(() => {
    if (studyList.length > 0) {
      setCurrentIndex((index) => (index + 1) % studyList.length);
    }
    setIsFlipped(false);
  }, [studyList.length]);

  const autoPlay = useCallback((text: string) => {
    void (async () => {
      if (await getAutoPlay(db)) tts.speak(text);
    })();
  }, []);

  const speak = useCallback((text: string) => tts.speak(text), []);

  return {
    studyList,
    totalWords,
    dueCount,
    currentIndex,
    isFlipped,
    status,
    loadError,
    retry: startSession,
    hasLoaded: status === "ready",
    isExtraPractice,
    reviewError,
    dismissReviewError: () => setReviewError(null),
    ttsError,
    flipCard,
    submitReview,
    skipCard,
    restartSession,
    autoPlay,
    speak,
  };
}

function shuffle<T>(list: T[]): T[] {
  const out = [...list];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}
