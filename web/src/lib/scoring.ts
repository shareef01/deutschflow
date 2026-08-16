/**
 * Pronunciation scoring — PracticeViewModel.evaluateMatch port, kept pure so it
 * is unit-testable exactly like the Kotlin companion.
 *
 * The subtle behaviour preserved: German has a standard transliteration for
 * keyboards without umlauts (ue for ü, oe for ö, ae for ä, ss for ß), and the
 * recogniser always returns the umlaut — so a word saved by hand as "Uebung"
 * must match the "Übung" that comes back from the microphone. `lowercase()` is
 * locale-invariant (a Turkish locale must not map I to a dotless ı).
 */

export type PracticeFeedback = "NONE" | "PERFECT" | "GOOD" | "KEEP_GOING";

export interface WordResult {
  /** The target as it was written, not folded — the user reads this back. */
  word: string;
  isCorrect: boolean;
}

/** Feedback wording lives in the i18n dictionary; only the level crosses here. */
export const PRACTICE_FEEDBACK_KEYS = {
  PERFECT: "practice.feedbackPerfect",
  GOOD: "practice.feedbackGood",
  KEEP_GOING: "practice.feedbackKeepGoing",
} as const;

const WORD_SPLIT = /\s+/;
const NON_LETTERS = /[^a-zA-ZäöüÄÖÜß]/g;

/** Folds a word to the form both spellings of it share. */
export function foldGerman(word: string): string {
  return word
    .toLowerCase()
    .replaceAll("ä", "ae")
    .replaceAll("ö", "oe")
    .replaceAll("ü", "ue")
    .replaceAll("ß", "ss");
}

/**
 * Scores [spokenText] against [targetSentence] word-by-word. Each word in the
 * target is checked for presence in the spoken text, ignoring case and umlaut
 * spelling; the feedback follows the same progression the UI shows: perfect
 * match, mostly correct, or keep at it.
 */
export function evaluateMatch(
  targetSentence: string,
  spokenText: string
): { results: WordResult[]; feedback: PracticeFeedback } {
  const targetWords = targetSentence
    .split(WORD_SPLIT)
    .map((w) => w.replace(NON_LETTERS, ""))
    .filter((w) => w.length > 0);

  const spokenKeys = new Set(
    spokenText
      .split(WORD_SPLIT)
      .map((w) => w.replace(NON_LETTERS, ""))
      .filter((w) => w.length > 0)
      .map(foldGerman)
  );

  const results: WordResult[] = targetWords.map((targetWord) => ({
    word: targetWord,
    isCorrect: spokenKeys.has(foldGerman(targetWord)),
  }));

  const correctCount = results.filter((r) => r.isCorrect).length;
  const feedback: PracticeFeedback =
    results.length === 0
      ? "NONE"
      : correctCount === results.length
        ? "PERFECT"
        : correctCount * 2 > results.length
          ? "GOOD"
          : "KEEP_GOING";

  return { results, feedback };
}
