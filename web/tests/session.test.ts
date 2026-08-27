import { describe, expect, it } from "vitest";
import {
  createSessionToken,
  safeRedirectTarget,
  SESSION_MAX_AGE_SECONDS,
  verifySessionToken,
} from "@/lib/auth/session";

/**
 * The gate's whole job is deciding what this cookie admits, so the tamper
 * cases are tested directly: the smoke suite covers the literal "granted"
 * forgery over HTTP; these cover what the verifier itself refuses.
 */

const SECRET = "test-site-password";
const NOW = 1_700_000_000_000;

describe("verifySessionToken", () => {
  it("admits a token it signed", async () => {
    const token = await createSessionToken(SECRET, NOW);
    expect(await verifySessionToken(token, SECRET, NOW)).toBe(true);
  });

  it("admits it until the expiry and not a millisecond past", async () => {
    const token = await createSessionToken(SECRET, NOW);
    // The check is `expiresAt <= now`, so the last valid millisecond is the one
    // before the expiry lands on.
    expect(
      await verifySessionToken(token, SECRET, NOW + SESSION_MAX_AGE_SECONDS * 1000 - 1)
    ).toBe(true);
    expect(
      await verifySessionToken(token, SECRET, NOW + SESSION_MAX_AGE_SECONDS * 1000)
    ).toBe(false);
  });

  it("refuses a token signed by another secret", async () => {
    const token = await createSessionToken("a different site password", NOW);
    expect(await verifySessionToken(token, SECRET, NOW)).toBe(false);
  });

  it("refuses a truncated signature", async () => {
    const token = await createSessionToken(SECRET, NOW);
    expect(await verifySessionToken(token.slice(0, -2), SECRET, NOW)).toBe(false);
  });

  it("refuses an extended expiry — the signature covers the payload", async () => {
    const token = await createSessionToken(SECRET, NOW);
    const separator = token.lastIndexOf(".");
    const forged = `${Number(token.slice(0, separator)) + 90 * 24 * 60 * 60 * 1000}.${token.slice(separator + 1)}`;
    expect(await verifySessionToken(forged, SECRET, NOW)).toBe(false);
  });

  it("refuses a non-numeric payload", async () => {
    expect(await verifySessionToken("abc.def", SECRET, NOW)).toBe(false);
  });

  it("refuses the literal cookie that once opened the gate", async () => {
    expect(await verifySessionToken("granted", SECRET, NOW)).toBe(false);
  });

  it("refuses anything without a payload.signature shape", async () => {
    expect(await verifySessionToken(undefined, SECRET, NOW)).toBe(false);
    expect(await verifySessionToken("", SECRET, NOW)).toBe(false);
    expect(await verifySessionToken("nodot", SECRET, NOW)).toBe(false);
    expect(await verifySessionToken(".signature", SECRET, NOW)).toBe(false);
  });
});

describe("safeRedirectTarget — post-login destinations stay on site", () => {
  const FALLBACK = "/transcript";

  it("keeps an in-app path", () => {
    expect(safeRedirectTarget("/history", FALLBACK)).toBe("/history");
  });

  it("rejects a protocol-relative host — browsers treat it as off-site", () => {
    expect(safeRedirectTarget("//evil.example", FALLBACK)).toBe(FALLBACK);
  });

  it("rejects absolute URLs", () => {
    expect(safeRedirectTarget("https://evil.example/hook", FALLBACK)).toBe(FALLBACK);
  });

  it("refuses to send the user back to the login page", () => {
    expect(safeRedirectTarget("/login", FALLBACK)).toBe(FALLBACK);
  });

  it("falls back on anything that is not a string", () => {
    expect(safeRedirectTarget(undefined, FALLBACK)).toBe(FALLBACK);
    expect(safeRedirectTarget(null, FALLBACK)).toBe(FALLBACK);
    expect(safeRedirectTarget(42, FALLBACK)).toBe(FALLBACK);
  });
});
