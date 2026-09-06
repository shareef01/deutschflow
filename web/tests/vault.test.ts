import { beforeEach, describe, expect, it } from "vitest";
import "fake-indexeddb/auto";
import {
  decryptApiKey,
  encryptApiKey,
  getOrCreateKey,
  resetVaultMemoryCacheForTesting,
} from "@/lib/db/vault";

describe("vault — KeystoreCipher port", () => {
  beforeEach(async () => {
    resetVaultMemoryCacheForTesting();
    // Delete database to test from empty vault
    await new Promise<void>((resolve) => {
      const req = indexedDB.deleteDatabase("deutschflow-vault");
      req.onsuccess = () => resolve();
      req.onerror = () => resolve();
      req.onblocked = () => resolve();
    });
  });

  it("round-trips a key through AES-GCM", async () => {
    const ciphertext = await encryptApiKey("gsk_test_abc123");
    expect(ciphertext).not.toBeNull();
    expect(await decryptApiKey(ciphertext!)).toBe("gsk_test_abc123");
  });

  it("never lets the plaintext appear in the stored value", async () => {
    const plain = "gsk_very_secret_key_material";
    const ciphertext = (await encryptApiKey(plain))!;
    expect(ciphertext).not.toContain(plain);
    // The vault's own file should hold no plaintext either: the value in the
    // settings row is exactly this ciphertext.
    expect(ciphertext).toMatch(/^[A-Za-z0-9+/]+=*$/);
  });

  it("returns null for undecryptable values instead of crashing", async () => {
    expect(await decryptApiKey("garbage")).toBeNull();
    expect(await decryptApiKey("")).toBeNull();
  });

  it("produces a different ciphertext per encryption (fresh IV)", async () => {
    const a = await encryptApiKey("same-key");
    const b = await encryptApiKey("same-key");
    expect(a).not.toBe(b);
  });

  it("reuses an existing key across sequential calls", async () => {
    const key1 = await getOrCreateKey();
    resetVaultMemoryCacheForTesting();
    const key2 = await getOrCreateKey();

    // Verify both keys are identical by encrypting with key1 and decrypting with key2
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = new TextEncoder().encode("secret");
    const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key1, data);
    const decrypted = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key2, encrypted);
    expect(new TextDecoder().decode(decrypted)).toBe("secret");
  });

  it("two simultaneous first callers converge on ONE stored key", async () => {
    // Both callers start with an empty vault and empty memory cache
    const [keyA, keyB] = await Promise.all([getOrCreateKey(), getOrCreateKey()]);

    // Encrypt with keyA, decrypt with keyB to prove convergence
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = new TextEncoder().encode("concurrent-secret");
    const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, keyA, data);
    const decrypted = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, keyB, encrypted);
    expect(new TextDecoder().decode(decrypted)).toBe("concurrent-secret");
  });

  it("two concurrent callers without in-memory memoization converge via store.add constraint conflict", async () => {
    // To simulate cross-tab concurrency where each tab has its own memory cache:
    // We invoke two calls where memory cache is cleared before each gets its candidate
    const p1 = getOrCreateKey();
    resetVaultMemoryCacheForTesting();
    const p2 = getOrCreateKey();

    const [key1, key2] = await Promise.all([p1, p2]);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = new TextEncoder().encode("cross-tab-secret");
    const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key1, data);
    const decrypted = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key2, encrypted);
    expect(new TextDecoder().decode(decrypted)).toBe("cross-tab-secret");
  });

  it("never falls back to plaintext if encryption fails", async () => {
    expect(await encryptApiKey("")).toBeNull();
  });
});

