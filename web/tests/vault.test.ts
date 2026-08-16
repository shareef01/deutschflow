import { describe, expect, it } from "vitest";
import { decryptApiKey, encryptApiKey } from "@/lib/db/vault";

describe("vault — KeystoreCipher port", () => {
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
});
