import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { DeutschFlowDB, foldGermanKey } from "@/lib/db/schema";
import { foldGerman, evaluateMatch } from "@/lib/scoring";
import {
  MIN_EASE_FACTOR,
  MAX_EASE_FACTOR,
  MAX_INTERVAL_DAYS,
  ReviewQuality,
  calculateNextReview,
} from "@/lib/ai/srs";
import { XP_PER_CARD, DAILY_XP_GOAL } from "@/lib/db/repository";
import { importLibrary } from "@/lib/db/backup";
import {
  GROQ_MODEL,
  MAX_AI_INPUT_CHARS,
  MAX_ROLEPLAY_USER_CHARS,
  MAX_ROLEPLAY_SCENARIO_CHARS,
  MAX_ROLEPLAY_MESSAGE_CHARS,
  MAX_ROLEPLAY_HISTORY_TURNS,
  MAX_ROLEPLAY_HISTORY_CHARS,
  MAX_ROLEPLAY_REPLY_CHARS,
  MAX_ROLEPLAY_CONTEXT_CHARS,
} from "@/lib/ai/groq";

interface ContractFixture {
  germanKeyCases: { input: string; expected: string }[];
  srsConstants: {
    MIN_EASE_FACTOR: number;
    MAX_EASE_FACTOR: number;
    MAX_INTERVAL_DAYS: number;
    XP_PER_CARD: number;
    DAILY_XP_GOAL: number;
  };
  aiConstants: {
    MODEL_NAME: string;
    MAX_AI_INPUT_CHARS: number;
    MAX_ROLEPLAY_USER_CHARS: number;
    MAX_ROLEPLAY_SCENARIO_CHARS: number;
    MAX_ROLEPLAY_MESSAGE_CHARS: number;
    MAX_ROLEPLAY_HISTORY_TURNS: number;
    MAX_ROLEPLAY_HISTORY_CHARS: number;
    MAX_ROLEPLAY_REPLY_CHARS: number;
    MAX_ROLEPLAY_CONTEXT_CHARS: number;
  };
  practiceScoringCases: {
    name: string;
    target: string;
    spoken: string;
    expectedFeedback: string;
    expectedCorrectCount: number;
    expectedTotalCount: number;
  }[];
  srsSchedulingCases: {
    name: string;
    interval: number;
    easeFactor: number;
    reviewCount: number;
    quality: number;
    expectedInterval: number;
    expectedEaseFactor: number;
    expectedReviewCount: number;
  }[];
  backupSchemaFixtures: {
    validV1: Record<string, unknown>;
    corruptMissingFormat: Record<string, unknown>;
    corruptNewerVersion: Record<string, unknown>;
    corruptNonArrayCollections: Record<string, unknown>;
    corruptInvalidDate: Record<string, unknown>;
  };
}

const contract: ContractFixture = JSON.parse(
  readFileSync(
    join(__dirname, "..", "..", "app", "src", "test", "resources", "cross-platform-contract.json"),
    "utf8"
  )
);

