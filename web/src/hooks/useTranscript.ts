import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getApiKey, getDialect } from "@/lib/db/settings";
import { insertTranscript, saveVocabulary } from "@/lib/db/repository";
import { recognizer, type RecognizerState } from "@/lib/speech/recognizer";
import { vocabularyProcessor } from "@/lib/ai/processor";
import type { WordDetails } from "@/lib/ai/groq";

/**
 * useTranscript — TranscriptViewModel port.
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/ui/viewmodel/TranscriptViewModel.kt
 * state-for-state and behaviour-for-behaviour:
 * - Completed utterances are consumed from the recognizer's result channel (the
 *   SharedFlow), never read from `finalText` right after `stopListening()`.
 * - An AI failure never reaches the translation field — the Save button writes
 *   that field straight into the vocabulary table.
 * - An interrogation cancels the previous in-flight one (latest tap wins), and
 *   only the newest interrogation clears the spinner.
 * - saveToVocabulary returns false when there was nothing to save, so the screen
 *   can stay quiet instead of confirming a write that did not happen.
 */

export interface TranscriptState {
  partialText: string;
  finalText: string;
  isListening: boolean;
  isProcessing: boolean;
  rmsLevel: number;
  isTranslating: boolean;
  translation: string;
  suggestedWords: string[];
  example: string;
  aiError: string | null;
  wordDetails: WordDetails | null;
  interrogatingWord: string | null;
  wordDetailError: string | null;
  errorState: string | null;
}

const INITIAL_STATE: TranscriptState = {
  partialText: "",
  finalText: "",
  isListening: false,
  isProcessing: false,
  rmsLevel: 0,
  isTranslating: false,
  translation: "",
  suggestedWords: [],
  example: "",
  aiError: null,
  wordDetails: null,
  interrogatingWord: null,
  wordDetailError: null,
  errorState: null,
};

/** The recognizer's state before hydration — server rendering sees silence. */
const SERVER_RECOGNIZER_STATE: RecognizerState = {
  partialText: "",
  finalText: "",
  isListening: false,
  isProcessing: false,
  errorState: null,
  rmsLevel: 0,
};

export function useTranscript() {
  const recognizerState = useSyncExternalStore(
    recognizer.subscribe,
    recognizer.getSnapshot,
    () => SERVER_RECOGNIZER_STATE
  );
  const [state, setState] = useState<TranscriptState>(INITIAL_STATE);
  const interrogationToken = useRef(0);

  // The recognizer's error is one surface with the AI error: whichever had
  // something to say last shows in the banner (Android merges them at the call
  // site with `errorState ?: aiError` — the recognizer's error wins).
  const errorState = recognizerState.errorState ?? state.aiError;

  const handleUtterance = useCallback(async (text: string) => {
    await insertTranscript(db, text);

    setState((prev) => ({ ...prev, isTranslating: true }));
    try {
      const apiKey = (await getApiKey(db)) ?? "";
      const result = await vocabularyProcessor.processText(text, apiKey);
      setState((prev) => {
        if (result.kind === "success") {
          return {
            ...prev,
            translation: result.translation,
            suggestedWords: result.keywords,
            example: result.example,
            aiError: null,
          };
        }
        // Never let a failure reach the translation field.
        return { ...prev, translation: "", suggestedWords: [], example: "", aiError: result.message };
      });
    } finally {
      setState((prev) => ({ ...prev, isTranslating: false }));
    }
  }, []);

  // One emission per completed utterance — handleUtterance.
  useEffect(() => {
    return recognizer.onUtterance((text) => {
      void handleUtterance(text);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startListening = useCallback(async () => {
    setState((prev) => ({ ...prev, translation: "", suggestedWords: [], example: "", aiError: null }));

    const granted = await recognizer.requestMicrophonePermission();
    if (!granted) {
      // The user refused the microphone — say so rather than doing nothing
      // (Android stops showing the system dialog after the second refusal).
      recognizer.reportPermissionDenied();
      return;
    }
    const dialect = await getDialect(db);
    recognizer.startListening(dialect);
  }, []);

  const stopListening = useCallback(() => recognizer.stopListening(), []);
  const cancelListening = useCallback(() => recognizer.cancel(), []);

  const saveToVocabulary = useCallback(
    (german: string, english: string): boolean => {
      if (!german.trim() || !english.trim()) return false;
      const example = state.example;
      void saveVocabulary(db, { germanText: german, englishTranslation: english, exampleSentence: example });
      return true;
    },
    [state.example]
  );

  const interrogateWord = useCallback((word: string) => {
    const trimmed = word.trim();
    if (!trimmed) return;

    // A second tap supersedes the first rather than racing it.
    const token = ++interrogationToken.current;
    setState((prev) => ({ ...prev, wordDetails: null, wordDetailError: null, interrogatingWord: trimmed }));

    void (async () => {
      try {
        const apiKey = (await getApiKey(db)) ?? "";
        const result = await vocabularyProcessor.interrogateWord(trimmed, apiKey);
        if (token !== interrogationToken.current) return; // superseded
        setState((prev) =>
          result.kind === "success"
            ? { ...prev, wordDetails: result.details }
            : { ...prev, wordDetailError: result.message }
        );
      } finally {
        // Only the newest interrogation owns this: a superseded one must not
        // clear the spinner off a chip whose answer is still on its way.
        if (token === interrogationToken.current) {
          setState((prev) => ({ ...prev, interrogatingWord: null }));
        }
      }
    })();
  }, []);

  const saveWordDetails = useCallback((details: WordDetails): boolean => {
    if (!details.word.trim() || !details.meaning.trim()) return false;
    void saveVocabulary(db, {
      germanText: details.word,
      englishTranslation: details.meaning,
      exampleSentence: details.exampleSentence,
      article: details.article,
      plural: details.plural,
      conjugation: details.conjugationOrInfinitive,
    });
    return true;
  }, []);

  const dismissWordDetails = useCallback(() => {
    setState((prev) => ({ ...prev, wordDetails: null, wordDetailError: null }));
  }, []);

  /**
   * Clears a failed interrogation, but only if it is still the failure on
   * screen — same rule as the Android `dismissWordDetailError(message)`.
   */
  const dismissWordDetailError = useCallback((message: string) => {
    setState((prev) => (prev.wordDetailError === message ? { ...prev, wordDetailError: null } : prev));
  }, []);

  const isBusy = recognizerState.isProcessing || state.isTranslating;

  const view: TranscriptState = {
    ...state,
    partialText: recognizerState.partialText,
    finalText: recognizerState.finalText,
    isListening: recognizerState.isListening,
    isProcessing: recognizerState.isProcessing,
    rmsLevel: recognizerState.rmsLevel,
    errorState,
  };

  return {
    state: view,
    isBusy,
    startListening,
    stopListening,
    cancelListening,
    saveToVocabulary,
    interrogateWord,
    saveWordDetails,
    dismissWordDetails,
    dismissWordDetailError,
  };
}

/** Extracts the recognizer snapshot for non-hook consumers. */
export function getRecognizerState(): RecognizerState {
  return recognizer.getSnapshot();
}
