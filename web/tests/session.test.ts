import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createSessionToken,
  deriveSessionSigningSecret,
  MIN_SESSION_SECRET_BYTES,
  MIN_SITE_PASSWORD_CHARS,
  passwordMatches,
  safeRedirectTarget,
  SESSION_COOKIE,
  sessionSecret,
  sitePasswordWarning,
  SESSION_MAX_AGE_SECONDS,
  verifySessionToken,
} from "@/lib/auth/session";
import { GET as logoutGet, POST as logoutPost } from "@/app/api/logout/route";
import { POST as loginPost } from "@/app/api/login/route";
import { proxy } from "@/proxy";
import { NextRequest } from "next/server";

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
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("public production authentication", () => {
  function configureProduction() {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("VERCEL", "");
    vi.stubEnv("DEUTSCHFLOW_LOCAL_AUTH", "");
    vi.stubEnv("SITE_PASSWORD", PASSWORD);
    vi.stubEnv("SESSION_SECRET", "test-session-secret-at-least-32-bytes");
    vi.stubEnv("UPSTASH_REDIS_REST_URL", "https://redis.example.invalid");
    vi.stubEnv("UPSTASH_REDIS_REST_TOKEN", "fixture");
  }

  it("rejects untrusted identity before charging a shared login bucket", async () => {
    configureProduction();
    vi.stubEnv("TRUSTED_PROXY_HEADER", "");
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    const request = new NextRequest("https://site.test/api/login", {
      method: "POST",
      headers: { "x-real-ip": "192.0.2.1" },
      body: new URLSearchParams({ password: PASSWORD }),
    });
    expect((await loginPost(request)).status).toBe(503);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("rejects sessions and leaves the cookie intact when Redis is down", async () => {
    configureProduction();
    const signingKey = await deriveSessionSigningSecret(process.env.SESSION_SECRET!, PASSWORD);
    const token = await createSessionToken(signingKey);
    const cookie = `${SESSION_COOKIE}=${token}`;
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 503 })));
    const protectedPage = new NextRequest("https://site.test/study", { headers: { cookie } });
    const logout = new Request("https://site.test/api/logout", {
      method: "POST", headers: { cookie, origin: "https://site.test" },
    });
    expect((await proxy(protectedPage)).status).toBe(503);
    const response = await logoutPost(logout);
    expect(response.status).toBe(503);
    expect(response.headers.has("set-cookie")).toBe(false);
  });

  it("checks revocations through the primary and writes them with the token expiry", async () => {
    configureProduction();
    vi.stubEnv("UPSTASH_REDIS_REST_URL", "");
    vi.stubEnv("UPSTASH_REDIS_REST_TOKEN", "");
    vi.stubEnv("REDIS_REST_URL", "");
    vi.stubEnv("REDIS_REST_TOKEN", "");
    vi.stubEnv("KV_REST_API_URL", "https://redis.example.invalid");
    vi.stubEnv("KV_REST_API_TOKEN", "vercel-fixture");
    const signingKey = await deriveSessionSigningSecret(process.env.SESSION_SECRET!, PASSWORD);
    const token = await createSessionToken(signingKey);
    const cookie = `${SESSION_COOKIE}=${token}`;
    let stored: string | null = null;
    const fetch = vi.fn(async (_url: string, options: RequestInit) => {
      expect(new Headers(options.headers).get("Authorization")).toBe("Bearer vercel-fixture");
      const [, key, value, ...args] = JSON.parse(String(options.body));
      expect(key).toMatch(/^deutschflow:revoked:[a-f0-9]{64}$/);
      expect(value).toBe("1");
      if (args[0] === "PXAT") {
        expect(args[1]).toBe(Number(token.split(".")[0]));
        stored = value;
        return Response.json({ result: "OK" });
      }
      expect(args).toEqual(["XX", "KEEPTTL", "GET"]);
      return Response.json({ result: stored });
    });
    vi.stubGlobal("fetch", fetch);
    const request = new NextRequest("https://site.test/study", { headers: { cookie } });
    expect((await proxy(request)).status).toBe(200);
    expect((await logoutPost(new Request("https://site.test/api/logout", {
      method: "POST", headers: { cookie },
    }))).status).toBe(200);
    expect((await proxy(request)).status).toBe(307);
    expect(fetch).toHaveBeenCalledTimes(3);
  });
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

  it("POST /api/logout accepts forwarded public origin behind configured trusted proxy (AUD-04)", async () => {
    vi.stubEnv("TRUSTED_PROXY_HEADER", "X-Real-IP");
    const request = new NextRequest("http://127.0.0.1:3000/api/logout", {
      method: "POST",
      headers: {
        origin: "https://deutschflow.example",
        "x-forwarded-proto": "https",
        "x-forwarded-host": "deutschflow.example",
      },
    });
    expect((await logoutPost(request)).status).toBe(200);

    // Mismatched origin is still rejected even behind a trusted proxy
    const evil = new NextRequest("http://127.0.0.1:3000/api/logout", {
      method: "POST",
      headers: {
        origin: "https://evil.example",
        "x-forwarded-proto": "https",
        "x-forwarded-host": "deutschflow.example",
      },
    });
    expect((await logoutPost(evil)).status).toBe(403);
  });

  it("POST /api/logout rejects spoofed x-forwarded-host when no trusted proxy is configured (AUD-04)", async () => {
    vi.stubEnv("VERCEL", "");
    vi.stubEnv("TRUSTED_PROXY_HEADER", "");
    const request = new Request("http://127.0.0.1:3000/api/logout", {
      method: "POST",
      headers: {
        origin: "https://evil.example",
        "x-forwarded-proto": "https",
        "x-forwarded-host": "evil.example",
      },
    });
    expect((await logoutPost(request)).status).toBe(403);
  });

  it("POST /api/login rejects cross-origin requests and oversized payloads (AUD-06)", async () => {
    vi.stubEnv("SITE_PASSWORD", PASSWORD);
    vi.stubEnv("SESSION_SECRET", "test-session-secret-at-least-32-bytes");
    const crossOrigin = new NextRequest("https://deutschflow.app/api/login", {
      method: "POST",
      headers: { origin: "https://evil.example" },
      body: new URLSearchParams({ password: PASSWORD }),
    });
    expect((await loginPost(crossOrigin)).status).toBe(403);

    const oversized = new NextRequest("https://deutschflow.app/api/login", {
      method: "POST",
      headers: {
        origin: "https://deutschflow.app",
        "content-length": "65536",
      },
      body: new URLSearchParams({ password: PASSWORD }),
    });
    expect((await loginPost(oversized)).status).toBe(413);
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

describe("SITE_PASSWORD strength warning (W-05)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it("warns below MIN_SITE_PASSWORD_CHARS and names the length", () => {
    const warning = sitePasswordWarning("hunter2");
    expect(warning).toContain("7 characters");
    expect(warning).toContain(String(MIN_SITE_PASSWORD_CHARS));
  });

  it("is silent at the minimum, and when no password is set (the 503 path covers that)", () => {
    expect(sitePasswordWarning("a".repeat(MIN_SITE_PASSWORD_CHARS))).toBeNull();
    expect(sitePasswordWarning(null)).toBeNull();
  });

  it("counts characters, not UTF-16 units or bytes", () => {
    expect(sitePasswordWarning("ä".repeat(MIN_SITE_PASSWORD_CHARS))).toBeNull();
    expect(sitePasswordWarning("😀".repeat(MIN_SITE_PASSWORD_CHARS - 1))).toContain("15 characters");
  });

  it("reads the env var by default, quotes stripped", () => {
    vi.stubEnv("SITE_PASSWORD", '"short"');
    expect(sitePasswordWarning()).toContain("5 characters");
  });

  it("is logged once at server start, on the Node runtime only", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const { register } = await import("@/instrumentation");
    vi.stubEnv("SITE_PASSWORD", "short");

    vi.stubEnv("NEXT_RUNTIME", "edge");
    await register();
    expect(warn).not.toHaveBeenCalled();

    vi.stubEnv("NEXT_RUNTIME", "nodejs");
    await register();
    expect(warn).toHaveBeenCalledTimes(1);
    expect(String(warn.mock.calls[0][0])).toContain("SITE_PASSWORD");
  });
});
