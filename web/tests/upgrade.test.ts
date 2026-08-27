import Dexie from "dexie";
import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import { getDueVocabulary } from "@/lib/db/repository";

/**
 * The schema's own comment is the spec: an indexed field whose key path is
 * undefined is left out of the index entirely, so every upgrade that introduces
 * one must seed it. These tests open databases shaped like the versions the app
 * actually shipped, at every stop in the chain, and check what survives.
 */

let counter = 0;

beforeEach(() => {
  counter++;
});

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** A database as version 1 shipped: no SRS columns, no activityLog, no sync fields. */
async function openLegacyV1(name: string): Promise<void> {
  const db = new Dexie(name);
  db.version(1).stores({
    vocabulary: "++id, timestamp, &germanTextKey",
    transcripts: "++id, timestamp",
    userStats: "id",
    settings: "key",
  });
  await db.open();
  // Untyped table access: the legacy database predates the shapes it will grow into.
  await db.table("vocabulary").bulkAdd([
    {
      germanText: "Hund",
      germanTextKey: "hund",
      englishTranslation: "dog",
      timestamp: 1,
      exampleSentence: "",
      article: "",
      plural: "",
      conjugation: "",
    },
    {
      germanText: "Katze",
      germanTextKey: "katze",
      englishTranslation: "cat",
      timestamp: 2,
      exampleSentence: "",
      article: "",
      plural: "",
      conjugation: "",
    },
  ]);
  await db.table("transcripts").bulkAdd([{ fullText: "Ich habe einen Hund.", timestamp: 3 }]);
  await db.table("userStats").add({ id: 1, xp: 40, streak: 2, lastActivityTimestamp: 3 });
  db.close();
}

describe("upgrade 1 → 4 — the pre-SRS library", () => {
  it("seeds the SRS columns so saved words survive the due query", async () => {
    const name = `df-upgrade-${Date.now()}-${counter}`;
    await openLegacyV1(name);
    const db = new DeutschFlowDB(name);
    await db.open();

    // The regression the version 2 comment warns about: without the seeding,
    // nextReview is undefined on every pre-existing row, the rows drop out of
    // the index, and Study opens empty for anyone who already had a library.
    const due = await getDueVocabulary(db, Date.now());
    expect(due.map((v) => v.germanText).sort()).toEqual(["Hund", "Katze"]);

    const row = await db.vocabulary.where("germanTextKey").equals("hund").first();
    expect(row?.nextReview).toBe(0);
    expect(row?.interval).toBe(0);
    expect(row?.easeFactor).toBe(2.5);
    expect(row?.reviewCount).toBe(0);
    expect(row?.synonyms).toBe("");
    expect(row?.antonyms).toBe("");
  });

  it("lands the seeded rows inside the nextReview index, not beside it", async () => {
    const name = `df-upgrade-${Date.now()}-${counter}`;
    await openLegacyV1(name);
    const db = new DeutschFlowDB(name);
    await db.open();

    // Direct index read: an unseeded key path yields zero rows here even when
    // the objects themselves carry the field through a later .modify().
    expect(await db.vocabulary.where("nextReview").equals(0).count()).toBe(2);
  });

  it("backfills the sync columns on vocabulary and transcripts", async () => {
    const name = `df-upgrade-${Date.now()}-${counter}`;
    await openLegacyV1(name);
    const db = new DeutschFlowDB(name);
    await db.open();

    const words = await db.vocabulary.toArray();
    expect(words).toHaveLength(2);
    for (const word of words) {
      expect(word.remoteId).toMatch(UUID_V4);
      expect(word.lastModifiedAt).toBeGreaterThan(0);
    }

    const transcripts = await db.transcripts.toArray();
    expect(transcripts).toHaveLength(1);
    expect(transcripts[0].remoteId).toMatch(UUID_V4);
    expect(transcripts[0].lastModifiedAt).toBeGreaterThan(0);
  });

  it("brings userStats and the v4 tables along untouched", async () => {
    const name = `df-upgrade-${Date.now()}-${counter}`;
    await openLegacyV1(name);
    const db = new DeutschFlowDB(name);
    await db.open();

    expect(await db.userStats.get(1)).toMatchObject({ xp: 40, streak: 2 });
    expect(await db.activityLog.count()).toBe(0);
    expect(await db.settings.count()).toBe(0);
  });

  it("keeps the germanTextKey unique constraint working after the trip", async () => {
    const name = `df-upgrade-${Date.now()}-${counter}`;
    await openLegacyV1(name);
    const db = new DeutschFlowDB(name);
    await db.open();

    // The &germanTextKey index survived the schema changes: a re-save of the
    // same word (as the merge flow produces) must still collide, not duplicate.
    await expect(
      db.vocabulary.add({
        germanText: "Hund",
        germanTextKey: "hund",
        englishTranslation: "dog",
        timestamp: 9,
        exampleSentence: "",
        article: "",
        plural: "",
        conjugation: "",
        nextReview: 0,
        interval: 0,
        easeFactor: 2.5,
        reviewCount: 0,
        synonyms: "",
        antonyms: "",
        remoteId: "11111111-2222-4333-8444-555555555555",
        lastModifiedAt: 9,
      })
    ).rejects.toThrow();
  });
});

describe("upgrade 2 → 4 — an SRS-era library gains the sync fields", () => {
  it("seeds remoteId without disturbing schedule state", async () => {
    const name = `df-upgrade-${Date.now()}-${counter}`;
    const legacy = new Dexie(name);
    legacy.version(2).stores({
      vocabulary: "++id, timestamp, &germanTextKey, nextReview",
      transcripts: "++id, timestamp",
      userStats: "id",
      settings: "key",
    });
    await legacy.open();
    await legacy.table("vocabulary").add({
      germanText: "Hund",
      germanTextKey: "hund",
      englishTranslation: "dog",
      timestamp: 1,
      exampleSentence: "",
      article: "",
      plural: "",
      conjugation: "",
      nextReview: 12345,
      interval: 4,
      easeFactor: 2.6,
      reviewCount: 2,
      synonyms: "Wauzi",
      antonyms: "",
    });
    legacy.close();

    const db = new DeutschFlowDB(name);
    await db.open();

    const row = await db.vocabulary.where("germanTextKey").equals("hund").first();
    // Version 4's callback only fills the sync columns; the schedule a user
    // has already built belongs to them.
    expect(row?.remoteId).toMatch(UUID_V4);
    expect(row?.lastModifiedAt).toBeGreaterThan(0);
    expect(row?.nextReview).toBe(12345);
    expect(row?.interval).toBe(4);
    expect(row?.easeFactor).toBe(2.6);
    expect(row?.reviewCount).toBe(2);
    expect(row?.synonyms).toBe("Wauzi");
  });
});