describe("Cross-Platform Behavioral Contract", () => {
  describe("German folding identity contract", () => {
    for (const { input, expected } of contract.germanKeyCases) {
      it(`folds '${input}' to '${expected}' in foldGermanKey and foldGerman`, () => {
        expect(foldGermanKey(input)).toBe(expected);
        expect(foldGerman(input)).toBe(expected);
      });
    }
  });

  describe("SRS and XP constants contract", () => {
    it("matches SRS scheduling boundaries", () => {
      expect(MIN_EASE_FACTOR).toBe(contract.srsConstants.MIN_EASE_FACTOR);
      expect(MAX_EASE_FACTOR).toBe(contract.srsConstants.MAX_EASE_FACTOR);
      expect(MAX_INTERVAL_DAYS).toBe(contract.srsConstants.MAX_INTERVAL_DAYS);
    });

    it("matches XP values", () => {
      expect(XP_PER_CARD).toBe(contract.srsConstants.XP_PER_CARD);
      expect(DAILY_XP_GOAL).toBe(contract.srsConstants.DAILY_XP_GOAL);
    });
  });

  describe("AI constants contract", () => {
    it("matches model and character/turn limits", () => {
      expect(GROQ_MODEL).toBe(contract.aiConstants.MODEL_NAME);
      expect(MAX_AI_INPUT_CHARS).toBe(contract.aiConstants.MAX_AI_INPUT_CHARS);
      expect(MAX_ROLEPLAY_USER_CHARS).toBe(contract.aiConstants.MAX_ROLEPLAY_USER_CHARS);
      expect(MAX_ROLEPLAY_SCENARIO_CHARS).toBe(contract.aiConstants.MAX_ROLEPLAY_SCENARIO_CHARS);
      expect(MAX_ROLEPLAY_MESSAGE_CHARS).toBe(contract.aiConstants.MAX_ROLEPLAY_MESSAGE_CHARS);
      expect(MAX_ROLEPLAY_HISTORY_TURNS).toBe(contract.aiConstants.MAX_ROLEPLAY_HISTORY_TURNS);
      expect(MAX_ROLEPLAY_HISTORY_CHARS).toBe(contract.aiConstants.MAX_ROLEPLAY_HISTORY_CHARS);
      expect(MAX_ROLEPLAY_REPLY_CHARS).toBe(contract.aiConstants.MAX_ROLEPLAY_REPLY_CHARS);
      expect(MAX_ROLEPLAY_CONTEXT_CHARS).toBe(contract.aiConstants.MAX_ROLEPLAY_CONTEXT_CHARS);
    });
  });

  describe("Practice scoring contract", () => {
    for (const testCase of contract.practiceScoringCases) {
      it(`scores correctly for ${testCase.name}`, () => {
        const result = evaluateMatch(testCase.target, testCase.spoken);
        expect(result.feedback).toBe(testCase.expectedFeedback);
        expect(result.results.filter((r) => r.isCorrect).length).toBe(testCase.expectedCorrectCount);
        expect(result.results.length).toBe(testCase.expectedTotalCount);
      });
    }
  });

  describe("SRS scheduling progression contract", () => {
    for (const testCase of contract.srsSchedulingCases) {
      it(`schedules correctly for ${testCase.name}`, () => {
        const vocab = {
          id: 1,
          germanText: "das Haus",
          germanTextKey: "das haus",
          englishTranslation: "the house",
          timestamp: 1000,
          exampleSentence: "",
          article: "das",
          plural: "Häuser",
          conjugation: "",
          synonyms: "",
          antonyms: "",
          nextReview: 0,
          interval: testCase.interval,
          easeFactor: testCase.easeFactor,
          reviewCount: testCase.reviewCount,
          remoteId: "fixture-id",
          lastModifiedAt: 1000,
        };

        const result = calculateNextReview(vocab, testCase.quality as ReviewQuality);
        expect(result.interval).toBe(testCase.expectedInterval);
        expect(result.easeFactor).toBeCloseTo(testCase.expectedEaseFactor, 4);
        expect(result.reviewCount).toBe(testCase.expectedReviewCount);
      });
    }
  });

  describe("Backup serialization schema contract", () => {
    let db: DeutschFlowDB;
    let counter = 0;

    beforeEach(() => {
      db = new DeutschFlowDB(`contract-backup-test-${counter++}`);
    });

    afterEach(async () => {
      await db.delete();
    });

    it("successfully imports validV1 contract fixture", async () => {
      const result = await importLibrary(db, contract.backupSchemaFixtures.validV1);
      expect(result.vocabularyAdded).toBe(1);
      expect(result.transcriptsAdded).toBe(1);

      const vocab = await db.vocabulary.where("germanTextKey").equals("das haus").first();
      expect(vocab).toBeDefined();
      expect(vocab?.englishTranslation).toBe("the house");
    });

    it("rejects backup missing format header", async () => {
      await expect(
        importLibrary(db, contract.backupSchemaFixtures.corruptMissingFormat)
      ).rejects.toThrow(/not a DeutschFlow library export/i);
    });

    it("rejects backup from newer schema version", async () => {
      await expect(
        importLibrary(db, contract.backupSchemaFixtures.corruptNewerVersion)
      ).rejects.toThrow(/newer/i);
    });

    it("rejects backup with non-array collection structures", async () => {
      await expect(
        importLibrary(db, contract.backupSchemaFixtures.corruptNonArrayCollections)
      ).rejects.toThrow(/array/i);
    });

    it("rejects backup with invalid calendar date in activity log", async () => {
      await expect(
        importLibrary(db, contract.backupSchemaFixtures.corruptInvalidDate)
      ).rejects.toThrow(/calendar date/i);
    });
  });
});
