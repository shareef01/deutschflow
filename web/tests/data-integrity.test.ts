import { afterEach, expect, it, vi } from "vitest";
import { DeutschFlowDB } from "@/lib/db/schema";
import { exportLibrary, importLibrary, MAX_BACKUP_FILE_BYTES } from "@/lib/db/backup";
import { clearAllProgress, deleteTranscript, insertTranscript, restoreTranscript,
  saveVocabulary, updateTranscriptAnalysis } from "@/lib/db/repository";
import { recordReview, StaleReviewError } from "@/lib/db/review";
import { ReviewQuality } from "@/lib/ai/srs";

const databases: DeutschFlowDB[] = [];
function fresh(name = `integrity-${crypto.randomUUID()}`) {
  const db = new DeutschFlowDB(name);
  databases.push(db);
  return db;
}
async function seed(db: DeutschFlowDB, germanText = "Haus") {
  await saveVocabulary(db, { germanText, englishTranslation: "house" });
  return (await db.vocabulary.toArray())[0];
}
afterEach(async () => {
  vi.restoreAllMocks();
  for (const db of databases) db.close();
  for (const db of databases.splice(0)) await db.delete();
});

it("clear progress removes every learning table and preserves settings", async () => {
  const db = fresh();
  const card = await seed(db);
  await recordReview(db, card, ReviewQuality.GOOD, false);
  await insertTranscript(db, "Hallo");
  await db.roleplayMessages.put({ position: 0, scenario: "Bakery", role: "user", content: "Hallo", timestamp: Date.now() });
  await db.settings.put({ key: "language", value: "de" });
  await clearAllProgress(db);
  for (const table of [db.vocabulary, db.transcripts, db.reviewEvents, db.userStats, db.activityLog, db.roleplayMessages]) {
    expect(await table.count(), table.name).toBe(0);
  }
  expect(await db.settings.count()).toBe(1);
});

it("rejects a review after deletion without resurrecting data or granting XP", async () => {
  const db = fresh(), card = await seed(db);
  await clearAllProgress(db);
  await expect(recordReview(db, card, ReviewQuality.GOOD, false)).rejects.toBeInstanceOf(StaleReviewError);
  expect(await db.vocabulary.count()).toBe(0);
  expect(await db.reviewEvents.count()).toBe(0);
  expect(await db.userStats.count()).toBe(0);
});

it("rejects a review when another tab edits the same card", async () => {
  const db = fresh(), card = await seed(db);
  await db.vocabulary.update(card.id!, { englishTranslation: "home" });
  await expect(recordReview(db, card, ReviewQuality.GOOD, false)).rejects.toBeInstanceOf(StaleReviewError);
  expect((await db.vocabulary.get(card.id!))?.englishTranslation).toBe("home");
  expect(await db.reviewEvents.count()).toBe(0);
});

it("only one of two concurrent tab reviews commits, even in the same millisecond", async () => {
  const db = fresh(), card = await seed(db), otherTab = fresh(db.name);
  vi.spyOn(Date, "now").mockReturnValue(card.lastModifiedAt);
  const results = await Promise.allSettled([
    recordReview(db, card, ReviewQuality.GOOD, false),
    recordReview(otherTab, card, ReviewQuality.GOOD, false),
  ]);
  expect(results.filter((result) => result.status === "fulfilled")).toHaveLength(1);
  expect(await db.reviewEvents.count()).toBe(1);
  expect((await db.userStats.get(1))?.xp).toBe(10);
});

it("measures actualDays since the latest review, including after metadata edits", async () => {
  const db = fresh(), card = await seed(db);
  const start = Date.now();
  const clock = vi.spyOn(Date, "now").mockReturnValue(start);
  await recordReview(db, card, ReviewQuality.AGAIN, false);
  expect((await db.reviewEvents.toArray())[0].actualDays).toBe(0);
  clock.mockReturnValue(start + 2 * 86_400_000);
  await saveVocabulary(db, { germanText: "Haus", englishTranslation: "home" });
  const current = (await db.vocabulary.toArray())[0];
  await recordReview(db, current, ReviewQuality.GOOD, false);
  expect((await db.reviewEvents.orderBy("id").last())?.actualDays).toBe(2);
});

it("extra practice keeps its schedule, records history, and awards no XP", async () => {
  const db = fresh(), card = await seed(db);
  const scheduled = await recordReview(db, card, ReviewQuality.GOOD, false);
  const extra = await recordReview(db, scheduled, ReviewQuality.EASY, true);
  expect(extra.nextReview).toBe(scheduled.nextReview);
  expect(extra.reviewCount).toBe(scheduled.reviewCount);
  expect((await db.userStats.get(1))?.xp).toBe(10);
  expect(await db.reviewEvents.count()).toBe(2);
});

