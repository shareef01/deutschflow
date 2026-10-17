import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createSessionToken,
  deriveSessionSigningSecret,
  MIN_SESSION_SECRET_BYTES,
  passwordMatches,
  safeRedirectTarget,
  SESSION_COOKIE,
  sessionSecret,
  SESSION_MAX_AGE_SECONDS,
  verifySessionToken,
} from "@/lib/auth/session";
import { GET as logoutGet, POST as logoutPost } from "@/app/api/logout/route";

/**
 * Security & session revocation test suite.
 *
 * Covers:
 * - Valid authentication
 * - Invalid password
 * - Modified cookie
 * - Expired session
 * - Missing session
 * - Revoked session
 * - Password rotation
 * - Session signing-key rotation
 * - Global epoch revocation
 * - Logout cookie clearance
 * - Multiple concurrent browser sessions
 * - Protected route redirection safety
 */

const SECRET = "test-session-secret-high-entropy";
const PASSWORD = "correct-horse-battery-staple";
const NOW = 1_700_000_000_000;

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("sessionSecret", () => {
  it("rejects absent and weak signing secrets", () => {
    vi.stubEnv("SESSION_SECRET", "");
    expect(sessionSecret()).toBeNull();

    vi.stubEnv("SESSION_SECRET", "a".repeat(MIN_SESSION_SECRET_BYTES - 1));
    expect(sessionSecret()).toBeNull();
  });

  it("accepts a secret with at least 32 bytes", () => {
    const configured = "a".repeat(MIN_SESSION_SECRET_BYTES);
    vi.stubEnv("SESSION_SECRET", configured);
    expect(sessionSecret()).toBe(configured);
  });
});

describe("deriveSessionSigningSecret — cryptographic revocation key derivation", () => {
  it("produces deterministic keys for matching inputs", async () => {
    const key1 = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    const key2 = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    expect(key1).toBe(key2);
    expect(key1.length).toBeGreaterThan(20);
  });

  it("changes key when password is rotated", async () => {
    const keyOriginal = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    const keyNewPassword = await deriveSessionSigningSecret(SECRET, "new-rotated-password", "1");
    expect(keyNewPassword).not.toBe(keyOriginal);
  });

  it("changes key when session secret is rotated", async () => {
    const keyOriginal = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    const keyNewSecret = await deriveSessionSigningSecret("new-rotated-secret", PASSWORD, "1");
    expect(keyNewSecret).not.toBe(keyOriginal);
  });

  it("changes key when session epoch is incremented (global revocation)", async () => {
    const keyEpoch1 = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    const keyEpoch2 = await deriveSessionSigningSecret(SECRET, PASSWORD, "2");
    expect(keyEpoch2).not.toBe(keyEpoch1);
  });
});

