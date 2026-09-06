import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { foldGermanKey } from "@/lib/db/schema";
import { foldGerman, evaluateMatch } from "@/lib/scoring";
import {
  MIN_EASE_FACTOR,
  MAX_EASE_FACTOR,
  MAX_INTERVAL_DAYS,
} from "@/lib/ai/srs";
import { XP_PER_CARD, DAILY_XP_GOAL } from "@/lib/db/repository";
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
});
