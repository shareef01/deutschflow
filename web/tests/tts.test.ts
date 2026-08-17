import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The three TTS states, and why "not ready yet" must not look like "never will be".
 *
 * `speechSynthesis.getVoices()` returns [] on its first synchronous call in Chrome
 * and Edge and only fills when `voiceschanged` fires. Deciding on that empty list
 * collapsed CONNECTING into UNAVAILABLE inside the constructor, so the state was
 * never CONNECTING, the queue was dead code, and the first Speak tap after a cold
 * start answered "no German voice" instead of speaking.
 *
 * The engine is constructed at module import, so each test configures its globals
 * first and then imports a fresh module through vi.resetModules().
 */

interface FakeVoice {
  lang: string;
  name: string;
}

class FakeUtterance {
  lang = "";
  voice: FakeVoice | null = null;
  rate = 1;
  constructor(public text: string) {}
}

class FakeSynthesis {
  voices: FakeVoice[] = [];
  spoken: string[] = [];
  cancelled = 0;
  onvoiceschanged: (() => void) | null = null;

  getVoices() {
    return this.voices;
  }

  speak(utterance: FakeUtterance) {
    this.spoken.push(utterance.text);
  }

  cancel() {
    this.cancelled++;
  }

  /** The asynchronous event the engine fires once its list is ready. */
  publishVoices(voices: FakeVoice[]) {
    this.voices = voices;
    this.onvoiceschanged?.();
  }
}

const GERMAN: FakeVoice = { lang: "de-DE", name: "Anna" };
const ENGLISH: FakeVoice = { lang: "en-US", name: "Alex" };

let synthesis: FakeSynthesis;

/** Builds a browser-shaped global and returns a freshly imported tts singleton. */
async function loadTts(initialVoices: FakeVoice[] = []) {
  synthesis = new FakeSynthesis();
  synthesis.voices = initialVoices;

  const globals = globalThis as unknown as Record<string, unknown>;
  globals.window = globalThis;
  globals.speechSynthesis = synthesis;
  globals.SpeechSynthesisUtterance = FakeUtterance;

  vi.resetModules();
  return (await import("@/lib/speech/tts")).tts;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("the engine is still connecting, not unavailable", () => {
  it("queues a phrase asked for before the voice list arrives, then speaks it", async () => {
    const tts = await loadTts([]);

    tts.speak("Guten Morgen");

    // Nothing spoken yet, and — the point — no error claiming there is no German.
    expect(synthesis.spoken).toEqual([]);
    expect(tts.getSnapshot().error).toBeNull();

    synthesis.publishVoices([ENGLISH, GERMAN]);

    expect(synthesis.spoken).toEqual(["Guten Morgen"]);
    expect(tts.getSnapshot().error).toBeNull();
  });

  it("speaks immediately once the voices are already known", async () => {
    const tts = await loadTts([GERMAN]);

    tts.speak("Hallo");

    expect(synthesis.spoken).toEqual(["Hallo"]);
  });
});

describe("the engine really has no German", () => {
  it("reports it once the list has arrived without one", async () => {
    const tts = await loadTts([]);

    tts.speak("Hallo");
    synthesis.publishVoices([ENGLISH]);

    // The phrase was queued on a promise the engine could not keep, so it is
    // reported rather than dropped in silence — somebody pressed a button for it.
    expect(synthesis.spoken).toEqual([]);
    // Against the dictionary, not the prose: this asserts which message is raised,
    // and a reworded string is a copy change rather than a failing test.
    const { t } = await import("@/lib/i18n");
    expect(tts.getSnapshot().error).toBe(t("tts.noGerman"));
  });

  it("does not stay silent forever when voiceschanged never fires", async () => {
    const tts = await loadTts([]);

    tts.speak("Hallo");
    expect(tts.getSnapshot().error).toBeNull();

    // A browser with no voices at all never publishes a list; the timeout is what
    // stops CONNECTING being permanent and every phrase being swallowed.
    await vi.advanceTimersByTimeAsync(3_000);

    expect(tts.getSnapshot().error).not.toBeNull();
  });

  it("clears the failure when asked", async () => {
    const tts = await loadTts([ENGLISH]);

    tts.speak("Hallo");
    expect(tts.getSnapshot().error).not.toBeNull();

    tts.dismissError();
    expect(tts.getSnapshot().error).toBeNull();
  });
});

describe("getSnapshot is stable for useSyncExternalStore", () => {
  it("returns the same reference until the error changes", async () => {
    const tts = await loadTts([GERMAN]);

    const first = tts.getSnapshot();
    expect(tts.getSnapshot()).toBe(first);

    tts.speak("Hallo");
    // Speaking successfully changes no state, so the reference must not churn —
    // a new object per call makes React re-render forever.
    expect(tts.getSnapshot()).toBe(first);
  });
});
