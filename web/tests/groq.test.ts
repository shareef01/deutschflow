import { afterEach, describe, expect, it, vi } from "vitest";
import {
  contentOf,
  detailFrom,
  parseResponse,
  parseWordDetails,
  translateAndExtract,
  WORD_SYSTEM_PROMPT,
} from "@/lib/ai/groq";

describe("contentOf — the OpenAI chat response shape", () => {
  it("pulls the assistant text from a choices[0].message", () => {
    const json = JSON.stringify({
      choices: [{ message: { role: "assistant", content: "Translation: Hello" } }],
    });
    expect(contentOf(json)).toBe("Translation: Hello");
  });

  it("returns empty for malformed JSON", () => {
    expect(contentOf("not json")).toBe("");
  });
});

describe("detailFrom — the provider's own error sentence", () => {
  it("extracts error.message from the body", () => {
    expect(detailFrom('{"error":{"message":"invalid api key"}}')).toBe("invalid api key");
  });

  it("returns null when there is none", () => {
    expect(detailFrom(null)).toBeNull();
    expect(detailFrom("")).toBeNull();
    expect(detailFrom("not json")).toBeNull();
  });
});

describe("parseResponse — translation extraction", () => {
  it("parses the canonical three-field answer", () => {
    const result = parseResponse(
      "Translation: Hello, how are you?\nKeywords: [Hallo, Deutsch, Lernen]\nExample: Ich lerne jeden Tag Deutsch."
    );
    expect(result).toEqual({
      kind: "success",
      translation: "Hello, how are you?",
      keywords: ["Hallo", "Deutsch", "Lernen"],
      example: "Ich lerne jeden Tag Deutsch.",
      // Absent from the answer, so the Grammar Spotlight has nothing to show.
      grammarNotes: [],
    });
  });

  it("tolerates the markdown the model adds unbidden", () => {
    const result = parseResponse(
      "**Translation:** Hello\n- **Keywords:** [Hallo, Deutsch]\n*Example:* Ich lerne Deutsch."
    );
    expect(result?.translation).toBe("Hello");
    expect(result?.keywords).toEqual(["Hallo", "Deutsch"]);
  });

  it("matches prefixes case-insensitively", () => {
    const result = parseResponse("TRANSLATION: Guten Morgen");
    expect(result?.translation).toBe("Guten Morgen");
  });

  it("returns null when no translation was produced", () => {
    expect(parseResponse("Sorry, I could not help.")).toBeNull();
  });

  it("never lets a failure reach the translation field", () => {
    const result = parseResponse("Translation failed: bad key");
    // "Translation failed" is not a "Translation:"-prefixed line, so it parses
    // to null rather than to a storable English translation.
    expect(result).toBeNull();
  });
});

describe("parseWordDetails — single-word interrogation", () => {
  it("parses the strict JSON schema", () => {
    const details = parseWordDetails(
      '{"word":"die Übung","article":"die","plural":"die Übungen","conjugation_or_infinitive":"","meaning":"exercise","example_sentence":"Ich mache meine Übungen."}'
    );
    expect(details).toEqual({
      word: "die Übung",
      article: "die",
      plural: "die Übungen",
      conjugationOrInfinitive: "",
      meaning: "exercise",
      exampleSentence: "Ich mache meine Übungen.",
      // Omitted by the model rather than empty - both read as "none known".
      synonyms: [],
      antonyms: [],
    });
  });

  it("defaults article to none and tolerates code fences", () => {
    const details = parseWordDetails(
      '```json\n{"word":"lernen","article":"","plural":"","conjugation_or_infinitive":"lernen","meaning":"to learn","example_sentence":"Wir lernen Deutsch."}\n```'
    );
    expect(details?.article).toBe("none");
    expect(details?.conjugationOrInfinitive).toBe("lernen");
  });

  it("returns null for unparseable or empty answers", () => {
    expect(parseWordDetails("no json here")).toBeNull();
    expect(parseWordDetails('{"word":"","meaning":""}')).toBeNull();
  });

  it("the interrogation prompt demands the exact JSON shape", () => {
    expect(WORD_SYSTEM_PROMPT).toContain('"article":"der|die|das|none"');
    expect(WORD_SYSTEM_PROMPT).toContain("conjugation_or_infinitive");
  });
});

describe("translateAndExtract — the no-key path", () => {
  it("returns the no-key failure without touching the network", async () => {
    const result = await translateAndExtract("Hallo", "");
    expect(result.kind).toBe("failure");
    if (result.kind === "failure") {
      expect(result.message).toContain("Groq");
    }
  });
});

describe("translateAndExtract — the provider's error precedence", () => {
  afterEach(() => vi.unstubAllGlobals());

  const respond = (status: number, body: string) =>
    vi.stubGlobal("fetch", async () => new Response(body, { status }));

  it("a 401 reports the rejected key when the body carries no detail", async () => {
    respond(401, '{"error":{}}');
    const result = await translateAndExtract("Hallo", "gsk_key");
    expect(result.kind).toBe("failure");
    if (result.kind === "failure") expect(result.message).toContain("rejected");
  });

  it("a 429 reports rate limiting", async () => {
    respond(429, '{"error":{}}');
    const result = await translateAndExtract("Hallo", "gsk_key");
    expect(result.kind).toBe("failure");
    if (result.kind === "failure") expect(result.message).toContain("minute");
  });

  it("the body's own detail wins over the canned status text", async () => {
    respond(401, '{"error":{"message":"quota exceeded for the day"}}');
    const result = await translateAndExtract("Hallo", "gsk_key");
    expect(result.kind).toBe("failure");
    if (result.kind === "failure") expect(result.message).toContain("quota exceeded");
  });

  it("an unknown status reports the status code", async () => {
    respond(503, "{}");
    const result = await translateAndExtract("Hallo", "gsk_key");
    expect(result.kind).toBe("failure");
    if (result.kind === "failure") expect(result.message).toContain("503");
  });

  it("wraps all of it in the localized 'translation failed' frame", async () => {
    respond(401, '{"error":{}}');
    const result = await translateAndExtract("Hallo", "gsk_key");
    if (result.kind === "failure") expect(result.message).toMatch(/failed|fehlgeschlagen/i);
  });
});
