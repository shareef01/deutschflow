import { describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import Dexie from "dexie";
import { DeutschFlowDB } from "@/lib/db/schema";
import { findByGermanText, saveVocabulary } from "@/lib/db/repository";

/**
 * The v8 → v9 upgrade: the identity key stops folding ß to ss.
 *
 * v8's key made "Maße" (measurements) and "Masse" (mass) the same word, as it did
 * "Buße" and "Busse". `&germanTextKey` is unique, so saving either found the other
 * and merged into it, deleting one — silently, because that merge is designed to look
 * like a successful save.
 *
 * Note what this means for the fixture below: a v8 database *cannot* hold both halves
 * of either pair. The unique index refuses to seed them, which is exactly the
 * constraint that did the damage in production. So the historical state is one
 * survivor per pair, and the merge that lost the other row is not recoverable — v9
 * cannot reconstruct a row whose contents are gone, nor know which word the user
 * meant. What v9 does guarantee is that it never happens again.
 *
 * Re-keying can only split groups, never merge, so no row can be lost to the unique
 * index and no merge pass is needed.
 */

let n = 0;

/** Builds a database at version 8, the shape that shipped before v9. */
async function seedV8(rows: Record<string, unknown>[]): Promise<string> {
  const name = `eszett-upgrade-${n++}`;
  const old = new Dexie(name);
  old.version(8).stores({
    vocabulary: "++id, timestamp, &germanTextKey, nextReview",
    transcripts: "++id, timestamp",
    userStats: "id",
    activityLog: "date",
    roleplayMessages: "position",
    settings: "key",
    reviewEvents: "++id, vocabularyId, reviewedAtTimestamp",
  });
  await old.open();
  await old.table("vocabulary").bulkAdd(rows);
  old.close();
  return name;
}

/** A v8 row, keyed the way v8 keyed it: ß folded to ss. */
function row(over: Record<string, unknown>) {
  const germanText = String(over.germanText ?? "x");
  return {
    germanText,
    germanTextKey: germanText
      .toLowerCase()
      .replaceAll("ä", "ae")
      .replaceAll("ö", "oe")
      .replaceAll("ü", "ue")
      .replaceAll("ß", "ss"),
    englishTranslation: "",
    timestamp: 0,
    exampleSentence: "",
    article: "",
    plural: "",
    conjugation: "",
    synonyms: "",
    antonyms: "",
    nextReview: 0,
    interval: 0,
    easeFactor: 2.5,
    reviewCount: 0,
    remoteId: "",
    lastModifiedAt: 0,
    ...over,
  };
}

describe("v8 → v9, the eszett-preserving identity key", () => {
  it("re-keys a surviving eszett word to its own key", async () => {
    const name = await seedV8([
      // Under v8 this row was keyed "masse", indistinguishable from a "Masse" row.
      row({
        germanText: "Maße",
        germanTextKey: "masse",
        englishTranslation: "measurements",
        plural: "Maße",
        remoteId: "rem-masse",
        lastModifiedAt: 3000,
      }),
      row({ germanText: "Haus", germanTextKey: "haus", englishTranslation: "house" }),
    ]);
    const db = new DeutschFlowDB(name);
    await db.open();

    const masse = (await db.vocabulary.toArray()).find((r) => r.remoteId === "rem-masse");
    // The key moves to the ß-preserving one; the word and its data do not.
    expect(masse?.germanTextKey).toBe("maße");
    expect(masse?.germanText).toBe("Maße");
    expect(masse?.englishTranslation).toBe("measurements");
    expect(masse?.plural).toBe("Maße");
    expect(masse?.lastModifiedAt).toBe(3000);

    // The unrelated word kept the key it already had.
    const haus = (await db.vocabulary.toArray()).find((r) => r.germanText === "Haus");
    expect(haus?.germanTextKey).toBe("haus");
    db.close();
  });

  it("preserves SRS state and metadata on a word with review history", async () => {
    const name = await seedV8([
      row({
        germanText: "Fuß",
        germanTextKey: "fuss",
        englishTranslation: "foot",
        plural: "Füße",
        article: "der",
        nextReview: 8000,
        interval: 6,
        reviewCount: 4,
        easeFactor: 2.6,
        remoteId: "rem-fuss",
        lastModifiedAt: 7000,
      }),
    ]);
    const db = new DeutschFlowDB(name);
    await db.open();

    const only = (await db.vocabulary.toArray())[0];
    expect(only.germanTextKey).toBe("fuß");
    expect(only.englishTranslation).toBe("foot");
    expect(only.article).toBe("der");
    expect(only.plural).toBe("Füße");
    expect(only.nextReview).toBe(8000);
    expect(only.interval).toBe(6);
    expect(only.reviewCount).toBe(4);
    expect(only.easeFactor).toBeCloseTo(2.6);
    expect(only.remoteId).toBe("rem-fuss");
    expect(only.lastModifiedAt).toBe(7000);
    db.close();
  });

  it("still merges words that are genuinely the same", async () => {
    // The umlaut and case rules are untouched, so the pairs that were already
    // deduped keep deduping.
    const name = await seedV8([
      row({
        germanText: "Übung",
        germanTextKey: "uebung",
        englishTranslation: "exercise",
        article: "die",
        timestamp: 1000,
      }),
    ]);
    const db = new DeutschFlowDB(name);
    await db.open();

    expect((await db.vocabulary.toArray())[0].germanTextKey).toBe("uebung");

    await saveVocabulary(db, {
      germanText: "übung",
      englishTranslation: "practice",
      timestamp: 2000,
    } as never);

    const all = await db.vocabulary.toArray();
    expect(all).toHaveLength(1);
    expect(all[0].germanTextKey).toBe("uebung");
    expect(all[0].article).toBe("die");
    db.close();
  });

  it("keeps the eszett pairs apart from here on", async () => {
    // The behaviour that actually changes for the user: after the upgrade, saving one
    // half of a pair no longer destroys the other. This is the regression the whole
    // change exists to prevent.
    const name = await seedV8([
      row({ germanText: "Masse", germanTextKey: "masse", englishTranslation: "mass" }),
    ]);
    const db = new DeutschFlowDB(name);
    await db.open();

    await saveVocabulary(db, {
      germanText: "Maße",
      englishTranslation: "measurements",
      plural: "Maße",
    } as never);

    const all = await db.vocabulary.toArray();
    expect(all).toHaveLength(2);
    expect((await findByGermanText(db, "Maße"))?.englishTranslation).toBe("measurements");
    expect((await findByGermanText(db, "Masse"))?.englishTranslation).toBe("mass");
    db.close();
  });
});