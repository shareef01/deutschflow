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

  /**
   * The library renders this during a render pass, so a random pick changed a
   * word's example underneath the reader. The sentence is a property of the word.
   */
  it("answers the same sentence for the same word", () => {
    const first = generateExample("Wortschatz");
    for (let i = 0; i < 20; i++) {
      expect(generateExample("Wortschatz")).toBe(first);
    }
  });

  it("still spreads different words across the templates", () => {
    const words = [
      "Wortschatz", "Fenster", "Tisch", "Buch", "Katze",
      "Hund", "Baum", "Wasser", "Stadt", "Freund", "Arbeit", "Zeit",
    ];
    const shapes = new Set(words.map((w) => generateExample(w).replaceAll(w, "")));
    expect(shapes.size).toBeGreaterThan(3);
  });

  /**
   * The index is Java's String.hashCode folded with floorMod, so the two apps
   * describe a shared word identically. These are the values the Kotlin
   * companion produces for the same inputs.
   */
  it("matches the Kotlin template choice for the same word", () => {
    // "Wortschatz".hashCode() = 1394315081 → template 1
    expect(generateExample("Wortschatz")).toBe("Ich möchte mehr über 'Wortschatz' lernen.");
    // "Fenster".hashCode() = 697420477 → template 7
    expect(generateExample("Fenster")).toBe("Warum benutzt du so oft das Wort 'Fenster'?");
  });
});
