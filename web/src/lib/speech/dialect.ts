import { db } from "@/lib/db";
import { DEFAULT_DIALECT, getDialect, isDialect } from "@/lib/db/settings";

/**
 * Resolves the user's speech recognition dialect from storage, validating it
 * against supported dialect codes. Falls back safely to de-DE on any read failure
 * so storage hiccups never kill speech recognition.
 */
export async function resolveRecognitionDialect(): Promise<string> {
  try {
    const dialect = await getDialect(db);
    return isDialect(dialect) ? dialect : DEFAULT_DIALECT;
  } catch {
    return DEFAULT_DIALECT;
  }
}
