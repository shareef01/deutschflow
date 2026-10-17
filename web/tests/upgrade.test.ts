import Dexie from "dexie";
import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import { getDueVocabulary } from "@/lib/db/repository";
import { importLibrary } from "@/lib/db/backup";

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

describe("upgrade 9 → 10 — nullable review event vocabulary references", () => {
  it("migrates legacy vocabularyId = 0 events to null while preserving positive ids", async () => {
    const name = `df-upgrade-v10-${Date.now()}-${counter++}`;
    const legacy = new Dexie(name);
    legacy.version(9).stores({
      vocabulary: "++id, timestamp, &germanTextKey, nextReview",
      transcripts: "++id, timestamp",
      userStats: "id",
      activityLog: "date",
      roleplayMessages: "position",
      settings: "key",
      reviewEvents: "++id, vocabularyId, reviewedAtTimestamp",
    });
    await legacy.open();
    await legacy.table("reviewEvents").bulkAdd([
      {
        id: 1,
        vocabularyId: 0,
        rating: "GOOD",
        scheduledDays: 1,
        actualDays: 1,
        reviewedAtTimestamp: 1000,
        isExtraPractice: false,
        remoteId: "11111111-1111-4111-8111-111111111111",
      },
      {
        id: 2,
        vocabularyId: 42,
        rating: "EASY",
        scheduledDays: 3,
        actualDays: 3,
        reviewedAtTimestamp: 2000,
        isExtraPractice: false,
        remoteId: "22222222-2222-4222-8222-222222222222",
      },
      {
        id: 3,
        vocabularyId: null,
        rating: "AGAIN",
        scheduledDays: 0,
        actualDays: 0,
        reviewedAtTimestamp: 3000,
        isExtraPractice: false,
        remoteId: "33333333-3333-4333-8333-333333333333",
      },
    ]);
    legacy.close();

    const db = new DeutschFlowDB(name);
    await db.open();

    const events = await db.reviewEvents.toArray();
    expect(events).toHaveLength(3);

    const event1 = events.find((e) => e.id === 1);
    expect(event1?.vocabularyId).toBeNull();
    expect(event1?.rating).toBe("GOOD");

    const event2 = events.find((e) => e.id === 2);
    expect(event2?.vocabularyId).toBe(42);
    expect(event2?.rating).toBe("EASY");

    const event3 = events.find((e) => e.id === 3);
    expect(event3?.vocabularyId).toBeNull();
    expect(event3?.rating).toBe("AGAIN");

    db.close();
  });
});

