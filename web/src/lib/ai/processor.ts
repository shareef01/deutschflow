/**
 * VocabularyProcessor — port of
 * app/src/main/java/com/aus/deutschflow/service/VocabularyProcessor.kt.
 *
 * Open, so a store can substitute a processor whose answers arrive when the
 * test says rather than when a server does.
 */
import {
  interrogateWord as groqInterrogate,
  translateAndExtract as groqTranslate,
  type AIResult,
  type WordDetailsResult,
} from "./groq";

export class VocabularyProcessor {
  async processText(text: string, apiKey: string): Promise<AIResult> {
    return groqTranslate(text, apiKey);
  }

  async interrogateWord(word: string, apiKey: string): Promise<WordDetailsResult> {
    return groqInterrogate(word, apiKey);
  }

  generateExample(word: string): string {
    return generateExample(word);
  }
}

/**
 * A conversational example for a word, built from templates — the fallback for
 * words typed in by hand, which never went near the model and so carry no
 * example of their own. German in every locale, deliberately: it is the
 * material being learned, not interface text.
 *
 * Chosen by the word rather than at random, matching the Kotlin companion: a
 * random pick returned a different sentence on every call, and the library
 * renders this during a render pass — so a word's example changed underneath the
 * reader. The sentence is a property of the word, so it is derived from it.
 */
export function generateExample(word: string): string {
  const templates = [
    `Kannst du mir helfen, das Wort '${word}' zu verstehen?`,
    `Ich möchte mehr über '${word}' lernen.`,
    `Wie sagt man '${word}' auf Englisch?`,
    `Heute habe ich das Wort '${word}' im Unterricht gelernt.`,
    `Kannst du '${word}' in einem Satz verwenden?`,
    `Das Wort '${word}' ist sehr wichtig für mich.`,
    `Ich übe gerade die Aussprache von '${word}'.`,
    `Warum benutzt du so oft das Wort '${word}'?`,
    `Es ist nicht einfach, '${word}' richtig zu benutzen.`,
    `Gestern habe ich '${word}' in einem Buch gelesen.`,
  ];

  switch (word.toLowerCase()) {
    case "hallo":
      return "Hallo, wie geht es dir?";
    case "deutsch":
      return "Ich lerne jeden Tag Deutsch.";
    case "lernen":
      return "Wir lernen zusammen in der Schule.";
    case "sprechen":
      return "Kannst du bitte langsamer sprechen?";
    default:
      return templates[hashIndex(word, templates.length)];
  }
}

/**
 * Java's `String.hashCode`, folded into range the way `Math.floorMod` folds it.
 *
 * The same algorithm as the Kotlin side deliberately: the two apps read the same
 * library, and a word carried between them should not describe itself differently
 * on each. `Math.imul` and `| 0` reproduce Java's signed 32-bit overflow, and
 * `((h % n) + n) % n` reproduces floorMod — a plain `%` keeps JavaScript's sign
 * and would disagree with Kotlin on every word that hashes negative.
 */
function hashIndex(word: string, size: number): number {
  let hash = 0;
  for (let i = 0; i < word.length; i++) {
    hash = (Math.imul(31, hash) + word.charCodeAt(i)) | 0;
  }
  return ((hash % size) + size) % size;
}

export const vocabularyProcessor = new VocabularyProcessor();
