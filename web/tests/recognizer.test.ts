import { beforeEach, describe, expect, it } from "vitest";
import { recognizer } from "@/lib/speech/recognizer";

/**
 * Recognizer session semantics — the contract `results` claims:
 * ONE emission per completed utterance, however many times the speaker pauses.
 *
 * `continuous` recognition finalises a result at every natural pause. Publishing
 * each one filed a transcript row and made a billed Groq request per pause, and
 * dropped the microphone control out of its recording state mid-sentence. These
 * tests hold that line, because no unit test could reach it before: the Playwright
 * suite cannot speak, so this behaviour was only ever exercised by a human.
 *
 * The Web Speech API is faked rather than mocked — a real engine's result list
 * accumulates across events and `abort()`/`stop()` end the session through `onend`,
 * and the bugs lived in exactly those orderings.
 */

interface Segment {
  transcript: string;
  isFinal: boolean;
}

class FakeRecognition {
  lang = "";
  continuous = false;
  interimResults = false;
  maxAlternatives = 1;

  onaudiostart: (() => void) | null = null;
  onresult: ((event: unknown) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;
  onend: (() => void) | null = null;

  started = false;
  aborted = false;

  /** The engine's own accumulating result list, as the real API keeps it. */
  private results: Segment[] = [];

  static last: FakeRecognition | null = null;

  constructor() {
    FakeRecognition.last = this;
  }

  start() {
    this.started = true;
  }

  stop() {
    this.started = false;
  }

  abort() {
    this.aborted = true;
    this.started = false;
  }

  /** Appends segments and fires onresult with the index the real API would send. */
  emit(...segments: Segment[]) {
    const resultIndex = this.results.length;
    this.results.push(...segments);

    const list = this.results.map((s) => ({
      isFinal: s.isFinal,
      length: 1,
      0: { transcript: s.transcript, confidence: 1 },
    }));

    this.onresult?.({ resultIndex, results: Object.assign(list, { length: list.length }) });
  }

  /** The session closing — after stop(), after abort(), or on the engine's own. */
  end() {
    this.onend?.();
  }
}

function collectUtterances(): string[] {
  const seen: string[] = [];
  recognizer.onUtterance((text) => seen.push(text));
  return seen;
}

const unsubscribers: Array<() => void> = [];

beforeEach(() => {
  // The recognizer reaches for window timers and the constructor global.
  const globals = globalThis as unknown as Record<string, unknown>;
  globals.window = globalThis;
  globals.SpeechRecognition = FakeRecognition;

  while (unsubscribers.length) unsubscribers.pop()?.();
  recognizer.destroy();
  recognizer.clearTranscript();
  recognizer.dismissError();
  FakeRecognition.last = null;
});

function track(unsubscribe: () => void) {
  unsubscribers.push(unsubscribe);
}

describe("one recording is one utterance", () => {
  it("publishes nothing on a pause, and everything once at the end", () => {
    const seen: string[] = [];
    track(recognizer.onUtterance((text) => seen.push(text)));

    recognizer.startListening("de-DE");
    const engine = FakeRecognition.last!;

    // Two natural pauses inside a single recording.
    engine.emit({ transcript: "Ich lerne", isFinal: true });
    engine.emit({ transcript: "Deutsch gern", isFinal: true });

    expect(seen).toEqual([]);

    recognizer.stopListening();
    engine.end();

    expect(seen).toEqual(["Ich lerne Deutsch gern"]);
  });

  it("stays in the recording state across a pause", () => {
    recognizer.startListening("de-DE");
    const engine = FakeRecognition.last!;

    engine.emit({ transcript: "Guten Morgen", isFinal: true });

    // The microphone is still open, so the control must still say so.
    expect(recognizer.getSnapshot().isListening).toBe(true);
    expect(recognizer.getSnapshot().isProcessing).toBe(false);
  });

  it("shows the whole utterance so far, not just the segment in progress", () => {
    recognizer.startListening("de-DE");
    const engine = FakeRecognition.last!;

    engine.emit({ transcript: "Ich lerne", isFinal: true });
    engine.emit({ transcript: " Deutsch", isFinal: false });

    // A speaker who pauses must not watch their first sentence disappear.
    expect(recognizer.getSnapshot().partialText).toBe("Ich lerne Deutsch");
  });

  it("publishes once even if the session ends twice", () => {
    const seen: string[] = [];
    track(recognizer.onUtterance((text) => seen.push(text)));

    recognizer.startListening("de-DE");
    const engine = FakeRecognition.last!;
    engine.emit({ transcript: "Hallo", isFinal: true });

    engine.end();
    engine.end();

    expect(seen).toEqual(["Hallo"]);
  });

  it("publishes nothing when the engine finalised nothing", () => {
    const seen: string[] = [];
    track(recognizer.onUtterance((text) => seen.push(text)));

    recognizer.startListening("de-DE");
    FakeRecognition.last!.end();

    expect(seen).toEqual([]);
  });
});

describe("abandoning and superseding a session", () => {
  it("cancel throws the half-sentence away instead of filing it", () => {
    const seen: string[] = [];
    track(recognizer.onUtterance((text) => seen.push(text)));

    recognizer.startListening("de-DE");
    const engine = FakeRecognition.last!;
    engine.emit({ transcript: "Ich wollte eigentlich", isFinal: true });

    recognizer.cancel();
    // A real engine still fires onend after abort(); it must reach nobody.
    engine.end();

    expect(seen).toEqual([]);
    expect(engine.aborted).toBe(true);
  });

  it("a superseded session cannot close the one that replaced it", () => {
    const seen: string[] = [];
    track(recognizer.onUtterance((text) => seen.push(text)));

    recognizer.startListening("de-DE");
    const first = FakeRecognition.last!;
    first.emit({ transcript: "alte Aufnahme", isFinal: true });

    recognizer.startListening("de-DE");
    const second = FakeRecognition.last!;
    expect(second).not.toBe(first);

    // The abandoned engine's onend lands late. It must not publish the old
    // utterance, and must not drop the new recording out of its listening state.
    first.end();

    expect(seen).toEqual([]);
    expect(recognizer.getSnapshot().isListening).toBe(true);

    second.emit({ transcript: "neue Aufnahme", isFinal: true });
    recognizer.stopListening();
    second.end();

    expect(seen).toEqual(["neue Aufnahme"]);
  });

  it("does not carry a previous session's segments into the next", () => {
    const seen: string[] = [];
    track(recognizer.onUtterance((text) => seen.push(text)));

    recognizer.startListening("de-DE");
    const first = FakeRecognition.last!;
    first.emit({ transcript: "erste", isFinal: true });
    recognizer.stopListening();
    first.end();

    recognizer.startListening("de-DE");
    const second = FakeRecognition.last!;
    second.emit({ transcript: "zweite", isFinal: true });
    recognizer.stopListening();
    second.end();

    expect(seen).toEqual(["erste", "zweite"]);
  });
});
