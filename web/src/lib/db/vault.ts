/**
 * API-key vault — KeystoreCipher 1:1, built on WebCrypto.
 *
 * Mirrors app/src/main/java/com/aus/deutschflow/data/local/KeystoreCipher.kt:
 *   Android Keystore, AES/GCM/NoPadding, fresh random IV per encryption,
 *   IV prefixed to the ciphertext, no plaintext fallback.
 *
 * Web equivalent:
 *   - AES-256-GCM via crypto.subtle.
 *   - The key is generated NON-EXTRACTABLE (`extractable: false`) and stored in
 *     a dedicated IndexedDB database, so the key material never leaves the
 *     browser as a plaintext value — the analogue of a key that lives in secure
 *     hardware and never enters the process.
 *   - A fresh 12-byte IV per encryption, prefixed to the ciphertext, base64.
 *   - If encryption fails, the key is NOT written: there is no plaintext
 *     fallback, and the UI must say so instead of claiming a save that did not
 *     happen (PreferenceManager.saveApiKey returns a Boolean for exactly this).
 *   - Decryption failure is an ordinary outcome, not an error to escalate: the
 *     key is gone and needs entering again, and neither is a crash.
 */

const ALGORITHM = { name: "AES-GCM", length: 256 } as const;
const KEY_USAGES: KeyUsage[] = ["encrypt", "decrypt"];
const KEY_ALIAS = "deutschflow.api-key";

/**
 * Dedicated IndexedDB database. It must NOT be the Dexie "deutschflow" store —
 * a CryptoKey object would not survive Dexie's typed tables, and the key
 * deserves a vault of its own, mirroring the Android Keystore's separation
 * from the Room database.
 */
const VAULT_DB_NAME = "deutschflow-vault";
const VAULT_STORE = "keys";

const IV_LENGTH = 12;

function openVault(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(VAULT_DB_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(VAULT_STORE)) {
        request.result.createObjectStore(VAULT_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function readKeyFromVault(): Promise<CryptoKey | undefined> {
  const db = await openVault();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(VAULT_STORE, "readonly");
      const get = tx.objectStore(VAULT_STORE).get(KEY_ALIAS);
      get.onsuccess = () => resolve(get.result as CryptoKey | undefined);
      get.onerror = () => reject(get.error);
    });
  } finally {
    db.close();
  }
}

async function writeKeyToVault(key: CryptoKey): Promise<void> {
  const db = await openVault();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(VAULT_STORE, "readwrite");
      tx.objectStore(VAULT_STORE).put(key, KEY_ALIAS);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

async function getOrCreateKey(): Promise<CryptoKey> {
  const existing = await readKeyFromVault();
  if (existing) return existing;

  // Non-extractable: exportKey() will throw, so the key material cannot be
  // copied out of the browser as a plaintext value.
  const key = await crypto.subtle.generateKey(ALGORITHM, false, KEY_USAGES);
  await writeKeyToVault(key);
  return key;
}

function base64Encode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64Decode(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/**
 * Encrypts the API key.
 *
 * @returns the ciphertext (`iv || ciphertext`, base64), or null when the value
 * could not be produced — in which case the caller must NOT fall back to
 * storing the plaintext.
 */
export async function encryptApiKey(plainText: string): Promise<string | null> {
  if (!plainText) return null;
  try {
    const key = await getOrCreateKey();
    const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv },
      key,
      new TextEncoder().encode(plainText)
    );

    const out = new Uint8Array(IV_LENGTH + ciphertext.byteLength);
    out.set(iv, 0);
    out.set(new Uint8Array(ciphertext), IV_LENGTH);
    return base64Encode(out);
  } catch {
    return null;
  }
}

/**
 * Decrypts a stored key.
 *
 * @returns the plaintext, or null when the stored value cannot be read —
 * exactly the KeystoreCipher contract: a restored backup can carry ciphertext
 * whose key never came with it, which means "enter the key again", not a crash.
 */
export async function decryptApiKey(stored: string): Promise<string | null> {
  try {
    const key = await getOrCreateKey();
    const bytes = base64Decode(stored);
    if (bytes.length <= IV_LENGTH) return null;

    const iv = bytes.slice(0, IV_LENGTH);
    const body = bytes.slice(IV_LENGTH);
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, body);
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}
