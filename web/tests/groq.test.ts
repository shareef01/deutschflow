import { afterEach, describe, expect, it, vi } from "vitest";
import {
  contentOf,
  detailFrom,
  parseResponse,
  parseWordDetails,
  redactSensitiveKey,
  translateAndExtract,
  WORD_SYSTEM_PROMPT,
  DEFAULT_GROQ_MODEL,
  SUPPORTED_GROQ_MODELS,
  getActiveGroqModel,
  wasCutOff,
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

  it("redacts sensitive Groq keys and Bearer tokens in error messages", () => {
    const errorJson = JSON.stringify({
      error: { message: "Invalid API key: gsk_abcdef1234567890abcdef1234567890 provided" },
    });
    expect(detailFrom(errorJson)).toBe("Invalid API key: gsk_*** provided");
    expect(redactSensitiveKey("Error with Bearer secret_token_xyz")).toBe("Error with Bearer ***");
  });

  it("returns null when there is none", () => {
    expect(detailFrom(null)).toBeNull();
    expect(detailFrom("")).toBeNull();
    expect(detailFrom("not json")).toBeNull();
  });

  it("formats actionable guidance when model is decommissioned or not found", () => {
    const decommissioned = JSON.stringify({
      error: {
        message: "The model `mixtral-8x7b-32768` has been decommissioned. Please choose a different model.",
        code: "model_decommissioned",
      },
    });
    const result1 = detailFrom(decommissioned);
    expect(result1).toContain("The configured AI model is unavailable or retired");
    expect(result1).toContain("Please update GROQ_MODEL");
    expect(result1).toContain("mixtral-8x7b-32768");

    const notFound = JSON.stringify({
      error: {
        message: "The model `nonexistent-model` does not exist or you do not have access to it.",
        code: "model_not_found",
      },
    });
    const result2 = detailFrom(notFound);
    expect(result2).toContain("The configured AI model is unavailable or retired");
    expect(result2).toContain("Please update GROQ_MODEL");
  });
});

describe("getActiveGroqModel — model selection and fallback", () => {
  const origEnv = process.env.GROQ_MODEL;
  afterEach(() => {
    if (origEnv !== undefined) {
      process.env.GROQ_MODEL = origEnv;
    } else {
      delete process.env.GROQ_MODEL;
    }
  });

  it("uses DEFAULT_GROQ_MODEL when GROQ_MODEL is unset or blank", () => {
    delete process.env.GROQ_MODEL;
    expect(getActiveGroqModel()).toBe(DEFAULT_GROQ_MODEL);
    process.env.GROQ_MODEL = "   ";
    expect(getActiveGroqModel()).toBe(DEFAULT_GROQ_MODEL);
  });

  it("rejects retired model mixtral-8x7b-32768 and falls back to default", () => {
    process.env.GROQ_MODEL = "mixtral-8x7b-32768";
    expect(SUPPORTED_GROQ_MODELS).not.toContain("mixtral-8x7b-32768");
    expect(getActiveGroqModel()).toBe(DEFAULT_GROQ_MODEL);
  });

  it("rejects deprecated Free-tier models llama-3.1-8b-instant and llama-3.3-70b-versatile", () => {
    expect(SUPPORTED_GROQ_MODELS).not.toContain("llama-3.1-8b-instant");
    expect(SUPPORTED_GROQ_MODELS).not.toContain("llama-3.3-70b-versatile");

    process.env.GROQ_MODEL = "llama-3.1-8b-instant";
    expect(getActiveGroqModel()).toBe(DEFAULT_GROQ_MODEL);

    process.env.GROQ_MODEL = "llama-3.3-70b-versatile";
    expect(getActiveGroqModel()).toBe(DEFAULT_GROQ_MODEL);
  });

  it("accepts supported production models from SUPPORTED_GROQ_MODELS", () => {
    expect(SUPPORTED_GROQ_MODELS).toContain("openai/gpt-oss-120b");
    expect(SUPPORTED_GROQ_MODELS).toContain("openai/gpt-oss-20b");
    for (const model of SUPPORTED_GROQ_MODELS) {
      process.env.GROQ_MODEL = model;
      expect(getActiveGroqModel()).toBe(model);
    }
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

describe("parseWordDetails — the article the model returns", () => {
  /**
   * The model decides the gender, and whatever it says is written into the library
   * and then rehearsed as fact for months. Only the four values the prompt allows
   * survive; anything else is the model improvising and becomes "none".
   */
  it("keeps the three definite articles and 'none'", () => {
    for (const [given, expected] of [
      ["der", "der"],
      ["Die", "die"],
      ["  DAS  ", "das"],
      ["none", "none"],
    ] as const) {
      const details = parseWordDetails(
        JSON.stringify({ word: "Haus", article: given, meaning: "house" })
      );
      expect(details?.article).toBe(expected);
    }
  });

  it("falls back to 'none' for anything the prompt did not allow", () => {
    for (const given of ["feminine", "el", "der or die, depending", "", null]) {
      const details = parseWordDetails(
        JSON.stringify({ word: "Mädchen", article: given, meaning: "girl" })
      );
      expect(details?.article).toBe("none");
    }
  });
});

describe("wasCutOff — finish_reason length detection and error reporting", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("detects choices[0].finish_reason === 'length'", () => {
    const truncated = JSON.stringify({
      choices: [{ message: { role: "assistant", content: "{\"translation\":\"Ich bin" }, finish_reason: "length" }],
    });
    expect(wasCutOff(truncated)).toBe(true);

    const normal = JSON.stringify({
      choices: [{ message: { role: "assistant", content: "{\"translation\":\"Ich bin fertig\"}" }, finish_reason: "stop" }],
    });
    expect(wasCutOff(normal)).toBe(false);

    expect(wasCutOff("")).toBe(false);
    expect(wasCutOff("not json")).toBe(false);
    expect(wasCutOff("{}")).toBe(false);
  });

  it("reports specific cut-off guidance when response was truncated by provider length limits", async () => {
    const truncatedPayload = JSON.stringify({
      choices: [{ message: { role: "assistant", content: "{\"translation\":\"truncated" }, finish_reason: "length" }],
    });
    vi.stubGlobal("fetch", async () => new Response(truncatedPayload, { status: 200 }));

    const result = await translateAndExtract("Sehr langer Text...", "gsk_valid_key");
    expect(result.kind).toBe("failure");
    if (result.kind === "failure") {
      expect(result.message).toContain("cut off before it finished");
    }
  });
});
