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

  it("scores a partial match as GOOD with per-word verdicts", () => {
    const { results, feedback } = evaluateMatch("Ich lerne Deutsch", "Ich spreche Deutsch");
    expect(feedback).toBe("GOOD");
    expect(results.map((r) => [r.word, r.isCorrect])).toEqual([
      ["Ich", true],
      ["lerne", false],
      ["Deutsch", true],
    ]);
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
});
