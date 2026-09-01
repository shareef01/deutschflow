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
 * ONE module-level singleton, shared by every screen that records — which is the
 * opposite of the Android arrangement, where each ViewModel owns an unscoped
 * instance precisely so that a sentence spoken on Practice is not also filed as a
 * transcript. The comment here used to claim the Android arrangement.
 *
 * What makes the shared instance safe is that exactly one consumer is ever
 * subscribed: each hook registers its `onUtterance` listener in an effect and
 * unsubscribes on unmount, and `useRoleplay` additionally gates on `active`
 * because Practice keeps both its tabs mounted. Add a third consumer without that
 * discipline and one utterance will be delivered twice.
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
  // No entry for Android's ERROR_NO_MATCH — "speech heard but not understood".
  // The Web Speech API has no equivalent: its `no-speech` means nothing was heard
  // at all, which is `timeout`. `speech.errorNoMatch` stays in the dictionary so
  // the two string sets remain a pair, but nothing here can raise it.
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

  /**
   * The final segments of the session in flight, joined and delivered once when it
   * ends.
   *
   * `continuous` recognition emits a final result at every natural pause, not once
   * per recording. Delivering each one as a completed utterance filed a transcript
   * row and made a Groq request per pause - so one spoken paragraph became three of
   * each - and, because deliverUtterance closes the session's state, the microphone
   * control dropped back to idle mid-sentence while capture was still running.
   * Android's SpeechRecognizer answers once per session, which is the contract
   * `results` claims.
   */
  private finalSegments: string[] = [];

  /** True once this session's utterance has been published, so `onend` cannot repeat it. */
  private delivered = false;

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

  /**
   * Detaches an engine so its remaining callbacks cannot reach this object.
   *
   * `abort()` fires `onend` asynchronously, so a superseded session's handler would
   * otherwise land *after* the next one had already set itself listening - closing a
   * recording that had just started. Silencing the old engine before abandoning it
   * is what makes "the newest session owns the state" true rather than a race.
   */
  private detach(recognition: SpeechRecognition | null): void {
    if (!recognition) return;
    recognition.onaudiostart = null;
    recognition.onresult = null;
    recognition.onerror = null;
    recognition.onend = null;
    recognition.abort();
  }

  /** Aborts the in-flight request that a newer one supersedes. */
  startListening(languageTag: string = DEFAULT_LANGUAGE): void {
    this.currentLanguage = languageTag;
    this.detach(this.recognition);
    this.recognition = null;

    const recognition = createRecognition();
    if (!recognition) {
      this.setState({ errorState: t("speech.unavailable"), isListening: false });
      return;
    }

    // Clear the previous session first, so a stale result can never be mistaken
    // for this one's. (The permission gate happens before the engine is told to
    // listen — see requestMicrophonePermission.)
    this.finalSegments = [];
    this.delivered = false;
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
      // The session is over, so this is the moment the utterance is complete —
      // everything the engine finalised, joined, delivered once. If it finalised
      // nothing, close the session without inventing an utterance.
      this.stopRmsAnimation();
      this.deliverUtterance(this.finalSegments.join(" "));
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
    // One patch, not two: the pair used to publish an intermediate state in which
    // the screen was neither listening nor processing. The utterance itself still
    // arrives later, from `onend`.
    this.setState({ isProcessing: this.state.isListening, isListening: false });
    this.recognition?.stop();
  }

  /**
   * Abandons the current utterance without delivering it — filing half a
   * sentence as a transcript is worse than filing nothing.
   */
  cancel(): void {
    // Detached rather than merely aborted: `onend` would otherwise publish the
    // half-sentence this call exists to throw away.
    this.detach(this.recognition);
    this.recognition = null;
    this.finalSegments = [];
    this.stopRmsAnimation();
    this.setState({ isListening: false, isProcessing: false, partialText: "", rmsLevel: 0 });
  }

  /** Forgets the last utterance without touching the engine (Practice uses this). */
  clearTranscript(): void {
    this.finalSegments = [];
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
    this.detach(this.recognition);
    this.recognition = null;
    this.finalSegments = [];
    this.stopRmsAnimation();
    if (this.errorResetTimer !== null) {
      window.clearTimeout(this.errorResetTimer);
      this.errorResetTimer = null;
    }
    this.setState({ isListening: false, isProcessing: false, rmsLevel: 0 });
  }

  /**
   * Banks each finalised segment and shows the transcript so far.
   *
   * Nothing is published here: a pause is not the end of an utterance, only the end
   * of a phrase. `onend` is what closes the session and delivers it.
   */
  private onResults(event: SpeechRecognitionEvent): void {
    let interim = "";
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const result = event.results[i];
      if (result.isFinal) this.finalSegments.push(result[0].transcript.trim());
      else interim += result[0].transcript;
    }

    const settled = this.finalSegments.join(" ").trim();
    this.setState({
      // The whole utterance as it stands, not just the segment in progress, so a
      // speaker who pauses does not watch their first sentence disappear. Each part
      // is trimmed before joining: engines emit interim text with its own leading
      // space, which a single join would double.
      partialText: [settled, interim.trim()].filter(Boolean).join(" "),
      finalText: settled || this.state.finalText,
    });
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

    const utterance = text.trim();
    // Once per session. `onend` fires after an explicit stop() as well as after the
    // engine closes on its own, and cancel() abandons a session that must publish
    // nothing at all.
    if (!utterance || this.delivered) return;
    this.delivered = true;

    this.setState({ partialText: "", finalText: utterance });
    for (const listener of this.utteranceListeners) listener(utterance);
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
