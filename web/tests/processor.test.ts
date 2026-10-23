import { afterEach, describe, expect, it, vi } from "vitest";
import { VocabularyProcessor } from "@/lib/ai/processor";
import { setCurrentLang, translate } from "@/lib/i18n";

/**
 * W-09: the provider accepted a caller's `AbortSignal` from the start, and nobody
 * supplied one. `VocabularyProcessor` — the only path the screens take into the AI
 * layer — hard-coded `undefined`, so the signal plumbing on the other side of it was
 * dead code and a translation the user had walked away from still ran to completion.
 *
 * The provider-level behaviour is already covered in groq.test.ts ("still reports the
 * caller's own abort as cancelled"). What was never covered is the wiring, which is
 * what these assert.
 */
describe("VocabularyProcessor forwards the caller's cancellation", () => {
  /** A fetch that never answers, and rejects only when its signal is aborted. */
  const stallUntilAborted = () => {
    const fetchMock = vi.fn((_url: string, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () =>
          reject(new DOMException("Aborted", "AbortError"))
        );
      })
    );
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  };

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    setCurrentLang("en");
  });

  it("cancels a translation when the caller aborts", async () => {
    const fetchMock = stallUntilAborted();
    const processor = new VocabularyProcessor();
    const controller = new AbortController();

    const pending = processor.processText("Hallo", "gsk_key", undefined, controller.signal);
    controller.abort();

    // Not a timeout, not a generic failure: the caller's own cancel, reported as one.
    expect(await pending).toEqual({
      kind: "failure",
      message: translate("en", "ai.cancelled"),
    });
    // And the request really was aborted at the socket, not merely ignored.
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;
    expect(signal?.aborted).toBe(true);
  });

  it("cancels a word lookup when the caller aborts", async () => {
    const fetchMock = stallUntilAborted();
    const processor = new VocabularyProcessor();
    const controller = new AbortController();

    const pending = processor.interrogateWord("Haus", "gsk_key", undefined, controller.signal);
    controller.abort();

    expect(await pending).toEqual({
      kind: "failure",
      message: translate("en", "ai.cancelled"),
    });
    expect(fetchMock.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
  });

  it("still sends an already-aborted signal nowhere near the network", async () => {
    const fetchMock = stallUntilAborted();
    const processor = new VocabularyProcessor();
    const controller = new AbortController();
    controller.abort();

    const result = await processor.processText("Hallo", "gsk_key", undefined, controller.signal);

    expect(result).toEqual({ kind: "failure", message: translate("en", "ai.cancelled") });
    // post() checks the signal before it opens a request.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("is unchanged for callers that pass no signal", async () => {
    // A caller with nothing to cancel must still reach the network: the guard has to
    // be the caller's signal, not "always cancel".
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      // The real endpoint, over POST - i.e. the request the provider always made.
      expect(url).toContain("api.groq.com");
      expect(init?.method).toBe("POST");
      return Promise.resolve(new Response("{}", { status: 200 }));
    });
    vi.stubGlobal("fetch", fetchMock);

    const processor = new VocabularyProcessor();
    await processor.processText("Hallo", "gsk_key");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal);
  });
});
