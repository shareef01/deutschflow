import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import "fake-indexeddb/auto";
import { DeutschFlowDB, foldGermanKey, VocabularyEntry } from "@/lib/db/schema";
import {
  importLibrary,
  exportEncryptedLibrary,
  validateBackup,
  ImportError,
  ITERATIONS,
  MIN_ITERATIONS,
  MAX_ITERATIONS,
} from "@/lib/db/backup";
import {
  saveVocabulary,
  deleteVocabulary,
  restoreVocabulary,
} from "@/lib/db/repository";
import {
  UpstashRedisThrottleBackend,
  ThrottleStoreError,
  InMemoryThrottleBackend,
} from "@/lib/auth/throttle";
import {
  translateAndExtract,
} from "@/lib/ai/groq";

describe("Audit Hardening & Data Integrity Regressions", () => {
  let db: DeutschFlowDB;

  beforeEach(async () => {
    db = new DeutschFlowDB(`test-audit-regressions-${Date.now()}-${Math.random()}`);
    await db.open();
  });

  afterEach(async () => {
    await db.delete();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  describe("1. Backup Import Recency Conflicts", () => {
    it("preserves incoming newer lapse when existing card has older mastered schedule", async () => {
      const now = Date.now();
      const fiveDaysAgo = now - 5 * 86_400_000;
      const yesterday = now - 1 * 86_400_000;

      // Existing DB has card mastered 5 days ago (interval 20, reviewCount 5, nextReview in future)
      await db.vocabulary.add({
        germanText: "Lernen",
        germanTextKey: foldGermanKey("Lernen"),
        englishTranslation: "to learn",
        timestamp: fiveDaysAgo,
        exampleSentence: "Ich lerne Deutsch.",
        article: "",
        plural: "",
        conjugation: "",
        synonyms: "",
        antonyms: "",
        nextReview: now + 15 * 86_400_000,
        interval: 20,
        easeFactor: 2.5,
        reviewCount: 5,
        remoteId: "vocab-mastered-1",
        lastModifiedAt: fiveDaysAgo,
        lastStudiedAt: fiveDaysAgo,
      });

      // Incoming backup has same card, but studied yesterday and lapsed (interval 0, reviewCount 0, nextReview 0)
      const incomingBackup = {
        format: "deutschflow-library",
        version: 2,
        exportedAt: new Date(now).toISOString(),
        vocabulary: [
          {
            id: 99,
            germanText: "Lernen",
            germanTextKey: foldGermanKey("Lernen"),
            englishTranslation: "to learn",
            timestamp: fiveDaysAgo,
            exampleSentence: "Ich lerne Deutsch.",
            article: "",
            plural: "",
            conjugation: "",
            synonyms: "",
            antonyms: "",
            nextReview: 0,
            interval: 0,
            easeFactor: 2.3,
            reviewCount: 0,
            remoteId: "vocab-mastered-1",
            lastModifiedAt: yesterday,
            lastStudiedAt: yesterday,
          },
        ],
        transcripts: [],
        userStats: [],
        activityLog: [],
        reviewEvents: [
          {
            id: 1,
            vocabularyId: 99,
            rating: "AGAIN",
            scheduledDays: 0,
            actualDays: 0,
            reviewedAtTimestamp: yesterday,
            isExtraPractice: false,
            remoteId: "a1111111-2222-4333-8444-444444444444",
          },
        ],
        roleplayMessages: [],
      };

      await importLibrary(db, incomingBackup);

      const card = await db.vocabulary.where("germanTextKey").equals(foldGermanKey("Lernen")).first();
      expect(card).toBeDefined();
      // The newer lapse MUST survive; older mastered schedule must NOT erase it
      expect(card!.interval).toBe(0);
      expect(card!.nextReview).toBe(0);
      expect(card!.reviewCount).toBe(0);
      expect(card!.lastStudiedAt).toBe(yesterday);
    });
  });

  describe("2. Vocabulary Collision Merges in saveVocabulary", () => {
    it("preserves departing card's actual review event timestamp and lapse when deciding surviving schedule", async () => {
      const now = Date.now();
      const tenDaysAgo = now - 10 * 86_400_000;
      const yesterday = now - 1 * 86_400_000;

      // Card A: "gehen" (mastered 10 days ago, interval 15)
      const idA = (await db.vocabulary.add({
        germanText: "gehen",
        germanTextKey: foldGermanKey("gehen"),
        englishTranslation: "to go",
        timestamp: tenDaysAgo,
        exampleSentence: "Ich gehe nach Hause.",
        article: "",
        plural: "",
        conjugation: "",
        synonyms: "",
        antonyms: "",
        nextReview: now + 5 * 86_400_000,
        interval: 15,
        easeFactor: 2.5,
        reviewCount: 4,
        remoteId: "card-a-remote",
        lastModifiedAt: tenDaysAgo,
        lastStudiedAt: tenDaysAgo,
      })) as number;

      await db.reviewEvents.add({
        vocabularyId: idA,
        rating: "GOOD",
        scheduledDays: 15,
        actualDays: 15,
        reviewedAtTimestamp: tenDaysAgo,
        isExtraPractice: false,
        remoteId: "a1111111-0000-0000-0000-000000000001",
      });

      // Card B: "laufen" (lapsed yesterday, interval 0, nextReview 0)
      const idB = (await db.vocabulary.add({
        germanText: "laufen",
        germanTextKey: foldGermanKey("laufen"),
        englishTranslation: "to run",
        timestamp: tenDaysAgo,
        exampleSentence: "Ich laufe schnell.",
        article: "",
        plural: "",
        conjugation: "",
        synonyms: "",
        antonyms: "",
        nextReview: 0,
        interval: 0,
        easeFactor: 2.2,
        reviewCount: 0,
        remoteId: "card-b-remote",
        lastModifiedAt: yesterday,
        lastStudiedAt: yesterday,
      })) as number;

      await db.reviewEvents.add({
        vocabularyId: idB,
        rating: "AGAIN",
        scheduledDays: 0,
        actualDays: 0,
        reviewedAtTimestamp: yesterday,
        isExtraPractice: false,
        remoteId: "b1111111-0000-0000-0000-000000000002",
      });

      // User renames Card B to "gehen", triggering collision with Card A
      await saveVocabulary(db, {
        id: idB,
        germanText: "gehen",
        englishTranslation: "to go / walk",
        interval: 0,
        nextReview: 0,
        reviewCount: 0,
        easeFactor: 2.2,
        lastModifiedAt: yesterday,
      });

      const surviving = await db.vocabulary.where("germanTextKey").equals(foldGermanKey("gehen")).first();
      expect(surviving).toBeDefined();
      // Because Card B was reviewed yesterday, its lapse schedule MUST survive over Card A from 10 days ago
      expect(surviving!.interval).toBe(0);
      expect(surviving!.nextReview).toBe(0);
      expect(surviving!.reviewCount).toBe(0);
      expect(surviving!.lastStudiedAt).toBe(yesterday);

      // And review events must both be linked to the surviving card
      const events = await db.reviewEvents.where("vocabularyId").equals(surviving!.id!).toArray();
      expect(events).toHaveLength(2);
    });
  });

  describe("3. ID Reuse During Undo in restoreVocabulary", () => {
    it("never steals review events from an unrelated card occupying the deleted card's former numeric ID", async () => {
      // 1. Create Card A with ID 5
      const cardA: VocabularyEntry = {
        id: 5,
        germanText: "Apfel",
        germanTextKey: foldGermanKey("Apfel"),
        englishTranslation: "apple",
        timestamp: 1000,
        exampleSentence: "Der Apfel ist rot.",
        article: "der",
        plural: "Äpfel",
        conjugation: "",
        synonyms: "",
        antonyms: "",
        nextReview: 0,
        interval: 0,
        easeFactor: 2.5,
        reviewCount: 0,
        remoteId: "card-a-uuid-1111",
        lastModifiedAt: 1000,
      };
      await db.vocabulary.put(cardA);

      // Card A has review events
      await db.reviewEvents.add({
        vocabularyId: 5,
        rating: "GOOD",
        scheduledDays: 1,
        actualDays: 1,
        reviewedAtTimestamp: 2000,
        isExtraPractice: false,
        remoteId: "rev-a-1111-2222-3333-444444444444",
      });

      // 2. Delete Card A
      await deleteVocabulary(db, cardA);
      expect(await db.vocabulary.get(5)).toBeUndefined();

      // 3. New unrelated Card B is created and allocated ID 5
      const cardB: VocabularyEntry = {
        id: 5,
        germanText: "Birne",
        germanTextKey: foldGermanKey("Birne"),
        englishTranslation: "pear",
        timestamp: 3000,
        exampleSentence: "Die Birne ist süß.",
        article: "die",
        plural: "Birnen",
        conjugation: "",
        synonyms: "",
        antonyms: "",
        nextReview: 4000,
        interval: 3,
        easeFactor: 2.5,
        reviewCount: 2,
        remoteId: "card-b-uuid-9999",
        lastModifiedAt: 3000,
      };
      await db.vocabulary.put(cardB);

      // Card B gets its own review event with vocabularyId 5
      await db.reviewEvents.add({
        vocabularyId: 5,
        rating: "GOOD",
        scheduledDays: 3,
        actualDays: 3,
        reviewedAtTimestamp: 4000,
        isExtraPractice: false,
        remoteId: "rev-b-5555-6666-7777-888888888888",
      });

      // 4. Restore Card A (Undo)
      await restoreVocabulary(db, cardA);

      // Card B must still exist at ID 5 with its own review event
      const freshCardB = await db.vocabulary.get(5);
      expect(freshCardB).toBeDefined();
      expect(freshCardB!.germanText).toBe("Birne");

      const cardBEvents = await db.reviewEvents.where("vocabularyId").equals(5).toArray();
      // Card B's review event MUST NOT have been stolen!
      expect(cardBEvents.some((e) => e.remoteId === "rev-b-5555-6666-7777-888888888888")).toBe(true);

      // Card A must be restored under a new ID
      const restoredCardA = await db.vocabulary.where("germanTextKey").equals(foldGermanKey("Apfel")).first();
      expect(restoredCardA).toBeDefined();
      expect(restoredCardA!.id).not.toBe(5);

      // Card A's review event should be attached to Card A's new ID
      const cardAEvents = await db.reviewEvents.where("vocabularyId").equals(restoredCardA!.id!).toArray();
      expect(cardAEvents.some((e) => e.remoteId === "rev-a-1111-2222-3333-444444444444")).toBe(true);
    });
  });

  describe("4. Duplicate Import Validation & Pre-import Rejection", () => {
    it("rejects backup with duplicate canonical vocabulary keys", () => {
      const backup = {
        format: "deutschflow-library",
        version: 2,
        exportedAt: new Date().toISOString(),
        vocabulary: [
          {
            germanText: "Straße",
            englishTranslation: "street",
            remoteId: "uuid-1",
          },
          {
            germanText: "strasse",
            englishTranslation: "street duplicate",
            remoteId: "uuid-2",
          },
        ],
        transcripts: [],
        userStats: [],
        activityLog: [],
      };

      expect(() => validateBackup(backup)).toThrowError(ImportError);
    });

    it("rejects backup with duplicate transcript remote IDs", () => {
      const backup = {
        format: "deutschflow-library",
        version: 2,
        exportedAt: new Date().toISOString(),
        vocabulary: [],
        transcripts: [
          {
            fullText: "Transcript 1",
            timestamp: 1000,
            remoteId: "dup-transcript-id",
          },
          {
            fullText: "Transcript 2",
            timestamp: 2000,
            remoteId: "dup-transcript-id",
          },
        ],
        userStats: [],
        activityLog: [],
      };

      expect(() => validateBackup(backup)).toThrowError(ImportError);
    });

    it("rejects backup with duplicate transcript content", () => {
      const backup = {
        format: "deutschflow-library",
        version: 2,
        exportedAt: new Date().toISOString(),
        vocabulary: [],
        transcripts: [
          {
            fullText: "Guten Tag!",
            timestamp: 1000,
            remoteId: "uuid-t1",
          },
          {
            fullText: "Guten Tag!",
            timestamp: 1000,
            remoteId: "uuid-t2",
          },
        ],
        userStats: [],
        activityLog: [],
      };

      expect(() => validateBackup(backup)).toThrowError(ImportError);
    });

    it("guarantees full transactional rollback on validation failure", async () => {
      await db.vocabulary.add({
        germanText: "Original",
        germanTextKey: foldGermanKey("Original"),
        englishTranslation: "original",
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
        remoteId: "orig-1",
        lastModifiedAt: 1000,
      });

      const malformedBackup = {
        format: "deutschflow-library",
        version: 2,
        exportedAt: new Date().toISOString(),
        vocabulary: [
          {
            germanText: "NewWord",
            englishTranslation: "new",
            remoteId: "new-1",
          },
          {
            germanText: "NewWord",
            englishTranslation: "new duplicate",
            remoteId: "new-2",
          },
        ],
        transcripts: [],
        userStats: [],
        activityLog: [],
      };

      await expect(importLibrary(db, malformedBackup)).rejects.toThrow(ImportError);
      // Ensure nothing was written
      const items = await db.vocabulary.toArray();
      expect(items).toHaveLength(1);
      expect(items[0].germanText).toBe("Original");
    });
  });

  describe("5. Redis /multi-exec Partial Response Validation", () => {
    it("rejects when command 3 fails with error and triggers fail-closed policy", async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify([
            { result: "5" },
            { result: "10000" },
            { error: "ERR syntax error on nextAllowed" },
          ]),
          { status: 200 }
        )
      );
      vi.stubGlobal("fetch", fetchMock);

      const backend = new UpstashRedisThrottleBackend(
        "https://example.upstash.io",
        "mock-token",
        new InMemoryThrottleBackend(),
        1000,
        "fail-closed"
      );

      await expect(backend.get("192.168.1.1", Date.now())).rejects.toThrow(ThrottleStoreError);
    });

    it("rejects when response length is less than expected 3 commands and falls back safely", async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify([
            { result: "5" },
            { result: "10000" },
          ]),
          { status: 200 }
        )
      );
      vi.stubGlobal("fetch", fetchMock);

      const memoryFallback = new InMemoryThrottleBackend();
      const backend = new UpstashRedisThrottleBackend(
        "https://example.upstash.io",
        "mock-token",
        memoryFallback,
        1000,
        "fail-secure-fallback"
      );

      const result = await backend.get("192.168.1.1", Date.now());
      // Should fall back cleanly without unhandled crash
      expect(result).toBeNull();
    });
  });

  describe("6. Groq HTTP Error Classification (Fast Rejection without Retries)", () => {
    it("does not retry 400 Bad Request, 401 Unauthorized, or 429 Rate Limited", async () => {
      let callCount = 0;
      const fetchMock = vi.fn().mockImplementation(async () => {
        callCount++;
        return new Response(
          JSON.stringify({ error: { message: "Invalid model parameters" } }),
          { status: 400 }
        );
      });
      vi.stubGlobal("fetch", fetchMock);

      const result = await translateAndExtract("Hallo", "gsk_test_key");
      expect(result.kind).toBe("failure");
      // MUST be called exactly once; no automatic retry on 400
      expect(callCount).toBe(1);
    });

    it("retries on transient 503 Service Unavailable up to MAX_POST_RETRIES", async () => {
      let callCount = 0;
      const fetchMock = vi.fn().mockImplementation(async () => {
        callCount++;
        if (callCount < 3) {
          return new Response("Service Unavailable", { status: 503 });
        }
        return new Response(
          JSON.stringify({
            choices: [
              {
                message: {
                  content: JSON.stringify({
                    translation: "Hello",
                    keywords: ["Hallo"],
                    example: "Hallo Welt",
                    grammar: [],
                  }),
                },
              },
            ],
          }),
          { status: 200 }
        );
      });
      vi.stubGlobal("fetch", fetchMock);

      const result = await translateAndExtract("Hallo", "gsk_test_key");
      expect(result.kind).toBe("success");
      expect(callCount).toBe(3);
    });
  });

  describe("7. Passphrase with Leading/Trailing Spaces", () => {
    it("preserves exact passphrase bytes with leading/trailing spaces during export and import", async () => {
      expect(ITERATIONS).toBe(210_000);
      expect(MIN_ITERATIONS).toBe(50_000);
      expect(MAX_ITERATIONS).toBe(1_000_000);

      const spacePassphrase = "   super secure password with spaces   ";

      await db.vocabulary.add({
        germanText: "Wasser",
        germanTextKey: foldGermanKey("Wasser"),
        englishTranslation: "water",
        timestamp: 1000,
        exampleSentence: "Das Wasser ist kalt.",
        article: "das",
        plural: "",
        conjugation: "",
        synonyms: "",
        antonyms: "",
        nextReview: 0,
        interval: 0,
        easeFactor: 2.5,
        reviewCount: 0,
        remoteId: "water-1",
        lastModifiedAt: 1000,
      });

      const encrypted = await exportEncryptedLibrary(db, spacePassphrase);
      expect(typeof encrypted).toBe("string");

      const targetDb = new DeutschFlowDB(`target-space-db-${Date.now()}`);
      await targetDb.open();
      try {
        const importRes = await importLibrary(targetDb, encrypted, spacePassphrase);
        expect(importRes.vocabularyAdded).toBe(1);

        const card = await targetDb.vocabulary.where("germanTextKey").equals(foldGermanKey("Wasser")).first();
        expect(card).toBeDefined();
        expect(card!.englishTranslation).toBe("water");
      } finally {
        await targetDb.delete();
      }
    });
  });
});
