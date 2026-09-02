import { describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import Dexie from "dexie";
import { DeutschFlowDB } from "@/lib/db/schema";

/**
 * The v4 → v5 upgrade, which has to merge before it re-keys.
 *
 * Rows that were distinct under the ASCII fold collide under the German one, and
 * `&germanTextKey` is unique — so a naive re-key throws halfway and leaves the
 * database in neither shape. This is the migration most able to lose a user's
 * words, so it is asserted on a fixture that contains every colliding case.
 */

let n = 0;

/** Builds a database at version 4, the shape that shipped before the fold changed. */
async function seedV4(rows: Record<string, unknown>[]): Promise<string> {
  const name = `fold-upgrade-${n++}`;
  const old = new Dexie(name);
  old.version(4).stores({
    vocabulary: "++id, timestamp, &germanTextKey, nextReview",
    transcripts: "++id, timestamp",
    userStats: "id",
    activityLog: "date",
    settings: "key",
  });
  await old.open();
  await old.table("vocabulary").bulkAdd(rows);
  old.close();
  return name;
}

function row(over: Record<string, unknown>) {
  return {
    germanText: "x",
    // The OLD ASCII-only fold, which is what these rows were stored with.
    germanTextKey: String(over.germanText ?? "x").replace(/[A-Z]/g, (c) => c.toLowerCase()),
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
    remoteId: "r",
    lastModifiedAt: 0,
    ...over,
  };
}

describe("v4 → v5, the German fold", () => {
  it("folds umlaut duplicates together without losing a field or a schedule", async () => {
    const name = await seedV4([
      row({ germanText: "Übung", englishTranslation: "exercise", timestamp: 100,
            article: "die", plural: "Übungen", nextReview: 5000, interval: 30,
            easeFactor: 2.5, reviewCount: 8 }),
      row({ germanText: "übung", englishTranslation: "practice", timestamp: 200,
            exampleSentence: "Eine Übung.", nextReview: 100, interval: 1,
            easeFactor: 2.3, reviewCount: 1 }),
    ]);

    const db = new DeutschFlowDB(name);
    await db.open();

    expect(await db.vocabulary.count()).toBe(1);
    const merged = (await db.vocabulary.toArray())[0];
    expect(merged.germanTextKey).toBe("uebung");
    expect(merged.article).toBe("die");
    expect(merged.plural).toBe("Übungen");
    expect(merged.exampleSentence).toBe("Eine Übung.");
    expect(merged.englishTranslation).toBe("practice");
    expect(merged.timestamp).toBe(200);
    // The month of reviews wins over the week of them.
    expect(merged.reviewCount).toBe(8);
    expect(merged.interval).toBe(30);
    expect(merged.nextReview).toBe(5000);
    db.close();
  });

  it("re-keys a word that has no duplicate", async () => {
    const name = await seedV4([row({ germanText: "Straße", englishTranslation: "street" })]);
    const db = new DeutschFlowDB(name);
    await db.open();

    const only = (await db.vocabulary.toArray())[0];
    expect(only.germanTextKey).toBe("strasse");
    expect(only.germanText).toBe("Straße");
    db.close();
  });

  it("leaves words that were already distinct alone", async () => {
    const name = await seedV4([
      row({ germanText: "schon", englishTranslation: "already" }),
      row({ germanText: "schön", englishTranslation: "beautiful" }),
      row({ germanText: "Hund", englishTranslation: "dog" }),
    ]);
    const db = new DeutschFlowDB(name);
    await db.open();

    expect(await db.vocabulary.count()).toBe(3);
    db.close();
  });

  it("survives a three-way collision", async () => {
    // Three spellings that the OLD ASCII fold kept apart — "Übung" folded to
    // "Übung" because Ü is not A–Z — and the German fold brings together.
    const name = await seedV4([
      row({ germanText: "Übung", englishTranslation: "exercise", timestamp: 1 }),
      row({ germanText: "übung", englishTranslation: "practice", timestamp: 2 }),
      row({ germanText: "Uebung", englishTranslation: "drill", timestamp: 3 }),
    ]);
    const db = new DeutschFlowDB(name);
    await db.open();

    expect(await db.vocabulary.count()).toBe(1);
    const merged = (await db.vocabulary.toArray())[0];
    expect(merged.germanTextKey).toBe("uebung");
    expect(merged.englishTranslation).toBe("drill");
    db.close();
  });

  it("keeps an empty library empty", async () => {
    const name = await seedV4([]);
    const db = new DeutschFlowDB(name);
    await db.open();
    expect(await db.vocabulary.count()).toBe(0);
    db.close();
  });
});
