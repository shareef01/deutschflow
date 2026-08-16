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
    // `speechSynthesis.getVoices()` is empty until the asynchronous
    // voiceschanged event; the engine is CONNECTING until then.
    if (typeof window === "undefined" || !("speechSynthesis" in window)) {
      this.state = "unavailable";
      return;
    }
    const evaluate = () => {
      this.syncVoices();
      window.speechSynthesis.onvoiceschanged = evaluate;
    };
    evaluate();
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

  private syncVoices() {
    const voices = window.speechSynthesis.getVoices();
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
    if (!("speechSynthesis" in window)) return;
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
    if ("speechSynthesis" in window) window.speechSynthesis.cancel();
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
