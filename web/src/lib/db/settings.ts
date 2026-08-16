import type { DeutschFlowDB } from "./schema";
import { decryptApiKey, encryptApiKey } from "./vault";

/**
 * Settings store — PreferenceManager (DataStore) 1:1.
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/data/local/PreferenceManager.kt:
 *   KEY_DIALECT                → getDialect / setDialect
 *   KEY_AUTO_PLAY              → getAutoPlay / setAutoPlay
 *   KEY_API_KEY_ENCRYPTED      → getEncryptedApiKey / saveApiKey / deleteApiKey
 *
 * The API key is stored ONLY as ciphertext from the WebCrypto vault, exactly as
 * Android stores only the AES-GCM-encrypted form in DataStore. `saveApiKey`
 * returns false when encryption fails — there is no plaintext fallback, and the
 * UI must say so instead of claiming a save that did not happen.
 */

export const DEFAULT_DIALECT = "de-DE";
export const DIALECTS = ["de-DE", "de-AT", "de-CH"] as const;
export type Dialect = (typeof DIALECTS)[number];

export const SETTING_KEYS = {
  dialect: "dialect",
  autoPlay: "auto_play",
  apiKeyCiphertext: "groq_api_key_encrypted",
  language: "language",
} as const;

export function isDialect(value: string): value is Dialect {
  return (DIALECTS as readonly string[]).includes(value);
}

export async function getDialect(db: DeutschFlowDB): Promise<Dialect> {
  const row = await db.settings.get(SETTING_KEYS.dialect);
  return row && isDialect(row.value) ? row.value : DEFAULT_DIALECT;
}

export async function setDialect(db: DeutschFlowDB, dialect: Dialect): Promise<void> {
  await db.settings.put({ key: SETTING_KEYS.dialect, value: dialect });
}

export async function getAutoPlay(db: DeutschFlowDB): Promise<boolean> {
  const row = await db.settings.get(SETTING_KEYS.autoPlay);
  return row ? row.value === "true" : true;
}

export async function setAutoPlay(db: DeutschFlowDB, enabled: boolean): Promise<void> {
  await db.settings.put({ key: SETTING_KEYS.autoPlay, value: String(enabled) });
}

export async function getLanguage(db: DeutschFlowDB): Promise<string | undefined> {
  return (await db.settings.get(SETTING_KEYS.language))?.value;
}

export async function setLanguage(db: DeutschFlowDB, language: string): Promise<void> {
  await db.settings.put({ key: SETTING_KEYS.language, value: language });
}

/* ---------------------------------------------------------------------------
   API key — write-only from the UI.
   --------------------------------------------------------------------------- */

/** The stored ciphertext, for the Settings screen's "a key is saved" report. */
export async function getEncryptedApiKey(db: DeutschFlowDB): Promise<string | undefined> {
  return (await db.settings.get(SETTING_KEYS.apiKeyCiphertext))?.value;
}

export async function hasApiKey(db: DeutschFlowDB): Promise<boolean> {
  return (await getEncryptedApiKey(db)) !== undefined;
}

/**
 * Encrypts and stores the key.
 *
 * @returns false when the key could not be encrypted — the caller must report
 * that instead of claiming a save that did not happen.
 */
export async function saveApiKey(db: DeutschFlowDB, apiKey: string): Promise<boolean> {
  const ciphertext = await encryptApiKey(apiKey);
  if (ciphertext === null) return false;
  await db.settings.put({ key: SETTING_KEYS.apiKeyCiphertext, value: ciphertext });
  return true;
}

/**
 * The plaintext, or null when it cannot be recovered (the vault key is gone —
 * "enter the key again", not a crash).
 */
export async function getApiKey(db: DeutschFlowDB): Promise<string | null> {
  const ciphertext = await getEncryptedApiKey(db);
  if (!ciphertext) return null;
  return decryptApiKey(ciphertext);
}

export async function deleteApiKey(db: DeutschFlowDB): Promise<void> {
  await db.settings.delete(SETTING_KEYS.apiKeyCiphertext);
}

/** Live (Flow-like) settings for React bindings (dexie liveQuery). */
export function observeDialect(db: DeutschFlowDB) {
  return db.settings.get(SETTING_KEYS.dialect);
}

export function observeAutoPlay(db: DeutschFlowDB) {
  return db.settings.get(SETTING_KEYS.autoPlay);
}
