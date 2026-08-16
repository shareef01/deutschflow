/**
 * SpeechRecognizerHelper — Web Speech API port.
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/service/SpeechRecognizerHelper.kt,
 * including the failure semantics that matter:
 * - One emission per completed utterance (the SharedFlow `results`): callers must
 *   react to it rather than reading `finalText` right after `stopListening()`,
 *   because the engine has not answered at that point.
 * - Recoverable errors (a silent utterance) clear themselves after a beat, but
 *   only if nothing newer superseded them.
 * - `cancel()` abandons without delivering; `stopListening()` asks for a result.
 * - Recognition content is never logged.
 *
 * Deliberately a module-level singleton per recognizer session, owned by the
 * stores that subscribe to it — the web analogue of each ViewModel owning its
 * own unscoped instance.
 *
 * Known platform difference, stated plainly: the Web Speech API exposes no audio
 * level, so `rmsLevel` is a synthetic animation while listening — the Android
 * engine reports real dB.
 */

import { t, type TKey } from "@/lib/i18n";

export interface RecognizerState {
  partialText: string;
  finalText: string;
  isListening: boolean;
  isProcessing: boolean;
  errorState: string | null;
  rmsLevel: number;
}

type StateListener = () => void;
type UtteranceListener = (text: string) => void;

const DEFAULT_LANGUAGE = "de-DE";
const ERROR_RESET_DELAY_MS = 2_500;
const RMS_TICK_MS = 80;

/** Message keys, localized at generation time (current language). */
const MESSAGES = {
  startFailed: "speech.startFailed" as TKey,
  audio: "speech.errorAudio" as TKey,
  permission: "speech.errorPermission" as TKey,
  network: "speech.errorNetwork" as TKey,
  noMatch: "speech.errorNoMatch" as TKey,
  timeout: "speech.errorTimeout" as TKey,
  languageUnsupported: "speech.errorLanguageUnsupported" as TKey,
  generic: "speech.errorGeneric" as TKey,
};

function createRecognition(): SpeechRecognition | null {
  if (typeof SpeechRecognition !== "undefined") return new SpeechRecognition();
  if (typeof webkitSpeechRecognition !== "undefined") return new webkitSpeechRecognition();
  return null;
}

class Recognizer {
  private recognition: SpeechRecognition | null = null;
  private state: RecognizerState = {
    partialText: "",
    finalText: "",
    isListening: false,
    isProcessing: false,
    errorState: null,
    rmsLevel: 0,
  };

  private readonly stateListeners = new Set<StateListener>();
  private readonly utteranceListeners = new Set<UtteranceListener>();
  private errorResetTimer: number | null = null;
  private rmsTimer: number | null = null;
  private currentLanguage = DEFAULT_LANGUAGE;

  getSnapshot = (): RecognizerState => this.state;

  subscribe = (listener: StateListener): (() => void) => {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  };

  /** One emission per completed utterance — the SharedFlow `results` analogue. */
  onUtterance(listener: UtteranceListener): () => void {
    this.utteranceListeners.add(listener);
    return () => this.utteranceListeners.delete(listener);
  }

  private setState(patch: Partial<RecognizerState>) {
    this.state = { ...this.state, ...patch };
    for (const listener of this.stateListeners) listener();
  }

  /** Aborts the in-flight request that a newer one supersedes. */
  startListening(languageTag: string = DEFAULT_LANGUAGE): void {
    this.currentLanguage = languageTag;
    this.recognition?.abort();
    this.recognition = null;

    const recognition = createRecognition();
    if (!recognition) {
      this.setState({ errorState: t("speech.unavailable"), isListening: false });
      return;
    }

    // Clear the previous session first, so a stale result can never be mistaken
    // for this one's. (The permission gate happens before the engine is told to
    // listen — see requestMicrophonePermission.)
    this.setState({
      partialText: "",
      finalText: "",
      errorState: null,
      isProcessing: false,
      isListening: true,
      rmsLevel: 0,
    });

    recognition.lang = languageTag;
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onaudiostart = () => this.startRmsAnimation();
    recognition.onresult = (event) => this.onResults(event);
    recognition.onerror = (event) => this.onError(event);
    recognition.onend = () => {
      // The engine closed on its own (or after stop()). If no final result was
      // delivered, close the session without inventing one.
      this.stopRmsAnimation();
      this.setState({ isListening: false, isProcessing: false, rmsLevel: 0 });
    };

    try {
      recognition.start();
      this.recognition = recognition;
    } catch {
      this.setState({
        errorState: t(MESSAGES.startFailed),
        isListening: false,
        isProcessing: false,
      });
    }
  }

