import { describe, expect, it } from "vitest";
import { generateExample } from "@/lib/ai/processor";

describe("generateExample — the hand-typed fallback", () => {
  it("always returns a German sentence containing the word", () => {
    for (let i = 0; i < 50; i++) {
      const example = generateExample("Wortschatz");
      expect(example).toContain("Wortschatz");
      expect(example.length).toBeGreaterThan(10);
    }
  });

  it("keeps the curated sentences for the common words", () => {
    expect(generateExample("hallo")).toBe("Hallo, wie geht es dir?");
    expect(generateExample("Deutsch")).toBe("Ich lerne jeden Tag Deutsch.");
    expect(generateExample("lernen")).toBe("Wir lernen zusammen in der Schule.");
    expect(generateExample("Sprechen")).toBe("Kannst du bitte langsamer sprechen?");
  });
});
