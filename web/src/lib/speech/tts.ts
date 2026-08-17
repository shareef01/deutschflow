/**
 * TTSHelper — speechSynthesis port.
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/service/TTSHelper.kt:
 * - CONNECTING / READY / UNAVAILABLE states: "not ready yet" and "will never be
 *   ready" must not look the same — a text asked for while connecting is spoken
 *   as soon as the engine answers; a text asked for while unavailable raises an
 *   error instead of silently doing nothing.
 * - The error is only raised once somebody has actually pressed a Speak button.
 * - `stop()` before the microphone opens, so the engine's own voice is never
 *   recognised as the user's (Practice).
 */

import { t } from "@/lib/i18n";

export interface TtsState {
  error: string | null;
}

type Listener = () => void;

/**
 * How long the engine is given to publish its voice list before an empty list is
 * taken as the answer. Long enough for a cold `voiceschanged`, short enough that a
 * browser with no voices at all still reports the failure rather than hanging.
 */
const VOICE_WAIT_MS = 3_000;

class Tts {
  private state: "connecting" | "ready" | "unavailable" = "connecting";
  private pendingText: string | null = null;
  private readonly listeners = new Set<Listener>();

  /**
   * Cached, never rebuilt per call: useSyncExternalStore's getSnapshot must
   * return the same reference between mutations, or React loops forever.
   */
  private stateValue: TtsState = { error: null };

  constructor() {
    // No engine at all - server rendering, or a browser without the API. That is
    // "will never be ready", which is exactly what unavailable means.
    if (typeof window === "undefined" || !("speechSynthesis" in window)) {
      this.state = "unavailable";
      return;
    }

    // `getVoices()` returns [] on its first synchronous call in Chrome and Edge and
    // only fills once `voiceschanged` fires. Deciding on that empty list collapsed
    // CONNECTING into UNAVAILABLE before the constructor even returned - so the
    // state was never CONNECTING, `pendingText` was never written, and the first
    // Speak tap after a cold start answered "no German voice" instead of queueing.
    // That is the precise failure the three states exist to keep apart.
    window.speechSynthesis.onvoiceschanged = () => this.syncVoices();
    this.syncVoices();

    // A browser that has no voices and never fires `voiceschanged` would otherwise
    // stay CONNECTING forever, silently swallowing every phrase. After this the
    // verdict is whatever the list says, empty or not.
    window.setTimeout(() => {
      if (this.state === "connecting") this.syncVoices({ decideOnEmpty: true });
    }, VOICE_WAIT_MS);
  }

  getSnapshot = (): TtsState => this.stateValue;

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private emit() {
    for (const listener of this.listeners) listener();
  }

  private setError(error: string | null) {
    this.stateValue = { error };
    this.emit();
  }

  /**
   * @param decideOnEmpty when false (the default) an empty voice list means "the
   * engine has not answered yet", not "there is no German voice". Only the timeout
   * in the constructor passes true, which is what stops CONNECTING being permanent.
   */
  private syncVoices({ decideOnEmpty = false } = {}) {
    const voices = window.speechSynthesis.getVoices();
    if (voices.length === 0 && !decideOnEmpty) return;

    const hasGerman = voices.some((v) => v.lang?.toLowerCase().startsWith("de"));
    if (hasGerman) {
      this.state = "ready";
      // A text asked for while connecting is spoken the moment the engine answers.
      if (this.pendingText !== null) {
        this.doSpeak(this.pendingText);
        this.pendingText = null;
      }
    } else {
      this.state = "unavailable";
      // The phrase was queued on the promise that the engine was still coming. It
      // is not, so say so rather than dropping it in silence — somebody pressed a
      // button for this.
      if (this.pendingText !== null) {
        this.pendingText = null;
        this.setError(t("tts.noGerman"));
      }
    }
  }

  speak(text: string): void {
    if (!text.trim()) return;
    if (this.state === "ready") {
      this.doSpeak(text);
    } else if (this.state === "connecting") {
      // Spoken the moment the engine answers.
      this.pendingText = text;
    } else {
      this.setError(t("tts.noGerman"));
    }
  }

  private doSpeak(text: string) {
    if (typeof window === "undefined" || !("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "de-DE";
    const germanVoice = window.speechSynthesis
      .getVoices()
      .find((v) => v.lang?.toLowerCase().startsWith("de"));
    if (germanVoice) utterance.voice = germanVoice;
    utterance.rate = 0.95;
    window.speechSynthesis.speak(utterance);
  }

  /** Stops the current phrase without tearing the engine down. */
  stop(): void {
    // `typeof window`, like the constructor: the bare `in window` test the rest of
    // this file used throws during server rendering rather than reporting absence.
    if (typeof window === "undefined" || !("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
  }

  dismissError(): void {
    this.setError(null);
  }

  shutdown(): void {
    this.state = "unavailable";
    this.pendingText = null;
    this.setError(null);
    this.stop();
  }
}

export const tts = new Tts();
