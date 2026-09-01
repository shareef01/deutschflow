import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getApiKey, getDialect } from "@/lib/db/settings";
import { insertTranscript, saveVocabulary } from "@/lib/db/repository";
import { recognizer, type RecognizerState } from "@/lib/speech/recognizer";
import { vocabularyProcessor } from "@/lib/ai/processor";
import { t } from "@/lib/i18n";
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

  /**
   * Which utterance may write state, bumped on every issue and on session start.
   *
   * Two utterances delivered back-to-back each fire a fresh Groq call, and the
   * results land in arrival order, not request order: a slow first call used to
   * resolve after the second and overwrite it with stale text. Only the most
   * recently issued utterance — and nothing issued before a cleared screen —
   * gets to touch the state.
   */
  const utteranceToken = useRef(0);

  const errorState = recognizerState.errorState ?? state.aiError;

  const handleUtterance = useCallback(async (text: string) => {
    const token = ++utteranceToken.current;
    try {
      await insertTranscript(db, text);
    } catch {
      // Quota, private-mode eviction, a blocked database: the rejection used to
      // escape as an unhandled promise rejection and the screen showed nothing.
      // The utterance is still on the recogniser's card, so translation continues.
      if (token === utteranceToken.current) {
        setState((prev) => ({ ...prev, aiError: t("ai.storageFailed") }));
      }
    }

    if (token !== utteranceToken.current) return;
    setState((prev) => ({ ...prev, isTranslating: true }));
    try {
      const apiKey = (await getApiKey(db)) ?? "";
      const result = await vocabularyProcessor.processText(text, apiKey);
      if (token !== utteranceToken.current) return;
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
    } catch {
      // The AI layer converts its own fetch failures into results, but a throw
      // from the vault reading the key bypassed every error surface on the way
      // down. Recoverable by re-entering the key, which is what the message asks.
      if (token !== utteranceToken.current) return;
      setState((prev) => ({
        ...prev,
        translation: "",
        suggestedWords: [],
        grammarNotes: [],
        example: "",
        aiError: t("ai.noKey"),
      }));
    } finally {
      // A stale utterance must not clear the spinner of the newer one that
      // replaced it.
      if (token === utteranceToken.current) {
        setState((prev) => ({ ...prev, isTranslating: false }));
      }
    }
  }, []);

  useEffect(() => {
    return recognizer.onUtterance((text) => {
      void handleUtterance(text);
    });
  }, [handleUtterance]);

  const startListening = useCallback(async () => {
    // A new session clears the screen; an older utterance still in flight would
    // otherwise repopulate it with a result for text that is no longer shown.
    utteranceToken.current++;
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

  /**
   * @returns true only once the row is committed, so the snackbar that follows is a
   * report rather than an intention.
   *
   * This used to fire the write and return `true` immediately, which meant a
   * rejected write - quota, a blocked upgrade, private-mode eviction - surfaced as
   * an unhandled promise rejection while the user was being told "Saved". Android's
   * saveToVocabulary suspends for exactly this reason; the web is the only copy of
   * the library, so a silently dropped save is worse here than there.
   */
  const saveToVocabulary = useCallback(
    async (german: string, english: string): Promise<boolean> => {
      if (!german.trim() || !english.trim()) return false;
      try {
        await saveVocabulary(db, {
          germanText: german,
          englishTranslation: english,
          exampleSentence: state.example,
        });
        return true;
      } catch {
        setState((prev) => ({ ...prev, aiError: t("ai.storageFailed") }));
        return false;
      }
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
      } catch {
        // Same bypass as the utterance path: a vault throw here reached the
        // console, never the sheet the user is staring at.
        if (token === interrogationToken.current) {
          setState((prev) => ({ ...prev, wordDetailError: t("ai.noKey") }));
        }
      } finally {
        if (token === interrogationToken.current) {
          setState((prev) => ({ ...prev, interrogatingWord: null }));
        }
      }
    })();
  }, []);

  /** Awaited for the same reason as [saveToVocabulary]. */
  const saveWordDetails = useCallback(async (details: WordDetails): Promise<boolean> => {
    if (!details.word.trim() || !details.meaning.trim()) return false;
    try {
      await saveVocabulary(db, {
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
    } catch {
      setState((prev) => ({ ...prev, wordDetailError: t("ai.storageFailed") }));
      return false;
    }
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
