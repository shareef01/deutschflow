import { describe, expect, it } from "vitest";
import { matchesGerman, matchesPlain, prepareGermanQuery } from "@/lib/search";

// R-07 (AUDIT_2026-10-22): "madchen" didn't find "Mädchen".
function finds(text: string, query: string): boolean {
  const q = prepareGermanQuery(query);
  if (!q) throw new Error("blank query");
  return matchesGerman(text, q);
}

describe("German search matching", () => {
  it("finds a word from each way people type it", () => {
    for (const query of ["Mädchen", "mädchen", "maedchen", "madchen", "MADCHEN"]) {
      expect(finds("das Mädchen", query), query).toBe(true);
    }
  });

  it("finds ß from ss, and the reverse", () => {
    expect(finds("die Straße", "strasse")).toBe(true);
    expect(finds("die Strasse", "straße")).toBe(true);
  });

  it("matches substrings, not just whole words", () => {
    expect(finds("Ich möchte einen Kaffee", "mochte ein")).toBe(true);
  });

  it("does not match unrelated text", () => {
    expect(finds("der Hund", "katze")).toBe(false);
    expect(finds("die Tür", "tor")).toBe(false);
  });

  it("lets an umlaut and its plain vowel meet, which is fine for a search box", () => {
    // "schon" and "schön" are different words, but someone typing either
    // without umlauts wants to see both.
    expect(finds("schon", "schön")).toBe(true);
  });

  it("treats a blank query as no filter", () => {
    expect(prepareGermanQuery("   ")).toBeNull();
  });

  it("matches the English side case-insensitively and tolerates a missing field", () => {
    const q = prepareGermanQuery("Apple")!;
    expect(matchesPlain("the apple", q)).toBe(true);
    expect(matchesPlain(undefined, q)).toBe(false);
  });
});
