import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getAllVocabulary, getDueVocabulary, rewardXp, XP_PER_CARD, updateVocabulary } from "@/lib/db/repository";
import { getAutoPlay } from "@/lib/db/settings";
import { tts } from "@/lib/speech/tts";
import type { VocabularyEntry } from "@/lib/db/schema";
import type { TKey } from "@/lib/i18n";
import { ReviewQuality, calculateNextReview } from "@/lib/ai/srs";

export function useStudy() {
  const [studyList, setStudyList] = useState<VocabularyEntry[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isFlipped, setIsFlipped] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);

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

  const ttsError = useSyncExternalStore(tts.subscribe, tts.getSnapshot, tts.getSnapshot)?.error ?? null;

  const startSession = useCallback(async () => {
    const due = await getDueVocabulary(db, Date.now());
    // Android's StudyViewModel falls back to the whole library when nothing is
    // due, so a user who cleared their queue can still re-drill. The web used
    // to dead-end on an empty state instead.
    const isExtra = due.length === 0;
    const list = isExtra ? await getAllVocabulary(db) : due;
    setCurrentIndex(0);
    setIsFlipped(false);
    setIsExtraPractice(isExtra);
    setStudyList(shuffle(list));
    setHasLoaded(true);
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

      // 2. Persist the schedule and the XP together, so a failure leaves neither.
      await db.transaction("rw", db.vocabulary, db.userStats, db.activityLog, async () => {
        await updateVocabulary(db, persisted);
        if (quality >= ReviewQuality.GOOD) {
          await rewardXp(db, XP_PER_CARD);
        }
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
    currentIndex,
    isFlipped,
    hasLoaded,
    isExtraPractice,
    reviewError,
    dismissReviewError: () => setReviewError(null),
    ttsError,
    flipCard,
    submitReview,
    skipCard,
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
