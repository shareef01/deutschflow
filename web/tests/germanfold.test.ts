import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB, foldGermanKey, germanMatchKey } from "@/lib/db/schema";
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

  it("treats the transliterated umlauts as the same word", () => {
    // A transliteration is two spellings of one word, so folding is correct here.
    expect(foldGermanKey("Übung")).toBe(foldGermanKey("Uebung"));
    expect(foldGermanKey("schön")).toBe(foldGermanKey("schoen"));
    expect(foldGermanKey("Ärger")).toBe(foldGermanKey("Aerger"));
  });

  // The regression: ß is not folded to ss in the identity key. German writes both
  // "ss" and "ß" natively and distinguishes them, so folding them merged words that
  // are not the same word - and germanTextKey is unique, so saving one after the other
  // merged and deleted a row.
  it("does not fold eszett to double-s", () => {
    expect(foldGermanKey("Maße")).not.toBe(foldGermanKey("Masse"));
    expect(foldGermanKey("Buße")).not.toBe(foldGermanKey("Busse"));
    // Not merely unequal - the sharp s survives intact.
    expect(foldGermanKey("Maße")).toBe("maße");
    expect(foldGermanKey("Buße")).toBe("buße");
    expect(foldGermanKey("Fuß")).toBe("fuß");
  });

  // The trade-off, stated so it cannot be quietly reverted: Straße and Strasse really
  // are one word and no longer merge here. germanMatchKey still matches them, so search
  // and scoring are unaffected, and a false split leaves a deletable duplicate where a
  // false merge would have destroyed a row.
  it("keeps ß and s apart for identity while the match fold still joins them", () => {
    expect(foldGermanKey("Straße")).not.toBe(foldGermanKey("Strasse"));
    expect(germanMatchKey("Straße")).toBe(germanMatchKey("Strasse"));
    expect(germanMatchKey("Straße")).toBe("strasse");
  });

  it("normalizes decomposed Unicode (NFD) to canonical NFC", () => {
    // Decomposed U + COMBINING DIAERESIS (\u0308)
    const decomposedUebung = "U\u0308bung";
    expect(foldGermanKey(decomposedUebung)).toBe(foldGermanKey("Übung"));
    expect(foldGermanKey(decomposedUebung)).toBe("uebung");
    expect(foldGermanKey("a\u0308")).toBe("ae");
    expect(foldGermanKey("o\u0308")).toBe("oe");
    expect(foldGermanKey("u\u0308")).toBe("ue");
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
      ["Straße", "straße"],
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

  // Deliberate: these now stay separate words in the library. See foldGermanKey.
  it("keeps Straße and Strasse as separate rows", async () => {
    await saveVocabulary(db, { germanText: "Straße", englishTranslation: "street" });
    await saveVocabulary(db, { germanText: "Strasse", englishTranslation: "road" });
    expect(await db.vocabulary.count()).toBe(2);
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
