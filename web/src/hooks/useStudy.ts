import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getAllVocabulary, getDueVocabulary } from "@/lib/db/repository";
import { recordReview, StaleReviewError } from "@/lib/db/review";
import { getAutoPlay } from "@/lib/db/settings";
import { tts } from "@/lib/speech/tts";
import type { VocabularyEntry } from "@/lib/db/schema";
import type { TKey } from "@/lib/i18n";
import { ReviewQuality } from "@/lib/ai/srs";

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

/**
 * Due cards still waiting in this sitting.
 *
 * Derived from the live queue, not the load-time snapshot: a scheduled sitting
 * holds exactly the due cards, a success removes one and Again re-queues it
 * (it is due again at once), so the queue length is the count. Extra practice
 * is by definition nothing due.
 */
export function remainingDueCount(studyList: readonly unknown[], isExtraPractice: boolean): number {
  return isExtraPractice ? 0 : studyList.length;
}

/**
 * Whether a grade may be submitted.
 *
 * The reveal is what turns a card into a judgement. Before it there is nothing to
 * recall, and "Again" would reset the word's interval and review count — scheduling
 * an unread card as forgotten. Exported so the same rule is asserted directly
 * (tests/study-reveal-gate.test.ts) rather than only through a rendered screen.
 */
export function canGradeCard(isFlipped: boolean, remaining: number): boolean {
  return isFlipped && remaining > 0;
}

export function useStudy() {
  const [studyList, setStudyList] = useState<VocabularyEntry[]>([]);
  const [totalWords, setTotalWords] = useState(0);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isFlipped, setIsFlipped] = useState(false);
  const [status, setStatus] = useState<StudyStatus>("loading");
  const [loadError, setLoadError] = useState<TKey | null>(null);
  const [sessionReviewedCount, setSessionReviewedCount] = useState(0);
  const [initialSessionSize, setInitialSessionSize] = useState(0);

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
      setCurrentIndex(0);
      setIsFlipped(false);
      setIsExtraPractice(session.isExtraPractice);
      setStudyList(session.studyList);
      setSessionReviewedCount(0);
      setInitialSessionSize(session.studyList.length);
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
    setSessionReviewedCount(0);
    setInitialSessionSize(all.length);
  }, []);

  useEffect(() => {
    tts.dismissError();
    void startSession();
  }, [startSession]);

  const flipCard = useCallback(() => setIsFlipped((flipped) => !flipped), []);

  /**
   * The reveal state, readable synchronously, and consumed on use.
   *
   * A grade is only meaningful once the answer has been shown. The four buttons
   * were live from the moment a card was dealt, so a mistimed tap — or the "1"
   * shortcut — graded a card the learner had not read; on "Again" that resets the
   * interval and the review count, scheduling an unseen word as forgotten.
   *
   * A ref rather than the `isFlipped` state because of the window between a review
   * committing and the re-render that clears the reveal: a held key repeats every
   * ~30ms, re-enters with the *old* closure, passes the old state check, and
   * submits the same card again. `recordReview`'s snapshot check catches that, so
   * the schedule survives — but the user is told "this card changed elsewhere, your
   * session has been refreshed" for holding a key. The flag is cleared the instant a
   * grade is accepted, so the second event finds nothing to grade.
   */
  const revealed = useRef(isFlipped);
  revealed.current = isFlipped;

  const submitReview = useCallback(async (quality: ReviewQuality) => {
    if (inFlight.current) return;
    // Nothing to grade until the card has been turned over.
    if (!revealed.current) return;
    const card = studyList[currentIndex];
    if (!card) return;
    inFlight.current = true;
    // Consumed, not merely read: see `revealed`.
    revealed.current = false;

    try {
      // Commit the schedule, event, and reward against the current database row.
      //
      // On extra practice a success changes nothing: the card was not due, and
      // rewarding the user for drilling by pushing the word further away is the
      // opposite of what they asked for. A failure still counts — finding out early
      // that a card is not known is real information.
      const persisted = await recordReview(db, card, quality, isExtraPractice);
      setReviewError(null);

      // Update the queue only after the transaction commits.
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
      setSessionReviewedCount((count) => count + 1);
      // Removing at currentIndex slides the next card into that slot, so the index
      // only moves when it ran off the end.
      setCurrentIndex(currentIndex >= nextList.length ? 0 : currentIndex);
      setIsFlipped(false);
    } catch (error) {
      // Refresh stale sessions without awarding XP or overwriting the current row.
      if (error instanceof StaleReviewError) {
        setReviewError("study.sessionChanged");
        await startSession();
      } else {
        setReviewError("study.reviewNotSaved");
      }
    } finally {
      inFlight.current = false;
    }
  }, [studyList, currentIndex, isExtraPractice, startSession]);

  const skipCard = useCallback(() => {
    if (inFlight.current) return;
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
    dueCount: remainingDueCount(studyList, isExtraPractice),
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
    /**
     * Whether a grade may be submitted. The same predicate governs the buttons,
     * the keyboard shortcuts and the hook's own guard, so the three cannot drift.
     */
    canGrade: canGradeCard(isFlipped, studyList.length),
    skipCard,
    restartSession,
    autoPlay,
    speak,
    sessionReviewedCount,
    initialSessionSize,
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
