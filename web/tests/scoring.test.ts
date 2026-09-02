import { describe, expect, it } from "vitest";
import { evaluateMatch, foldGerman } from "@/lib/scoring";

describe("foldGerman", () => {
  it("transliterates umlauts to the keyboard spellings", () => {
    expect(foldGerman("Übung")).toBe("uebung");
    expect(foldGerman("Öffnung")).toBe("oeffnung");
    expect(foldGerman("Äpfel")).toBe("aepfel");
    expect(foldGerman("Straße")).toBe("strasse");
  });

  it("is case-insensitive and locale-invariant", () => {
    expect(foldGerman("HALLO")).toBe("hallo");
    expect(foldGerman("Ich")).toBe("ich");
  });
});

describe("evaluateMatch", () => {
  it("accepts umlaut spelling variants (Uebung == Übung)", () => {
    const { results } = evaluateMatch("Übung macht den Meister", "Uebung macht den meister");
    expect(results.every((r) => r.isCorrect)).toBe(true);
  });

  it("scores a perfect match as PERFECT", () => {
    const { feedback } = evaluateMatch("Ich lerne Deutsch", "ich lerne deutsch");
    expect(feedback).toBe("PERFECT");
  });

  it("gives per-word verdicts on a partial match", () => {
    const { results, feedback } = evaluateMatch("Ich lerne Deutsch", "Ich spreche Deutsch");
    // Two of three is 67%, under the 75% bar. This asserted GOOD while the bar
    // was "over half", which reported "most words were clear" for missing a third
    // of the sentence.
    expect(feedback).toBe("KEEP_GOING");
    expect(results.map((r) => [r.word, r.isCorrect])).toEqual([
      ["Ich", true],
      ["lerne", false],
      ["Deutsch", true],
    ]);
  });

  it("scores three quarters as GOOD", () => {
    const { results, feedback } = evaluateMatch("eins zwei drei vier", "eins zwei drei");
    expect(results.filter((r) => r.isCorrect)).toHaveLength(3);
    expect(feedback).toBe("GOOD");
  });

  it("scores three of five as KEEP_GOING", () => {
    const { feedback } = evaluateMatch("eins zwei drei vier fuenf", "eins zwei drei");
    expect(feedback).toBe("KEEP_GOING");
  });

  it("scores an unrelated attempt as KEEP_GOING", () => {
    const { feedback } = evaluateMatch("Hallo", "");
    expect(feedback).toBe("KEEP_GOING");
  });

  it("strips punctuation before comparing", () => {
    const { results } = evaluateMatch("Wie geht's, dir?", "wie gehts dir");
    expect(results.every((r) => r.isCorrect)).toBe(true);
  });

  it("returns NONE when the target has no words", () => {
    expect(evaluateMatch("", "").feedback).toBe("NONE");
  });

  it("keeps the target word as written, not folded, for display", () => {
    const { results } = evaluateMatch("Übung", "Uebung");
    expect(results[0].word).toBe("Übung");
  });

  it("counts word order against the speaker", () => {
    // A set-membership check scored a reversed sentence as PERFECT, on the one
    // screen whose job is telling you whether you said it right. The alignment
    // keeps the longest ordered run, so reversing three words leaves one in place.
    const { results, feedback } = evaluateMatch("Ich lerne Deutsch", "Deutsch lerne Ich");
    expect(results.filter((r) => r.isCorrect)).toHaveLength(1);
    expect(feedback).toBe("KEEP_GOING");
  });

  it("requires a repeated target word to actually be repeated", () => {
    const once = evaluateMatch("die Frau und die Katze", "die Frau und Katze");
    expect(once.results.filter((r) => r.isCorrect)).toHaveLength(4);
    expect(once.results[3].isCorrect).toBe(false);

    const twice = evaluateMatch("die Frau und die Katze", "die Frau und die Katze");
    expect(twice.results.every((r) => r.isCorrect)).toBe(true);
    expect(twice.feedback).toBe("PERFECT");
  });

  it("does not penalise filler around the target", () => {
    const { results, feedback } = evaluateMatch(
      "Ich lerne Deutsch",
      "also ähm Ich lerne Deutsch ja"
    );
    expect(results.every((r) => r.isCorrect)).toBe(true);
    expect(feedback).toBe("PERFECT");
  });

  it("does not fold genuinely different words together", () => {
    // schön and schon are different words; only the first folds to schoen.
    const { results, feedback } = evaluateMatch("schoen", "schon");
    expect(results[0].isCorrect).toBe(false);
    expect(feedback).toBe("KEEP_GOING");
  });
});