describe("backward-compatibility for previously saved backups", () => {
  it("successfully imports a version 1 backup without reviewEvents or roleplayMessages", async () => {
    const now = new Date();
    const d0 = new Date(now.getTime() - 2 * 86400000).toISOString().slice(0, 10);
    const d1 = new Date(now.getTime() - 1 * 86400000).toISOString().slice(0, 10);
    const d2 = now.toISOString().slice(0, 10);

    const v1Backup = {
      format: "deutschflow-library",
      version: 1,
      exportedAt: new Date().toISOString(),
      vocabulary: [
        {
          id: 1,
          germanText: "der Hund",
          englishTranslation: "the dog",
          timestamp: 1000,
          nextReview: 0,
          interval: 0,
          easeFactor: 2.5,
          reviewCount: 0,
          exampleSentence: "Der Hund bellt.",
          article: "der",
          plural: "Hunde",
          conjugation: "",
          synonyms: "",
          antonyms: "",
          remoteId: "a1111111-1111-4111-8111-111111111111",
          lastModifiedAt: 1000,
        },
      ],
      transcripts: [
        {
          id: 1,
          fullText: "Ich gehe spazieren.",
          timestamp: 1000,
          translation: "I am going for a walk.",
          analysisJson: "",
          remoteId: "b1111111-1111-4111-8111-111111111111",
          lastModifiedAt: 1000,
        },
      ],
      userStats: [
        { id: 1, xp: 120, streak: 3, lastActivityTimestamp: 3000 },
      ],
      activityLog: [
        { date: d0, xpGained: 20, timestamp: 1000 },
        { date: d1, xpGained: 30, timestamp: 2000 },
        { date: d2, xpGained: 40, timestamp: 3000 },
      ],
    };

    const name = `df-backup-v1-${Date.now()}-${counter++}`;
    const db = new DeutschFlowDB(name);
    await db.open();

    const result = await importLibrary(db, v1Backup);
    expect(result.vocabularyAdded).toBe(1);
    expect(result.transcriptsAdded).toBe(1);

    const words = await db.vocabulary.toArray();
    expect(words).toHaveLength(1);
    expect(words[0].germanText).toBe("der Hund");
    expect(words[0].englishTranslation).toBe("the dog");

    const transcripts = await db.transcripts.toArray();
    expect(transcripts).toHaveLength(1);
    expect(transcripts[0].fullText).toBe("Ich gehe spazieren.");

    const stats = await db.userStats.get(1);
    expect(stats?.xp).toBe(120);
    expect(stats?.streak).toBe(3);

    const activity = await db.activityLog.toArray();
    expect(activity).toHaveLength(3);

    db.close();
  });

  it("successfully imports a version 2 backup with nullable, zero, and unresolvable vocabularyId", async () => {
    const v2Backup = {
      format: "deutschflow-library",
      version: 2,
      exportedAt: new Date().toISOString(),
      vocabulary: [
        {
          id: 10,
          germanText: "die Katze",
          englishTranslation: "the cat",
          timestamp: 2000,
          nextReview: 5000,
          interval: 2,
          easeFactor: 2.6,
          reviewCount: 1,
          exampleSentence: "",
          article: "die",
          plural: "Katzen",
          conjugation: "",
          synonyms: "",
          antonyms: "",
          remoteId: "c1111111-1111-4111-8111-111111111111",
          lastModifiedAt: 2000,
        },
      ],
      transcripts: [],
      userStats: [],
      activityLog: [],
      reviewEvents: [
        {
          vocabularyId: 10, // Resolves to persisted word
          rating: "GOOD",
          scheduledDays: 2,
          actualDays: 2,
          reviewedAtTimestamp: 3000,
          isExtraPractice: false,
          remoteId: "d1111111-1111-4111-8111-111111111111",
        },
        {
          vocabularyId: null, // Nullable / orphaned
          rating: "AGAIN",
          scheduledDays: 0,
          actualDays: 0,
          reviewedAtTimestamp: 3100,
          isExtraPractice: false,
          remoteId: "d2222222-2222-4222-8222-222222222222",
        },
        {
          vocabularyId: 0, // Legacy 0
          rating: "HARD",
          scheduledDays: 1,
          actualDays: 1,
          reviewedAtTimestamp: 3200,
          isExtraPractice: false,
          remoteId: "d3333333-3333-4333-8333-333333333333",
        },
        {
          vocabularyId: 9999, // Unresolvable legacy ID
          rating: "EASY",
          scheduledDays: 4,
          actualDays: 4,
          reviewedAtTimestamp: 3300,
          isExtraPractice: false,
          remoteId: "d4444444-4444-4444-8444-444444444444",
        },
      ],
      roleplayMessages: [],
    };

    const name = `df-backup-v2-${Date.now()}-${counter++}`;
    const db = new DeutschFlowDB(name);
    await db.open();

    const result = await importLibrary(db, v2Backup);
    expect(result.vocabularyAdded).toBe(1);
    expect(result.reviewEventsAdded).toBe(4);

    const persistedVocab = (await db.vocabulary.toArray())[0];
    const events = await db.reviewEvents.toArray();
    expect(events).toHaveLength(4);

    // 10 mapped to persisted id
    expect(events.find((e) => e.remoteId === "d1111111-1111-4111-8111-111111111111")?.vocabularyId).toBe(persistedVocab.id);
    // null, 0, and 9999 are all cleanly mapped to null
    expect(events.find((e) => e.remoteId === "d2222222-2222-4222-8222-222222222222")?.vocabularyId).toBeNull();
    expect(events.find((e) => e.remoteId === "d3333333-3333-4333-8333-333333333333")?.vocabularyId).toBeNull();
    expect(events.find((e) => e.remoteId === "d4444444-4444-4444-8444-444444444444")?.vocabularyId).toBeNull();
    db.close();
  });
});
