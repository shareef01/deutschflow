import { foldGerman } from "@/lib/scoring";

/**
 * Search matching for German text, as people actually type it.
 *
 * Three spellings of "Mädchen" reach the search box: the real one, the
 * transliteration ("maedchen", via foldGerman), and the keyboard without
 * umlauts ("madchen"). Each is compared against the same form of the text, so
 * any of them finds the word. "ß" becomes "ss" in both folds: nobody types
 * "strase" for "Straße".
 */
function stripUmlauts(text: string): string {
  return text
    .normalize("NFC")
    .toLowerCase()
    .replaceAll("ä", "a")
    .replaceAll("ö", "o")
    .replaceAll("ü", "u")
    .replaceAll("ß", "ss")
    .replaceAll(/['’]/g, "");
}

/** A query folded once, so a list filter doesn't re-fold it per row. */
export interface GermanQuery {
  lower: string;
  folded: string;
  stripped: string;
}

/** Null for a blank query, meaning "show everything". */
export function prepareGermanQuery(query: string): GermanQuery | null {
  const raw = query.trim();
  if (!raw) return null;
  return { lower: raw.toLowerCase(), folded: foldGerman(raw), stripped: stripUmlauts(raw) };
}

export function matchesGerman(text: string, query: GermanQuery): boolean {
  return (
    text.toLowerCase().includes(query.lower) ||
    foldGerman(text).includes(query.folded) ||
    stripUmlauts(text).includes(query.stripped)
  );
}

/** Plain case-insensitive contains, for the English side. */
export function matchesPlain(text: string | undefined, query: GermanQuery): boolean {
  return text !== undefined && text.toLowerCase().includes(query.lower);
}
