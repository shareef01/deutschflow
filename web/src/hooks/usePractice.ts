import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getAllVocabulary } from "@/lib/db/repository";
import { getDialect } from "@/lib/db/settings";
import { recognizer } from "@/lib/speech/recognizer";
import { tts } from "@/lib/speech/tts";
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

export function usePractice() {
  const recognizerState = useSyncExternalStore(
    recognizer.subscribe,
    recognizer.getSnapshot,
    () => SERVER_SNAPSHOT
  );
  const ttsError = useSyncExternalStore(tts.subscribe, tts.getSnapshot, tts.getSnapshot)?.error ?? null;

  const [targetSentence, setTargetSentence] = useState(DEFAULT_TARGET);
  const [feedback, setFeedback] = useState<PracticeFeedback>("NONE");
  const [wordResults, setWordResults] = useState<WordResult[]>([]);

  /** One error surface: whichever of the microphone or the voice engine last
   * had something to say — recognition preferred, like the Android combine. */
  const errorState = recognizerState.errorState ?? ttsError;

  const loadRandomTarget = useCallback(async () => {
    const list = await getAllVocabulary(db);
    if (list.length > 0) {
      const randomItem = list[Math.floor(Math.random() * list.length)];
      setTargetSentence(randomItem.exampleSentence || randomItem.germanText);
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

    // Scoring runs when the utterance actually arrives.
    return recognizer.onUtterance((text) => {
      const { results, feedback: verdict } = evaluateMatch(targetSentence, text);
      setWordResults(results);
      setFeedback(verdict);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startPractice = useCallback(async () => {
    setWordResults([]);
    setFeedback("NONE");
    // Stop any German playback before the microphone opens.
    tts.stop();

    const granted = await recognizer.requestMicrophonePermission();
    if (!granted) {
      recognizer.reportPermissionDenied();
      return;
    }
    const dialect = await getDialect(db);
    recognizer.startListening(dialect);
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
