import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB, foldGermanKey } from "@/lib/db/schema";
import { saveVocabulary, findByGermanText } from "@/lib/db/repository";

/**
 * Duplicate detection, for a language with umlauts.
 *
 * The fold used to be ASCII-only, matching SQLite's NOCASE — including the part
 * that was wrong for German. These are the pairs that decide whether the library
 * quietly accumulates copies.
 */

describe("foldGermanKey", () => {
  it("folds case, including umlauts", () => {
    expect(foldGermanKey("Hund")).toBe(foldGermanKey("hund"));
    expect(foldGermanKey("Übung")).toBe(foldGermanKey("übung"));
    expect(foldGermanKey("Öl")).toBe(foldGermanKey("öl"));
    expect(foldGermanKey("Ärger")).toBe(foldGermanKey("ärger"));
  });

  it("treats the transliterated spellings as the same word", () => {
    expect(foldGermanKey("Übung")).toBe(foldGermanKey("Uebung"));
    expect(foldGermanKey("Straße")).toBe(foldGermanKey("Strasse"));
    expect(foldGermanKey("schön")).toBe(foldGermanKey("schoen"));
  });

  it("keeps genuinely different words apart", () => {
    expect(foldGermanKey("Hund")).not.toBe(foldGermanKey("Hand"));
    expect(foldGermanKey("schon")).not.toBe(foldGermanKey("schön"));
  });

  it("is locale-invariant", () => {
    // A default-locale lowercase under tr-TR maps I to a dotless ı.
    expect(foldGermanKey("ICH")).toBe("ich");
  });

  it("agrees with the Kotlin germanKey on the shared table", () => {
    // The same fixture asserted in VocabularyEntityKeyTest.kt.
    const table: [string, string][] = [
      ["Hund", "hund"],
      ["Übung", "uebung"],
      ["übung", "uebung"],
      ["Uebung", "uebung"],
      ["Straße", "strasse"],
      ["Strasse", "strasse"],
      ["Öl", "oel"],
      ["Ärger", "aerger"],
      ["  das Haus  ", "das haus"],
    ];
    for (const [given, expected] of table) {
      expect(foldGermanKey(given)).toBe(expected);
    }
  });
});

describe("saving an umlaut word twice", () => {
  let db: DeutschFlowDB;
  let n = 0;

  beforeEach(async () => {
    db = new DeutschFlowDB(`fold-test-${n++}`);
    await db.open();
  });

  it("merges rather than making a second row", async () => {
    await saveVocabulary(db, {
      germanText: "Übung",
      englishTranslation: "exercise",
      article: "die",
    });
    await saveVocabulary(db, {
      germanText: "übung",
      englishTranslation: "practice",
      plural: "Übungen",
    });

    expect(await db.vocabulary.count()).toBe(1);
    const row = (await db.vocabulary.toArray())[0];
    // The merge keeps what each copy knew.
    expect(row.article).toBe("die");
    expect(row.plural).toBe("Übungen");
    expect(row.englishTranslation).toBe("practice");
  });

  it("merges Straße and Strasse", async () => {
    await saveVocabulary(db, { germanText: "Straße", englishTranslation: "street" });
    await saveVocabulary(db, { germanText: "Strasse", englishTranslation: "road" });
    expect(await db.vocabulary.count()).toBe(1);
  });

  it("finds a word however it was written", async () => {
    await saveVocabulary(db, { germanText: "Übung", englishTranslation: "exercise" });
    expect((await findByGermanText(db, "uebung"))?.englishTranslation).toBe("exercise");
    expect((await findByGermanText(db, "ÜBUNG"))?.englishTranslation).toBe("exercise");
  });

  it("still keeps different words apart", async () => {
    await saveVocabulary(db, { germanText: "schon", englishTranslation: "already" });
    await saveVocabulary(db, { germanText: "schön", englishTranslation: "beautiful" });
    expect(await db.vocabulary.count()).toBe(2);
  });
});