describe("verifySessionToken — validation, expiration, and tampering", () => {
  it("admits a token it signed", async () => {
    const token = await createSessionToken(SECRET, NOW);
    expect(await verifySessionToken(token, SECRET, NOW)).toBe(true);
  });

  it("admits it until the expiry and not a millisecond past", async () => {
    const token = await createSessionToken(SECRET, NOW);
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

  it("refuses a modified cookie with tampered signature bytes", async () => {
    const token = await createSessionToken(SECRET, NOW);
    const separator = token.lastIndexOf(".");
    const sig = token.slice(separator + 1);
    const tamperedSig = sig.startsWith("a") ? "b" + sig.slice(1) : "a" + sig.slice(1);
    const tamperedToken = `${token.slice(0, separator)}.${tamperedSig}`;
    expect(await verifySessionToken(tamperedToken, SECRET, NOW)).toBe(false);
  });

  it("refuses a non-numeric payload", async () => {
    expect(await verifySessionToken("abc.def", SECRET, NOW)).toBe(false);
  });

  it("refuses the literal cookie that once opened the gate", async () => {
    expect(await verifySessionToken("granted", SECRET, NOW)).toBe(false);
  });

  it("refuses missing or malformed tokens", async () => {
    expect(await verifySessionToken(undefined, SECRET, NOW)).toBe(false);
    expect(await verifySessionToken("", SECRET, NOW)).toBe(false);
    expect(await verifySessionToken("nodot", SECRET, NOW)).toBe(false);
    expect(await verifySessionToken(".signature", SECRET, NOW)).toBe(false);
  });
});

describe("Session Revocation Scenarios", () => {
  it("revokes existing sessions immediately upon password rotation", async () => {
    const activeKeyOldPass = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    const token = await createSessionToken(activeKeyOldPass, NOW);

    // Active session verifies under current password
    expect(await verifySessionToken(token, activeKeyOldPass, NOW)).toBe(true);

    // Password rotated
    const activeKeyNewPass = await deriveSessionSigningSecret(SECRET, "brand-new-site-password", "1");

    // Old session token is rejected
    expect(await verifySessionToken(token, activeKeyNewPass, NOW)).toBe(false);
  });

  it("revokes existing sessions immediately upon session secret rotation", async () => {
    const activeKeyOldSecret = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    const token = await createSessionToken(activeKeyOldSecret, NOW);

    expect(await verifySessionToken(token, activeKeyOldSecret, NOW)).toBe(true);

    // Secret rotated
    const activeKeyNewSecret = await deriveSessionSigningSecret("brand-new-session-secret", PASSWORD, "1");
    expect(await verifySessionToken(token, activeKeyNewSecret, NOW)).toBe(false);
  });

  it("revokes all existing sessions globally upon session epoch increment", async () => {
    const keyEpoch1 = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");
    const sessionTokenUser1 = await createSessionToken(keyEpoch1, NOW);
    const sessionTokenUser2 = await createSessionToken(keyEpoch1, NOW + 5000);

    // Both sessions active
    expect(await verifySessionToken(sessionTokenUser1, keyEpoch1, NOW)).toBe(true);
    expect(await verifySessionToken(sessionTokenUser2, keyEpoch1, NOW)).toBe(true);

    // Admin bumps SESSION_EPOCH to 2
    const keyEpoch2 = await deriveSessionSigningSecret(SECRET, PASSWORD, "2");

    // All prior sessions are revoked globally
    expect(await verifySessionToken(sessionTokenUser1, keyEpoch2, NOW)).toBe(false);
    expect(await verifySessionToken(sessionTokenUser2, keyEpoch2, NOW)).toBe(false);
  });

  it("supports multiple concurrent browser sessions under the same valid epoch", async () => {
    const signingKey = await deriveSessionSigningSecret(SECRET, PASSWORD, "1");

    const sessionDesktop = await createSessionToken(signingKey, NOW);
    const sessionMobile = await createSessionToken(signingKey, NOW + 10_000);
    const sessionTablet = await createSessionToken(signingKey, NOW + 20_000);

    expect(await verifySessionToken(sessionDesktop, signingKey, NOW)).toBe(true);
    expect(await verifySessionToken(sessionMobile, signingKey, NOW)).toBe(true);
    expect(await verifySessionToken(sessionTablet, signingKey, NOW)).toBe(true);

    // Epoch bump invalidates all three concurrently
    const revokedKey = await deriveSessionSigningSecret(SECRET, PASSWORD, "2");
    expect(await verifySessionToken(sessionDesktop, revokedKey, NOW)).toBe(false);
    expect(await verifySessionToken(sessionMobile, revokedKey, NOW)).toBe(false);
    expect(await verifySessionToken(sessionTablet, revokedKey, NOW)).toBe(false);
  });
});

describe("Logout Route Handler — explicit cookie clearance", () => {
  it("POST /api/logout clears session cookie with maxAge 0", async () => {
    const request = new Request("https://deutschflow.app/api/logout", { method: "POST" });
    const response = await logoutPost(request);

    expect(response.status).toBe(200);
    const cookie = response.cookies.get(SESSION_COOKIE);
    expect(cookie).toBeDefined();
    expect(cookie?.value).toBe("");
    expect(cookie?.maxAge).toBe(0);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe("lax");
  });

  it("GET /api/logout rejects with 405 Method Not Allowed and does not clear cookies", async () => {
    const response = await logoutGet();

    expect(response.status).toBe(405);
    expect(response.headers.get("Allow")).toBe("POST");
    const body = await response.json();
    expect(body.error).toContain("Method Not Allowed");
    const cookie = response.cookies.get(SESSION_COOKIE);
    expect(cookie).toBeUndefined();
  });
  it("POST /api/logout rejects cross-origin request with 403 Forbidden", async () => {
    const request = new Request("https://deutschflow.app/api/logout", {
      method: "POST",
      headers: { origin: "https://evil-attacker.example" },
    });
    const response = await logoutPost(request);
    expect(response.status).toBe(403);
    const body = await response.json();
    expect(body.error).toContain("Forbidden");
  });

  it("POST /api/logout accepts matching origin", async () => {
    const request = new Request("https://deutschflow.app/api/logout", {
      method: "POST",
      headers: { origin: "https://deutschflow.app" },
    });
    const response = await logoutPost(request);
    expect(response.status).toBe(200);
  });
});

describe("configurable session lifetime (SESSION_MAX_AGE_SECONDS)", () => {
  it("defaults to 30 days when unset", async () => {
    const { getSessionMaxAgeSeconds, DEFAULT_SESSION_MAX_AGE_SECONDS } = await import("@/lib/auth/session");
    expect(getSessionMaxAgeSeconds()).toBe(DEFAULT_SESSION_MAX_AGE_SECONDS);
  });

  it("respects safe custom bounds (e.g. 7 days)", async () => {
    vi.stubEnv("SESSION_MAX_AGE_SECONDS", "604800");
    const { getSessionMaxAgeSeconds } = await import("@/lib/auth/session");
    expect(getSessionMaxAgeSeconds()).toBe(604800);
  });

  it("clamps out-of-bound values back to default", async () => {
    vi.stubEnv("SESSION_MAX_AGE_SECONDS", "10"); // too short (< 1h)
    const { getSessionMaxAgeSeconds, DEFAULT_SESSION_MAX_AGE_SECONDS } = await import("@/lib/auth/session");
    expect(getSessionMaxAgeSeconds()).toBe(DEFAULT_SESSION_MAX_AGE_SECONDS);

    vi.stubEnv("SESSION_MAX_AGE_SECONDS", "999999999"); // too long (> 90d)
    expect(getSessionMaxAgeSeconds()).toBe(DEFAULT_SESSION_MAX_AGE_SECONDS);
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

  it("rejects browser-normalized backslash and control-character redirects", () => {
    expect(safeRedirectTarget("/\\evil.example", FALLBACK)).toBe(FALLBACK);
    expect(safeRedirectTarget("/history\r\nLocation: https://evil.example", FALLBACK)).toBe(FALLBACK);
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

describe("passwordMatches", () => {
  it("accepts only the full password", async () => {
    expect(await passwordMatches("correct horse", "correct horse")).toBe(true);
    expect(await passwordMatches("correct", "correct horse")).toBe(false);
    expect(await passwordMatches("correct horse!", "correct horse")).toBe(false);
  });
});
