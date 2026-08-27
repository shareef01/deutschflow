import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getAllVocabulary, getDueVocabulary, rewardXp, XP_PER_CARD, updateVocabulary } from "@/lib/db/repository";
import { getAutoPlay } from "@/lib/db/settings";
import { tts } from "@/lib/speech/tts";
import type { VocabularyEntry } from "@/lib/db/schema";
import { ReviewQuality, calculateNextReview } from "@/lib/ai/srs";

export function useStudy() {
  const [studyList, setStudyList] = useState<VocabularyEntry[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isFlipped, setIsFlipped] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);

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
    const list = due.length > 0 ? due : await getAllVocabulary(db);
    setCurrentIndex(0);
    setIsFlipped(false);
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
      // 1. Calculate next SRS state
      const updated = calculateNextReview(card, quality);

      // 2. Persist to Dexie
      await updateVocabulary(db, updated);

      // 3. Award XP if successful
      if (quality >= ReviewQuality.GOOD) {
        await rewardXp(db, XP_PER_CARD);
      }

      // 4. Update the queue.
      //
      // Computed here rather than inside a setStudyList updater. Updaters must be
      // pure, and that one called setCurrentIndex from inside itself; StrictMode
      // runs updaters twice, and React reserves the right to re-run them.
      const nextList = [...studyList];
      nextList.splice(currentIndex, 1);
      if (quality === ReviewQuality.AGAIN) {
        // Not a success, so the card goes to the back of the sitting rather than
        // leaving it — the schedule already has it due immediately.
        nextList.push(updated);
      }

      setStudyList(nextList);
      // Removing at currentIndex slides the next card into that slot, so the index
      // only moves when it ran off the end.
      setCurrentIndex(currentIndex >= nextList.length ? 0 : currentIndex);
      setIsFlipped(false);
    } finally {
      inFlight.current = false;
    }
  }, [studyList, currentIndex]);

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
