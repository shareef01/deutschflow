import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import {
  findByGermanText,
  mergedWith,
  rewardXp,
  saveVocabulary,
  updateVocabulary,
} from "@/lib/db/repository";

let db: DeutschFlowDB;
let counter = 0;

beforeEach(() => {
  db = new DeutschFlowDB(`deutschflow-test-${Date.now()}-${counter++}`);
});

describe("saveVocabulary — VocabularyDao.save() port", () => {
  it("inserts a new word", async () => {
    await saveVocabulary(db, { germanText: "Hund", englishTranslation: "dog" });
    expect(await db.vocabulary.count()).toBe(1);
  });

  it("finds by german text NOCASE-style: 'hund' finds 'Hund'", async () => {
    await saveVocabulary(db, { germanText: "Hund", englishTranslation: "dog" });
    const found = await findByGermanText(db, "hund");
    expect(found?.germanText).toBe("Hund");
  });

  it("folds a re-save into the surviving row instead of duplicating", async () => {
    await saveVocabulary(db, {
      germanText: "Übung",
      englishTranslation: "exercise",
      exampleSentence: "",
    });

    // A later save fills the grammar the first sighting lacked.
    await saveVocabulary(db, {
      germanText: "Übung",
      englishTranslation: "exercise",
      article: "die",
      plural: "die Übungen",
    });

    expect(await db.vocabulary.count()).toBe(1);
    const row = (await db.vocabulary.toArray())[0];
    // Identity is not up for negotiation: the first spelling survives, the
    // newcomer's fields win where they are filled.
    expect(row.germanText).toBe("Übung");
    expect(row.article).toBe("die");
    expect(row.plural).toBe("die Übungen");
    expect(row.exampleSentence).toBe("");
  });

  it("treats Übung and Uebung as one word", async () => {
    // This assertion used to run the other way, pinning Room's NOCASE behaviour as
    // correct: NOCASE folds ASCII A-Z only, so the two spellings were two rows —
    // and so were "Übung"/"übung" and "Öl"/"öl". Both platforms now fold the way
    // German does, which is also the fold lib/scoring.ts has always used to judge
    // pronunciation. See tests/germanfold.test.ts for the full table.
    await saveVocabulary(db, { germanText: "Übung", englishTranslation: "exercise" });
    await saveVocabulary(db, { germanText: "Uebung", englishTranslation: "exercise" });
    expect(await db.vocabulary.count()).toBe(1);
  });

  it("takes the later timestamp so a touched word surfaces at the top", async () => {
    await saveVocabulary(db, {
      germanText: "Hund",
      englishTranslation: "dog",
      timestamp: 1_000,
    });
    await saveVocabulary(db, {
      germanText: "hund",
      englishTranslation: "dog",
      timestamp: 2_000,
    });
    const row = (await db.vocabulary.toArray())[0];
    expect(row.timestamp).toBe(2_000);
  });

  it("a rename onto an existing name merges instead of throwing", async () => {
    const first = { germanText: "Hund", englishTranslation: "dog", article: "der" };
    const second = { germanText: "Katze", englishTranslation: "cat" };
    await saveVocabulary(db, first);
    await saveVocabulary(db, second);

    const katze = (await findByGermanText(db, "Katze"))!;
    // Rename Katze -> Hund: the two rows fold together, the newcomer goes.
    await saveVocabulary(db, {
      id: katze.id,
      germanText: "Hund",
      englishTranslation: "cat",
    });

    expect(await db.vocabulary.count()).toBe(1);
    const row = (await db.vocabulary.toArray())[0];
    expect(row.germanText).toBe("Hund");
    expect(row.englishTranslation).toBe("cat");
    expect(row.article).toBe("der");
  });

  it("advances lastModifiedAt on save and merge", async () => {
    const before = Date.now();
    await saveVocabulary(db, {
      germanText: "Buch",
      englishTranslation: "book",
      lastModifiedAt: 1000,
    });
    const first = (await findByGermanText(db, "Buch"))!;
    expect(first.lastModifiedAt).toBeGreaterThanOrEqual(before);

    await saveVocabulary(db, {
      germanText: "Buch",
      englishTranslation: "tome",
      lastModifiedAt: 2000,
    });
    const merged = (await findByGermanText(db, "Buch"))!;
    expect(merged.lastModifiedAt).toBeGreaterThanOrEqual(first.lastModifiedAt);
  });

  it("updateVocabulary advances lastModifiedAt", async () => {
    await saveVocabulary(db, { germanText: "Tisch", englishTranslation: "table" });
    const row = (await findByGermanText(db, "Tisch"))!;
    const oldMod = row.lastModifiedAt;

    await new Promise((r) => setTimeout(r, 5));
    await updateVocabulary(db, { ...row, englishTranslation: "desk" });
    const updated = (await findByGermanText(db, "Tisch"))!;
    expect(updated.lastModifiedAt).toBeGreaterThan(oldMod);
    expect(updated.englishTranslation).toBe("desk");
  });
});

describe("mergedWith pure function", () => {
  it("advances lastModifiedAt to max of existing, incoming, and now", () => {
    const existing = {
      id: 1,
      germanText: "Hund",
      germanTextKey: "hund",
      englishTranslation: "dog",
      timestamp: 1000,
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
      remoteId: "uuid-1",
      lastModifiedAt: 1000,
    };

    const merged = mergedWith(existing, { germanText: "Hund", englishTranslation: "hound", lastModifiedAt: 2000 }, 3000);
    expect(merged.lastModifiedAt).toBe(3000);
    expect(merged.englishTranslation).toBe("hound");

    const futureMerged = mergedWith(existing, { germanText: "Hund", englishTranslation: "hound", lastModifiedAt: 5000 }, 3000);
    expect(futureMerged.lastModifiedAt).toBe(5000);
  });
});

describe("rewardXp — the atomic read-modify-write", () => {
  it("banks XP and starts the streak on the first award", async () => {
    const stats = await rewardXp(db);
    expect(stats.xp).toBe(10);
    expect(stats.streak).toBe(1);
    expect(await db.userStats.count()).toBe(1);
  });

  it("keeps one row across awards", async () => {
    await rewardXp(db);
    await rewardXp(db, 5);
    const stats = await rewardXp(db);
    expect(stats.xp).toBe(25);
    expect(await db.userStats.count()).toBe(1);
  });
});
