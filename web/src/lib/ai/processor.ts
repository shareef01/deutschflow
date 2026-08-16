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
      return templates[Math.floor(Math.random() * templates.length)];
  }
}

export const vocabularyProcessor = new VocabularyProcessor();