  /** Requests mic access first — the browser's permission launcher. */
  async requestMicrophonePermission(): Promise<boolean> {
    if (!navigator.mediaDevices?.getUserMedia) return false;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      // The permission is the point; the stream is released immediately — the
      // recognition engine takes the mic itself.
      for (const track of stream.getTracks()) track.stop();
      return true;
    } catch {
      return false;
    }
  }

  stopListening(): void {
    // The final result still arrives later, in onresult.
    if (this.state.isListening) this.setState({ isProcessing: true });
    this.setState({ isListening: false });
    this.recognition?.stop();
  }

  /**
   * Abandons the current utterance without delivering it — filing half a
   * sentence as a transcript is worse than filing nothing.
   */
  cancel(): void {
    this.recognition?.abort();
    this.stopRmsAnimation();
    this.setState({ isListening: false, isProcessing: false, partialText: "", rmsLevel: 0 });
  }

  /** Forgets the last utterance without touching the engine (Practice uses this). */
  clearTranscript(): void {
    this.setState({ partialText: "", finalText: "" });
  }

  /** Reports a denied permission through the same channel as any other failure. */
  reportPermissionDenied(): void {
    this.setState({ errorState: t(MESSAGES.permission), isListening: false, isProcessing: false });
  }

  /** Drops a failure that is no longer the most recent thing to have gone wrong. */
  dismissError(): void {
    this.setState({ errorState: null });
  }

  destroy(): void {
    this.recognition?.abort();
    this.recognition = null;
    this.stopRmsAnimation();
    if (this.errorResetTimer !== null) {
      window.clearTimeout(this.errorResetTimer);
      this.errorResetTimer = null;
    }
    this.setState({ isListening: false, isProcessing: false, rmsLevel: 0 });
  }

  private onResults(event: SpeechRecognitionEvent): void {
    let interim = "";
    let final = "";
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const result = event.results[i];
      if (result.isFinal) final += result[0].transcript;
      else interim += result[0].transcript;
    }
    this.setState({
      partialText: interim,
      finalText: final || this.state.finalText,
    });
    if (final) this.deliverUtterance(final.trim());
  }

  private onError(event: SpeechRecognitionErrorEvent): void {
    this.stopRmsAnimation();
    this.setState({ isListening: false, isProcessing: false, rmsLevel: 0 });

    const message = this.messageFor(event.error);
    if (message === null) return; // "aborted" is a cancellation, not a failure

    // Recoverable by simply trying again: surface the hint, then clear it on a
    // timer so the control is ready without the user having to dismiss anything.
    const recoverable = event.error === "no-speech";
    this.setState({ errorState: message });
    if (recoverable) this.scheduleErrorReset(message);
  }

  /** @returns the message, or null when the error is not worth showing. */
  private messageFor(error: SpeechRecognitionErrorEvent["error"]): string | null {
    switch (error) {
      case "no-speech":
        return t(MESSAGES.timeout);
      case "audio-capture":
        return t(MESSAGES.audio);
      case "network":
        return t(MESSAGES.network);
      case "not-allowed":
      case "service-not-allowed":
        return t(MESSAGES.permission);
      case "language-not-supported":
        return t(MESSAGES.languageUnsupported);
      case "aborted":
        return null;
      default:
        return t(MESSAGES.generic);
    }
  }

  /**
   * Publishes a completed utterance and closes the session it belonged to.
   * Internal so the store can drive one through without real speech — the same
   * reason the Android helper's deliverUtterance is @VisibleForTesting.
   */
  deliverUtterance(text: string): void {
    this.stopRmsAnimation();
    this.setState({ isListening: false, isProcessing: false, rmsLevel: 0 });
    if (!text.trim()) return;

    this.setState({ finalText: text });
    for (const listener of this.utteranceListeners) listener(text);
  }

  private scheduleErrorReset(message: string) {
    if (this.errorResetTimer !== null) window.clearTimeout(this.errorResetTimer);
    this.errorResetTimer = window.setTimeout(() => {
      this.errorResetTimer = null;
      // Only if nothing newer superseded it.
      if (this.state.errorState === message) this.setState({ errorState: null });
    }, ERROR_RESET_DELAY_MS);
  }

  /** Synthetic input level — see the module note. */
  private startRmsAnimation() {
    if (this.rmsTimer !== null) return;
    let phase = 0;
    this.rmsTimer = window.setInterval(() => {
      phase += 0.35;
      const level = 0.25 + 0.55 * Math.abs(Math.sin(phase));
      this.setState({ rmsLevel: level });
    }, RMS_TICK_MS);
  }

  private stopRmsAnimation() {
    if (this.rmsTimer !== null) {
      window.clearInterval(this.rmsTimer);
      this.rmsTimer = null;
    }
  }
}

export const recognizer = new Recognizer();
