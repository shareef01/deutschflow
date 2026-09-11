import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getAllVocabulary } from "@/lib/db/repository";
import { recognizer, isRecognitionSupported } from "@/lib/speech/recognizer";
import { tts } from "@/lib/speech/tts";
import { resolveRecognitionDialect } from "@/lib/speech/dialect";
import { evaluateMatch, type PracticeFeedback, type WordResult } from "@/lib/scoring";

/**
 * usePractice — PracticeViewModel port.
 *
 * - The target is something real: the model's example when the entry has one,
 *   the entry itself otherwise — never a template wrapped around a sentence.
 * - Scoring runs when the utterance actually arrives (the recognizer's result
 *   channel), never by reading finalText right after stopPractice().
 * - TTS is stopped before the microphone opens, or the engine's own voice
 *   would be recognised as the user's.
 */

const DEFAULT_TARGET = "Ich lerne Deutsch.";

/** The recognizer's state before hydration — server rendering sees silence. */
const SERVER_SNAPSHOT = {
  partialText: "",
  finalText: "",
  isListening: false,
  isProcessing: false,
  errorState: null,
  rmsLevel: 0,
};

export function selectWeightedVocabulary<T extends { interval?: number; easeFactor?: number }>(items: T[]): T | null {
  if (items.length === 0) return null;
  if (items.length === 1) return items[0];

  const weights = items.map((item) => {
    const interval = Math.max(1, item.interval ?? 0);
    const ease = Math.min(3.0, Math.max(1.3, item.easeFactor ?? 2.5));
    const intervalFactor = 1 / Math.sqrt(interval);
    const difficultyFactor = 3.5 - ease;
    return Math.max(0.1, intervalFactor * difficultyFactor);
  });

  const totalWeight = weights.reduce((sum, w) => sum + w, 0);
  let threshold = Math.random() * totalWeight;

  for (let i = 0; i < items.length; i++) {
    threshold -= weights[i];
    if (threshold <= 0) {
      return items[i];
    }
  }
  return items[items.length - 1];
}

export function usePractice() {
  const speechSupported = useSyncExternalStore(
    () => () => {},
    isRecognitionSupported,
    () => false
  );

  const recognizerState = useSyncExternalStore(
    recognizer.subscribe,
    recognizer.getSnapshot,
    () => SERVER_SNAPSHOT
  );
  const ttsError = useSyncExternalStore(tts.subscribe, tts.getSnapshot, tts.getSnapshot)?.error ?? null;

  const [targetSentence, setTargetSentence] = useState(DEFAULT_TARGET);
  const [feedback, setFeedback] = useState<PracticeFeedback>("NONE");
  const [wordResults, setWordResults] = useState<WordResult[]>([]);

  /**
   * The sentence the scorer reads when an utterance arrives.
   *
   * The utterance listener is registered once on entry, so a closure over
   * `targetSentence` would keep scoring the sentence that was on screen at
   * mount time — every "Next sentence" after the first would be judged against
   * the first one. The ref is updated wherever the target changes, which is
   * only [loadRandomTarget], so the scorer always sees the current sentence.
   */
  const targetRef = useRef(targetSentence);
  const isStarting = useRef(false);

  /** One error surface: whichever of the microphone or the voice engine last
   * had something to say — recognition preferred, like the Android combine. */
  const errorState = recognizerState.errorState ?? ttsError;

  const loadRandomTarget = useCallback(async () => {
    const list = await getAllVocabulary(db);
    const chosen = selectWeightedVocabulary(list);
    if (chosen) {
      const next = chosen.exampleSentence || chosen.germanText;
      // Keep the scorer's ref in step the moment the target changes, before
      // any utterance can arrive against it.
      targetRef.current = next;
      setTargetSentence(next);
    }
    setWordResults([]);
    setFeedback("NONE");
    // The third piece of the last attempt lives in the recognizer; clearing only
    // the two above would leave the old words under the new sentence.
    recognizer.clearTranscript();
  }, []);

  useEffect(() => {
    tts.dismissError();
    void loadRandomTarget();

    // Scoring runs when the utterance actually arrives, against the sentence
    // currently on screen — read through the ref, not a mount-time closure.
    return recognizer.onUtterance((text) => {
      const { results, feedback: verdict } = evaluateMatch(targetRef.current, text);
      setWordResults(results);
      setFeedback(verdict);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startPractice = useCallback(async () => {
    if (isStarting.current || recognizer.getSnapshot().isListening) return;
    isStarting.current = true;
    setWordResults([]);
    setFeedback("NONE");
    // Stop any German playback before the microphone opens.
    tts.stop();

    try {
      const granted = await recognizer.requestMicrophonePermission();
      if (!granted) {
        recognizer.reportPermissionDenied();
        return;
      }
      const dialect = await resolveRecognitionDialect();
      recognizer.startListening(dialect);
    } catch {
      recognizer.reportPermissionDenied();
    } finally {
      isStarting.current = false;
    }
  }, []);

  const stopPractice = useCallback(() => recognizer.stopListening(), []);

  /** Called when the screen leaves composition — the OnLeavingScreen port. */
  const cancelListening = useCallback(() => recognizer.cancel(), []);

  const speak = useCallback((text: string) => {
    // The recognizer's error outlives the attempt that caused it; the banner
    // prefers it, so a stale one would hide whatever this request has to say.
    recognizer.dismissError();
    tts.speak(text);
  }, []);

  const nextSentence = useCallback(() => {
    void loadRandomTarget();
  }, [loadRandomTarget]);

  return {
    speechSupported,
    targetSentence,
    feedback,
    wordResults,
    partialText: recognizerState.partialText,
    spokenText: recognizerState.finalText,
    isListening: recognizerState.isListening,
    isProcessing: recognizerState.isProcessing,
    rmsLevel: recognizerState.rmsLevel,
    errorState,
    startPractice,
    stopPractice,
    cancelListening,
    speak,
    nextSentence,
  };
}
