import { beforeEach, describe, expect, it, vi } from "vitest";
import type { PromiseExtended } from "dexie";
import { DeutschFlowDB, type VocabularyEntry } from "@/lib/db/schema";
import {
  findByGermanText,
  mergedWith,
  rewardXp,
  saveVocabulary,
  updateVocabulary,
  withFurthestScheduleOf,
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

  /**
   * The library's edit sheet writes the row back through saveVocabulary, which
   * builds a fresh record from a fixed list of fields. Every field missing from
   * that list was deleted by an edit: renaming a word also erased when it was
   * last studied, which is the value the merge rules use to decide which of two
   * copies of a card keeps its progress.
   */
  it("keeps a studied card's lastStudiedAt when the user edits it", async () => {
    await saveVocabulary(db, { germanText: "Haus", englishTranslation: "house" });
    const row = (await findByGermanText(db, "Haus"))!;
    const studiedAt = 1_700_000_000_000;
    // What recordReview writes when the card is answered Good.
    await db.vocabulary.update(row.id!, {
      reviewCount: 4,
      interval: 9,
      nextReview: studiedAt + 9 * 86_400_000,
      lastStudiedAt: studiedAt,
    });

    const beforeEdit = (await findByGermanText(db, "Haus"))!;
    await saveVocabulary(db, { ...beforeEdit, englishTranslation: "the house" });

    const edited = (await db.vocabulary.get(row.id!))!;
    expect(edited.englishTranslation).toBe("the house");
    expect(edited.lastStudiedAt).toBe(studiedAt);
    // The schedule travels with the same identity, as it did before.
    expect(edited.reviewCount).toBe(4);
    expect(edited.interval).toBe(9);
  });

  it("keeps the caller's lastStudiedAt when the row being edited has gone", async () => {
    // The edit sheet writes back an entry it read earlier; another tab (or a
    // completed erase-then-restore) can remove that row in between. The schedule the
    // caller passes is kept in that case, so the study timestamp must be kept too.
    await saveVocabulary(db, { germanText: "Weg", englishTranslation: "way" });
    const row = (await findByGermanText(db, "Weg"))!;
    const studiedAt = 1_700_000_000_000;
    await db.vocabulary.update(row.id!, { lastStudiedAt: studiedAt, reviewCount: 2, interval: 6 });
    const beforeEdit = (await findByGermanText(db, "Weg"))!;
    await db.vocabulary.delete(row.id!);

    await saveVocabulary(db, { ...beforeEdit, englishTranslation: "the way" });

    const recreated = (await findByGermanText(db, "Weg"))!;
    expect(recreated.englishTranslation).toBe("the way");
    expect(recreated.lastStudiedAt).toBe(studiedAt);
    expect(recreated.reviewCount).toBe(2);
  });

  it("editing a reviewed card to collide with another card remaps review events to surviving ID", async () => {
    // Card 1: Katze (id 1), reviewed 5 times
    await saveVocabulary(db, { germanText: "Katze", englishTranslation: "cat" });
    const katze = (await findByGermanText(db, "Katze"))!;
    for (let i = 0; i < 5; i++) {
      await db.reviewEvents.add({
        vocabularyId: katze.id!,
        rating: "GOOD",
        scheduledDays: i * 2,
        actualDays: i * 2,
        reviewedAtTimestamp: 1000 + i * 100,
        isExtraPractice: false,
        remoteId: `review-katze-${i}`,
      });
    }

    // Card 2: Hund (id 2), reviewed 2 times
    await saveVocabulary(db, { germanText: "Hund", englishTranslation: "dog" });
    const hund = (await findByGermanText(db, "Hund"))!;
    for (let i = 0; i < 2; i++) {
      await db.reviewEvents.add({
        vocabularyId: hund.id!,
        rating: "EASY",
        scheduledDays: i * 3,
        actualDays: i * 3,
        reviewedAtTimestamp: 2000 + i * 100,
        isExtraPractice: false,
        remoteId: `review-hund-${i}`,
      });
    }

    // Update Katze's schedule to 5 reviews, interval 15
    await db.vocabulary.update(katze.id!, {
      reviewCount: 5,
      interval: 15,
      nextReview: 50_000,
      easeFactor: 2.6,
    });

    // Rename Katze -> Hund (collision with Hund)
    await saveVocabulary(db, {
      id: katze.id,
      germanText: "Hund",
      englishTranslation: "feline & canine",
    });

    // Exactly 1 vocabulary card survives (Hund, id 2)
    expect(await db.vocabulary.count()).toBe(1);
    const survivor = (await db.vocabulary.toArray())[0];
    expect(survivor.id).toBe(hund.id);
    expect(survivor.germanText).toBe("Hund");

    // Furthest schedule wins: Katze had reviewCount 5 > Hund's 0, so Katze's schedule survives!
    expect(survivor.reviewCount).toBe(5);
    expect(survivor.interval).toBe(15);
    expect(survivor.easeFactor).toBe(2.6);

    // All 7 review events now reference the surviving vocabulary ID! Zero orphans!
    const allEvents = await db.reviewEvents.toArray();
    expect(allEvents).toHaveLength(7);
    for (const event of allEvents) {
      expect(event.vocabularyId).toBe(survivor.id);
    }
  });

  it("deduplicates review events with identical remoteId during collision merge", async () => {
    await saveVocabulary(db, { germanText: "Baum", englishTranslation: "tree" });
    const baum = (await findByGermanText(db, "Baum"))!;
    await saveVocabulary(db, { germanText: "Wald", englishTranslation: "forest" });
    const wald = (await findByGermanText(db, "Wald"))!;

    // Both cards have a review event with the same remoteId (e.g. from prior cross-sync)
    await db.reviewEvents.add({
      vocabularyId: baum.id!,
      rating: "GOOD",
      scheduledDays: 1,
      actualDays: 1,
      reviewedAtTimestamp: 1000,
      isExtraPractice: false,
      remoteId: "shared-remote-event-1",
    });
    await db.reviewEvents.add({
      vocabularyId: wald.id!,
      rating: "GOOD",
      scheduledDays: 1,
      actualDays: 1,
      reviewedAtTimestamp: 1000,
      isExtraPractice: false,
      remoteId: "shared-remote-event-1",
    });

    // Rename Wald -> Baum
    await saveVocabulary(db, {
      id: wald.id,
      germanText: "Baum",
      englishTranslation: "woods",
    });

    // Deduplication preserves exactly 1 copy of the shared event
    const events = await db.reviewEvents.toArray();
    expect(events).toHaveLength(1);
    expect(events[0].vocabularyId).toBe(baum.id);
  });

  it("merging an unreviewed card into an unreviewed card keeps NEW_CARD_SCHEDULE", async () => {
    await saveVocabulary(db, { germanText: "Apfel", englishTranslation: "apple" });
    const apfel = (await findByGermanText(db, "Apfel"))!;
    await saveVocabulary(db, { germanText: "Birne", englishTranslation: "pear" });
    const birne = (await findByGermanText(db, "Birne"))!;

    await saveVocabulary(db, {
      id: birne.id,
      germanText: "Apfel",
      englishTranslation: "fruit",
    });

    const survivor = (await db.vocabulary.toArray())[0];
    expect(survivor.id).toBe(apfel.id);
    expect(survivor.reviewCount).toBe(0);
    expect(survivor.interval).toBe(0);
    expect(survivor.easeFactor).toBe(2.5);
  });

  it("preserves twenty historical review events on collision merge", async () => {
    await saveVocabulary(db, { germanText: "Sonne", englishTranslation: "sun" });
    const sonne = (await findByGermanText(db, "Sonne"))!;
    await saveVocabulary(db, { germanText: "Mond", englishTranslation: "moon" });
    const mond = (await findByGermanText(db, "Mond"))!;

    for (let i = 0; i < 20; i++) {
      await db.reviewEvents.add({
        vocabularyId: mond.id!,
        rating: "GOOD",
        scheduledDays: i,
        actualDays: i,
        reviewedAtTimestamp: 1000 + i * 10,
        isExtraPractice: false,
        remoteId: `mond-event-${i}`,
      });
    }

    await saveVocabulary(db, {
      id: mond.id,
      germanText: "Sonne",
      englishTranslation: "celestial body",
    });

    const events = await db.reviewEvents.toArray();
    expect(events).toHaveLength(20);
    for (const e of events) {
      expect(e.vocabularyId).toBe(sonne.id);
    }
  });

  it("keeps Maße and Masse as distinct vocabulary entries", async () => {
    await saveVocabulary(db, { germanText: "Maße", englishTranslation: "dimensions" });
    await saveVocabulary(db, { germanText: "Masse", englishTranslation: "mass" });

    expect(await db.vocabulary.count()).toBe(2);
    const masse = await findByGermanText(db, "Masse");
    const masseEszett = await findByGermanText(db, "Maße");
    expect(masse).toBeDefined();
    expect(masseEszett).toBeDefined();
    expect(masse?.id).not.toBe(masseEszett?.id);
  });

  it("rolls back all changes atomically if an operation fails during merge", async () => {
    await saveVocabulary(db, { germanText: "Wasser", englishTranslation: "water" });
    const wasser = (await findByGermanText(db, "Wasser"))!;
    await saveVocabulary(db, { germanText: "Feuer", englishTranslation: "fire" });
    const feuer = (await findByGermanText(db, "Feuer"))!;

    await db.reviewEvents.add({
      vocabularyId: feuer.id!,
      rating: "GOOD",
      scheduledDays: 1,
      actualDays: 1,
      reviewedAtTimestamp: 1000,
      isExtraPractice: false,
      remoteId: "feuer-event-1",
    });

    // Mock db.vocabulary.delete to simulate an unexpected storage error midway
    const originalDelete = db.vocabulary.delete.bind(db.vocabulary);
    const deleteSpy = vi.spyOn(db.vocabulary, "delete").mockImplementation((key: number) => {
      if (key === feuer.id) {
        return Promise.reject(new Error("Disk write failed")) as unknown as PromiseExtended<void>;
      }
      return originalDelete(key);
    });

    await expect(
      saveVocabulary(db, {
        id: feuer.id,
        germanText: "Wasser",
        englishTranslation: "element",
      })
    ).rejects.toThrow("Disk write failed");

    deleteSpy.mockRestore();

    // Verify atomic rollback: both cards still exist and review event still points to feuer!
    expect(await db.vocabulary.count()).toBe(2);
    const feuerAfter = await findByGermanText(db, "Feuer");
    const wasserAfter = await findByGermanText(db, "Wasser");
    expect(feuerAfter).toBeDefined();
    expect(wasserAfter).toBeDefined();
    expect(wasserAfter?.id).toBe(wasser.id);

    const events = await db.reviewEvents.toArray();
    expect(events).toHaveLength(1);
    expect(events[0].vocabularyId).toBe(feuer.id);
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

describe("withFurthestScheduleOf — pedagogical SRS merge policy", () => {
  const baseEntry: VocabularyEntry = {
    id: 1,
    germanText: "Hund",
    germanTextKey: "hund",
    englishTranslation: "dog",
    timestamp: 1000,
    exampleSentence: "Der Hund bellt.",
    article: "der",
    plural: "Hunde",
    conjugation: "",
    synonyms: "",
    antonyms: "",
    remoteId: "00000000-0000-4000-8000-000000000001",
    nextReview: 0,
    interval: 0,
    easeFactor: 2.5,
    reviewCount: 0,
    lastModifiedAt: 1000,
  };

  it("preserves a recent lapse over an older mastered schedule", () => {
    // Survivor had a mature schedule from 3 days ago (T1 = 1000)
    const matureSurvivor = {
      ...baseEntry,
      reviewCount: 5,
      interval: 30,
      nextReview: 200_000,
      easeFactor: 2.6,
      lastModifiedAt: 1000,
      lastStudiedAt: 1000,
    };
    // Learner failed the card today (T2 = 5000, quality AGAIN -> interval 0, nextReview 0, ease penalty)
    const recentLapse = {
      ...baseEntry,
      reviewCount: 0,
      interval: 0,
      nextReview: 0,
      easeFactor: 2.4,
      lastModifiedAt: 5000,
      lastStudiedAt: 5000,
    };

    const merged = withFurthestScheduleOf(matureSurvivor, recentLapse, 5000);
    // The lapse MUST win: the learner forgot the word and must review it now!
    expect(merged.reviewCount).toBe(0);
    expect(merged.interval).toBe(0);
    expect(merged.nextReview).toBe(0);
    expect(merged.easeFactor).toBe(2.4);
  });

  it("preserves a recent remastering over an older lapse", () => {
    // Survivor was a lapsed card from yesterday (T1 = 1000)
    const lapsedSurvivor = {
      ...baseEntry,
      reviewCount: 0,
      interval: 0,
      nextReview: 0,
      easeFactor: 2.3,
      lastModifiedAt: 1000,
      lastStudiedAt: 1000,
    };
    // Learner restudied and mastered the card today (T2 = 5000)
    const remastered = {
      ...baseEntry,
      reviewCount: 4,
      interval: 15,
      nextReview: 300_000,
      easeFactor: 2.5,
      lastModifiedAt: 5000,
      lastStudiedAt: 5000,
    };

    const merged = withFurthestScheduleOf(lapsedSurvivor, remastered, 5000);
    expect(merged.reviewCount).toBe(4);
    expect(merged.interval).toBe(15);
    expect(merged.nextReview).toBe(300_000);
    expect(merged.easeFactor).toBe(2.5);
  });

  it("preserves recent lapse even when content was edited after the lapse", () => {
    // Survivor was mastered at T=1000, but edited for typos at T=9000
    const matureSurvivorEdited = {
      ...baseEntry,
      reviewCount: 5,
      interval: 30,
      nextReview: 200_000,
      easeFactor: 2.6,
      lastModifiedAt: 9000,
      lastStudiedAt: 1000,
    };
    // Card lapsed at T=2000
    const recentLapse = {
      ...baseEntry,
      reviewCount: 0,
      interval: 0,
      nextReview: 0,
      easeFactor: 2.4,
      lastModifiedAt: 2000,
      lastStudiedAt: 2000,
    };

    // Study timestamps (1000 vs 2000) determine that recentLapse is the newer learning event,
    // ignoring lastModifiedAt (9000) from typographical edits.
    const merged = withFurthestScheduleOf(matureSurvivorEdited, recentLapse, 5000);
    expect(merged.reviewCount).toBe(0);
    expect(merged.interval).toBe(0);
    expect(merged.nextReview).toBe(0);
    expect(merged.easeFactor).toBe(2.4);
  });

  it("favors due/lapsed card when timestamps are tied to avoid burying review", () => {
    const futureCard = {
      ...baseEntry,
      reviewCount: 3,
      interval: 20,
      nextReview: 150_000,
      easeFactor: 2.5,
      lastModifiedAt: 2000,
    };
    const dueCard = {
      ...baseEntry,
      reviewCount: 0,
      interval: 0,
      nextReview: 0,
      easeFactor: 2.3,
      lastModifiedAt: 2000,
    };

    const merged = withFurthestScheduleOf(futureCard, dueCard, 50_000);
    expect(merged.interval).toBe(0);
    expect(merged.nextReview).toBe(0);
    expect(merged.easeFactor).toBe(2.3);
  });

  it("favors sooner review when both future schedules have tied timestamps", () => {
    const furtherCard = {
      ...baseEntry,
      reviewCount: 4,
      interval: 25,
      nextReview: 250_000,
      easeFactor: 2.6,
      lastModifiedAt: 1000,
    };
    const soonerCard = {
      ...baseEntry,
      reviewCount: 3,
      interval: 10,
      nextReview: 100_000,
      easeFactor: 2.5,
      lastModifiedAt: 1000,
    };

    const merged = withFurthestScheduleOf(furtherCard, soonerCard, 50_000);
    expect(merged.nextReview).toBe(100_000);
    expect(merged.interval).toBe(10);
  });

  it("ensures unreviewed content edits never wipe out existing study progress", () => {
    const studiedSurvivor = {
      ...baseEntry,
      reviewCount: 5,
      interval: 30,
      nextReview: 200_000,
      easeFactor: 2.6,
      lastModifiedAt: 1000,
    };
    const unreviewedEdit = {
      ...baseEntry,
      reviewCount: 0,
      interval: 0,
      nextReview: 0,
      easeFactor: 2.5,
      lastModifiedAt: 9000, // freshly edited timestamp
    };

    const merged = withFurthestScheduleOf(studiedSurvivor, unreviewedEdit, 9000);
    expect(merged.reviewCount).toBe(5);
    expect(merged.interval).toBe(30);
    expect(merged.nextReview).toBe(200_000);
  });

  it("preserves a recent lapse when incoming card had a content edit after the lapse", () => {
    // Survivor had a lapse at T=5000
    const lapsedSurvivor = {
      ...baseEntry,
      reviewCount: 0,
      interval: 0,
      nextReview: 0,
      easeFactor: 2.4,
      lastModifiedAt: 5000,
      lastStudiedAt: 5000,
    };
    // Other was mastered earlier at T=1000, but content (typo) was edited at T=9000
    const editedMastered = {
      ...baseEntry,
      reviewCount: 5,
      interval: 30,
      nextReview: 200_000,
      easeFactor: 2.6,
      lastModifiedAt: 9000, // Typo fixed at 9000
      lastStudiedAt: 1000,  // But last studied at 1000
    };

    const merged = withFurthestScheduleOf(lapsedSurvivor, editedMastered, 9000);
    // The lapse at 5000 is more recent than study at 1000; content edit at 9000 must NOT erase it
    expect(merged.reviewCount).toBe(0);
    expect(merged.interval).toBe(0);
    expect(merged.nextReview).toBe(0);
    expect(merged.easeFactor).toBe(2.4);
  });

  it("preserves a recent lapse even without study timestamps when content was edited later", () => {
    const lapsedSurvivor = {
      ...baseEntry,
      reviewCount: 0,
      interval: 0,
      nextReview: 0,
      easeFactor: 2.4,
      lastModifiedAt: 5000,
    };
    const editedMastered = {
      ...baseEntry,
      reviewCount: 5,
      interval: 30,
      nextReview: 200_000,
      easeFactor: 2.6,
      lastModifiedAt: 9000,
    };

    const merged = withFurthestScheduleOf(lapsedSurvivor, editedMastered, 9000);
    expect(merged.reviewCount).toBe(0);
    expect(merged.interval).toBe(0);
    expect(merged.nextReview).toBe(0);
  });
});
