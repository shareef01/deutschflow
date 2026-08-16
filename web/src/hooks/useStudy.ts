import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getAllVocabulary, rewardXp, XP_PER_CARD } from "@/lib/db/repository";
import { getAutoPlay } from "@/lib/db/settings";
import { tts } from "@/lib/speech/tts";
import type { VocabularyEntry } from "@/lib/db/schema";

/**
 * useStudy — StudyViewModel port.
 *
 * The list is deliberately a snapshot rather than a live flow — re-shuffling
 * mid-session would move the cards under the user — but the screen restarts the
 * session on entry, so words saved since last time do show up.
 */
export function useStudy() {
  const [studyList, setStudyList] = useState<VocabularyEntry[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isFlipped, setIsFlipped] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);

  /** Cards already banked this session — a card counts once per session. */
  const awardedCardIds = useRef(new Set<number>());

  const ttsError = useSyncExternalStore(tts.subscribe, tts.getSnapshot, tts.getSnapshot)?.error ?? null;

  /**
   * Takes a fresh shuffled snapshot. Not called from the hook's init: the
   * screen already calls it on entry, and doing both meant two reads and two
   * shuffles every time the tab was opened.
   */
  const startSession = useCallback(async () => {
    // Cleared first: a tap landing before the read came back must not be judged
    // against the previous session's banked cards.
    awardedCardIds.current.clear();

    const list = await getAllVocabulary(db);
    setCurrentIndex(0);
    setIsFlipped(false);
    setStudyList(shuffle(list));
    setHasLoaded(true);
  }, []);

  // Called on entry — and only the screen calls it, mirroring the Android
  // "the ViewModel deliberately does not also load on init".
  useEffect(() => {
    tts.dismissError();
    void startSession();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const flipCard = useCallback(() => setIsFlipped((flipped) => !flipped), []);

  const nextCard = useCallback(() => {
    setStudyList((list) => {
      if (list.length > 0) {
        setCurrentIndex((index) => (index + 1) % list.length);
      }
      return list;
    });
    setIsFlipped(false);
  }, []);

  /** Speaks the card, unless the user turned auto-play off in Settings. */
  const autoPlay = useCallback((text: string) => {
    void (async () => {
      if (await getAutoPlay(db)) tts.speak(text);
    })();
  }, []);

  const speak = useCallback((text: string) => tts.speak(text), []);

  /**
   * Banks the card on screen, once, and advances the streak — atomically in the
   * repository (one read-modify-write transaction, calendar-day streak logic).
   */
  const rewardCurrentCard = useCallback(
    (points: number = XP_PER_CARD) => {
      const card = studyList[currentIndex];
      if (!card || card.id === undefined) return;
      if (!awardedCardIds.current.add(card.id)) return;
      void rewardXp(db, points);
    },
    [studyList, currentIndex]
  );

  return {
    studyList,
    currentIndex,
    isFlipped,
    hasLoaded,
    ttsError,
    flipCard,
    nextCard,
    autoPlay,
    speak,
    rewardCurrentCard,
  };
}

/** Fisher-Yates — Kotlin's List.shuffled() equivalent. */
function shuffle<T>(list: T[]): T[] {
  const out = [...list];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}
