import Dexie from "dexie";
import { beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import {
  clearAllProgress,
  clearConversation,
  loadConversation,
  saveConversationTurn,
} from "@/lib/db/repository";

/**
 * The roleplay chat used to live in React state alone: a reload lost it, on the
 * one screen where the user stops to compose a German sentence, and where what
 * was lost was the model's half of the conversation rather than anything they
 * could retype. These check the storage that fixes it, including the two ways it
 * is allowed to disappear — a new scenario, and "clear all progress".
 */

let counter = 0;

beforeEach(() => {
  counter++;
});

function freshDb(): DeutschFlowDB {
  return new DeutschFlowDB(`roleplay-${counter}-${Math.random()}`);
}

const OPENING = {
  position: 0,
  scenario: "Ordering at a Berlin Bakery",
  role: "assistant" as const,
  content: "Guten Morgen! Was darf es sein?",
  translation: "Good morning! What would you like?",
  timestamp: 1000,
};

const REPLY = {
  position: 1,
  scenario: "Ordering at a Berlin Bakery",
  role: "user" as const,
  content: "Ein Brötchen, bitte.",
  timestamp: 2000,
};

describe("roleplay conversation storage", () => {
  it("reads the conversation back oldest turn first", async () => {
    const db = freshDb();
    // Written out of order on purpose: the chat renders by position, not by
    // insertion, and a restored conversation has to come back the same way.
    await saveConversationTurn(db, REPLY);
    await saveConversationTurn(db, OPENING);

    const saved = await loadConversation(db);
    expect(saved.map((m) => m.position)).toEqual([0, 1]);
    expect(saved[0].content).toBe("Guten Morgen! Was darf es sein?");
    expect(saved[1].role).toBe("user");
    db.close();
  });

  it("keeps a user turn's absent translation absent", async () => {
    // The gloss belongs to the model's turns. A user turn that came back with an
    // empty string instead of nothing would render an empty caption under it.
    const db = freshDb();
    await saveConversationTurn(db, REPLY);
    expect((await loadConversation(db))[0].translation).toBeUndefined();
    db.close();
  });

  it("replaces the turn at a position rather than duplicating it", async () => {
    // What a retry does: the same user turn is sent again at the same index.
    const db = freshDb();
    await saveConversationTurn(db, REPLY);
    await saveConversationTurn(db, { ...REPLY, content: "Zwei Brötchen, bitte." });

    const saved = await loadConversation(db);
    expect(saved).toHaveLength(1);
    expect(saved[0].content).toBe("Zwei Brötchen, bitte.");
    db.close();
  });

  it("starts a new scenario from nothing", async () => {
    const db = freshDb();
    await saveConversationTurn(db, OPENING);
    await clearConversation(db);
    expect(await loadConversation(db)).toEqual([]);
    db.close();
  });

  it("returns an empty conversation rather than throwing on a closed database", async () => {
    // A chat that cannot be read is a chat you start fresh; it must not take the
    // screen down. The hook awaits this before deciding whether to open a scene.
    const db = freshDb();
    await db.open();
    db.close();
    await expect(loadConversation(db)).resolves.toEqual([]);
  });

  it("is wiped by clear-all-progress, which promises exactly that", async () => {
    const db = freshDb();
    await saveConversationTurn(db, OPENING);
    await db.vocabulary.put({
      germanText: "das Brötchen",
      germanTextKey: "das broetchen",
      englishTranslation: "the bread roll",
      timestamp: 1,
      exampleSentence: "",
      article: "das",
      plural: "",
      conjugation: "",
      nextReview: 0,
      interval: 0,
      easeFactor: 2.5,
      reviewCount: 0,
      synonyms: "",
      antonyms: "",
      remoteId: "r",
      lastModifiedAt: 1,
    });

    await clearAllProgress(db);

    expect(await loadConversation(db)).toEqual([]);
    expect(await db.vocabulary.count()).toBe(0);
    db.close();
  });
});

describe("the version 6 upgrade", () => {
  it("adds the table to an existing library without disturbing it", async () => {
    const name = `roleplay-upgrade-${counter}-${Math.random()}`;

    // A database as version 5 shipped: no roleplayMessages store at all.
    const legacy = new Dexie(name);
    legacy.version(5).stores({
      vocabulary: "++id, timestamp, &germanTextKey, nextReview",
      transcripts: "++id, timestamp",
      userStats: "id",
      activityLog: "date",
      settings: "key",
    });
    await legacy.open();
    await legacy.table("vocabulary").put({
      germanText: "die Übung",
      germanTextKey: "die uebung",
      englishTranslation: "the exercise",
      timestamp: 100,
      exampleSentence: "",
      article: "die",
      plural: "",
      conjugation: "",
      nextReview: 0,
      interval: 0,
      easeFactor: 2.5,
      reviewCount: 0,
      synonyms: "",
      antonyms: "",
      remoteId: "r",
      lastModifiedAt: 100,
    });
    legacy.close();

    const db = new DeutschFlowDB(name);
    await db.open();
    expect(db.verno).toBe(6);
    // Nothing is backfilled — the table starts empty either way. What matters is
    // that adding it leaves the library alone.
    expect(await loadConversation(db)).toEqual([]);
    const words = await db.vocabulary.toArray();
    expect(words).toHaveLength(1);
    expect(words[0].germanText).toBe("die Übung");

    await saveConversationTurn(db, OPENING);
    expect(await loadConversation(db)).toHaveLength(1);
    db.close();
  });
});