it("rolls back schedule and XP when the event cannot be saved", async () => {
  const db = fresh(), card = await seed(db);
  vi.spyOn(db.reviewEvents, "add").mockRejectedValueOnce(new Error("quota"));
  await expect(recordReview(db, card, ReviewQuality.GOOD, false)).rejects.toThrow("quota");
  expect(await db.vocabulary.get(card.id!)).toEqual(card);
  expect(await db.userStats.count()).toBe(0);
  expect(await db.activityLog.count()).toBe(0);
});

it("round-trips long phrases, analyzed transcripts, review links, and a conversation", async () => {
  const source = fresh(), target = fresh();
  const card = await seed(source, "Ich lerne Deutsch. ".repeat(12).trim());
  await recordReview(source, card, ReviewQuality.GOOD, false);
  const id = await insertTranscript(source, "Guten Morgen");
  await updateTranscriptAnalysis(source, id, "Good morning", '{"keywords":["Morgen"]}');
  const turn = { position: 0, scenario: "Bakery", role: "assistant" as const,
    content: "Guten Morgen", translation: "Good morning", timestamp: Date.now() };
  await source.roleplayMessages.add(turn);
  await seed(target, "Hund"); // Source and destination IDs deliberately differ.
  const backup = await exportLibrary(source);
  expect(backup.version).toBe(2);
  await importLibrary(target, backup);
  await importLibrary(target, backup);
  const restored = await target.vocabulary.where("germanTextKey").equals(card.germanTextKey).first();
  expect(restored?.germanText).toBe(card.germanText);
  expect(restored?.id).not.toBe(card.id);
  expect(await target.reviewEvents.count()).toBe(1);
  expect((await target.reviewEvents.toArray())[0].vocabularyId).toBe(restored?.id);
  expect((await target.transcripts.toArray())[0]).toMatchObject({ translation: "Good morning", analysisJson: '{"keywords":["Morgen"]}' });
  expect(await target.roleplayMessages.toArray()).toEqual([turn]);
});

it("maps imported events to an existing folded word without replacing its local identity", async () => {
  const source = fresh(), target = fresh();
  const card = await seed(source, "Haus");
  await recordReview(source, card, ReviewQuality.GOOD, false);
  await seed(target, "Hund");
  await saveVocabulary(target, { germanText: "haus", englishTranslation: "home" });
  const local = (await target.vocabulary.where("germanTextKey").equals("haus").first())!;
  await importLibrary(target, await exportLibrary(source));
  expect(await target.vocabulary.count()).toBe(2);
  expect((await target.vocabulary.get(local.id!))?.remoteId).toBe(local.remoteId);
  expect((await target.reviewEvents.toArray())[0].vocabularyId).toBe(local.id);
});

it("keeps orphaned review history unlinked and does not overwrite a local conversation", async () => {
  const source = fresh(), target = fresh(), card = await seed(source);
  await recordReview(source, card, ReviewQuality.GOOD, false);
  await source.vocabulary.delete(card.id!);
  const local = { position: 0, scenario: "Hotel", role: "user" as const, content: "Hallo", timestamp: Date.now() };
  await target.roleplayMessages.add(local);
  await source.roleplayMessages.add({ ...local, scenario: "Bakery" });
  await seed(target, "Hund");
  await importLibrary(target, await exportLibrary(source));
  expect((await target.reviewEvents.toArray())[0].vocabularyId).toBe(0);
  expect(await target.roleplayMessages.toArray()).toEqual([local]);
});

it("undo restores transcript analysis and stable identity", async () => {
  const db = fresh();
  const id = await insertTranscript(db, "Hallo");
  await updateTranscriptAnalysis(db, id, "Hello", "{}");
  const original = (await db.transcripts.get(id))!;
  await deleteTranscript(db, original);
  await restoreTranscript(db, original);
  await restoreTranscript(db, original);
  const rows = await db.transcripts.toArray();
  expect(rows).toHaveLength(1);
  expect(rows[0]).toMatchObject({ remoteId: original.remoteId, translation: "Hello", analysisJson: "{}" });
});

it("refuses oversized exports before offering an unrestorable download", async () => {
  const db = fresh();
  await insertTranscript(db, "X".repeat(MAX_BACKUP_FILE_BYTES));
  await expect(exportLibrary(db)).rejects.toMatchObject({ reason: "capacity" });
  expect(await db.transcripts.count()).toBe(1);
});

it("rejects malformed new collections atomically and still accepts version 1", async () => {
  const source = fresh(), target = fresh();
  await seed(source);
  const backup = await exportLibrary(source);
  await expect(importLibrary(target, { ...backup, reviewEvents: [{ rating: "INVALID" }] })).rejects.toThrow();
  expect(await target.vocabulary.count()).toBe(0);
  await importLibrary(target, { ...backup, version: 1, reviewEvents: undefined, roleplayMessages: undefined });
  expect(await target.vocabulary.count()).toBe(1);
});
