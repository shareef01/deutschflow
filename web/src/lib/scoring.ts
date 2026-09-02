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

function tokenize(text: string): string[] {
  return text
    .split(WORD_SPLIT)
    .map((w) => w.replace(NON_LETTERS, ""))
    .filter((w) => w.length > 0);
}

/**
 * Which target positions appear, in order, in what was heard.
 *
 * Standard longest-common-subsequence over the two folded token lists, then a
 * walk back through the table to recover which target indices were matched.
 * O(target x spoken), which for one sentence is nothing. Mirrors
 * PracticeViewModel.alignedTargetIndices exactly.
 */
function alignedTargetIndices(target: string[], spoken: string[]): Set<number> {
  const matched = new Set<number>();
  if (target.length === 0 || spoken.length === 0) return matched;

  // lengths[i][j] = LCS length of target[i..] and spoken[j..]
  const lengths: number[][] = Array.from({ length: target.length + 1 }, () =>
    new Array<number>(spoken.length + 1).fill(0)
  );
  for (let i = target.length - 1; i >= 0; i--) {
    for (let j = spoken.length - 1; j >= 0; j--) {
      lengths[i][j] =
        target[i] === spoken[j]
          ? lengths[i + 1][j + 1] + 1
          : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
    }
  }

  let i = 0;
  let j = 0;
  while (i < target.length && j < spoken.length) {
    if (target[i] === spoken[j]) {
      matched.add(i);
      i++;
      j++;
    } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
      i++;
    } else {
      j++;
    }
  }
  return matched;
}

/**
 * Scores [spokenText] against [targetSentence], in order.
 *
 * What this measures, stated plainly because the feature used to claim more:
 * how much of the target sentence the *recogniser* reported hearing. It is a
 * recall and intelligibility check, not phoneme-level pronunciation scoring —
 * neither SpeechRecognizer nor the Web Speech API exposes per-phoneme
 * confidence, so that would need a forced-alignment model. A speech engine's
 * language model also resolves ambiguous audio toward plausible sentences, so
 * it will often report the word you meant even when you said it poorly.
 *
 * The matching is a longest-common-subsequence alignment rather than the set
 * membership this used to do, which was wrong in two ways a learner would
 * notice: order was ignored, so saying the sentence backwards scored perfect;
 * and repetition was ignored, so a target containing "die" twice was satisfied
 * by saying it once. An LCS fixes both at once, because a subsequence is
 * ordered and consumes each match.
 */
export function evaluateMatch(
  targetSentence: string,
  spokenText: string
): { results: WordResult[]; feedback: PracticeFeedback } {
  const targetWords = tokenize(targetSentence);
  const spokenWords = tokenize(spokenText).map(foldGerman);

  const matched = alignedTargetIndices(targetWords.map(foldGerman), spokenWords);

  const results: WordResult[] = targetWords.map((targetWord, index) => ({
    word: targetWord,
    isCorrect: matched.has(index),
  }));

  const correctCount = results.filter((r) => r.isCorrect).length;
  const feedback: PracticeFeedback =
    results.length === 0
      ? "NONE"
      : correctCount === results.length
        ? "PERFECT"
        : // Three quarters, not half. "Most words were clear" was reported for
          // getting half a sentence right, which is not most of anything.
          correctCount * 4 >= results.length * 3
          ? "GOOD"
          : "KEEP_GOING";

  return { results, feedback };
}
