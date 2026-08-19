import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getApiKey, getDialect } from "@/lib/db/settings";
import { insertTranscript, saveVocabulary } from "@/lib/db/repository";
import { recognizer, type RecognizerState } from "@/lib/speech/recognizer";
import { vocabularyProcessor } from "@/lib/ai/processor";
import type { WordDetails, GrammarNote } from "@/lib/ai/groq";

export interface TranscriptState {
  partialText: string;
  finalText: string;
  isListening: boolean;
  isProcessing: boolean;
  rmsLevel: number;
  isTranslating: boolean;
  translation: string;
  suggestedWords: string[];
  grammarNotes: GrammarNote[];
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
  grammarNotes: [],
  example: "",
  aiError: null,
  wordDetails: null,
  interrogatingWord: null,
  wordDetailError: null,
  errorState: null,
};

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
            grammarNotes: result.grammarNotes,
            aiError: null,
          };
        }
        return { ...prev, translation: "", suggestedWords: [], grammarNotes: [], example: "", aiError: result.message };
      });
    } finally {
      setState((prev) => ({ ...prev, isTranslating: false }));
    }
  }, []);

  useEffect(() => {
    return recognizer.onUtterance((text) => {
      void handleUtterance(text);
    });
  }, [handleUtterance]);

  const startListening = useCallback(async () => {
    setState((prev) => ({ ...prev, translation: "", suggestedWords: [], grammarNotes: [], example: "", aiError: null }));

    const granted = await recognizer.requestMicrophonePermission();
    if (!granted) {
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

    const token = ++interrogationToken.current;
    setState((prev) => ({ ...prev, wordDetails: null, wordDetailError: null, interrogatingWord: trimmed }));

    void (async () => {
      try {
        const apiKey = (await getApiKey(db)) ?? "";
        const result = await vocabularyProcessor.interrogateWord(trimmed, apiKey);
        if (token !== interrogationToken.current) return;
        setState((prev) =>
          result.kind === "success"
            ? { ...prev, wordDetails: result.details }
            : { ...prev, wordDetailError: result.message }
        );
      } finally {
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
      synonyms: details.synonyms.join(", "),
      antonyms: details.antonyms.join(", "),
    });
    return true;
  }, []);

  const dismissWordDetails = useCallback(() => {
    setState((prev) => ({ ...prev, wordDetails: null, wordDetailError: null }));
  }, []);

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
