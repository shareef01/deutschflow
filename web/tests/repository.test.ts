import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import {
  findByGermanText,
  rewardXp,
  saveVocabulary,
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

  it("treats Übung and Uebung as distinct — NOCASE folds ASCII only", async () => {
    // Room parity: SQLite's NOCASE does not equate umlauts with their
    // transliterations, so the two spellings are different words here too
    // (the umlaut-to-ue folding is pronunciation scoring, not identity).
    await saveVocabulary(db, { germanText: "Übung", englishTranslation: "exercise" });
    await saveVocabulary(db, { germanText: "Uebung", englishTranslation: "exercise" });
    expect(await db.vocabulary.count()).toBe(2);
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
