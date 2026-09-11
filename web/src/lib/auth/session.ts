/**
 * The access-gate session token.
 *
 * The gate used to admit any request carrying the literal cookie
 * `df_access=granted`, which meant `curl -b "df_access=granted"` was through the
 * door without ever seeing the password. `httpOnly` does not help with that: it
 * stops page scripts *reading* the cookie, and has no bearing on what a client
 * chooses to send.
 *
 * A token is `<expiry>.<hmac>`, signed with an independent SESSION_SECRET. The
 * password is only an authentication credential: changing it does not silently
 * repurpose a human-entered value as cryptographic key material. Rotating
 * SESSION_SECRET invalidates every outstanding session.
 *
 * Web Crypto only — this runs inside the server action and the network proxy,
 * and keeps the two on one implementation rather than diverging per runtime.
 */

export const SESSION_COOKIE = "df_access";

/** Thirty days, matching the cookie's own lifetime. */
export const SESSION_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

const encoder = new TextEncoder();

/** SITE_PASSWORD, tolerating an env var quoted by the shell or the host UI. */
export function sitePassword(): string | null {
  const raw = process.env.SITE_PASSWORD?.trim();
  if (!raw) return null;
  return raw.replace(/^["']|["']$/g, "");
}

/** Independent high-entropy key used only for session authentication. */
export function sessionSecret(): string | null {
  const raw = process.env.SESSION_SECRET?.trim();
  if (!raw) return null;
  return raw.replace(/^["']|["']$/g, "");
}

async function key(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
}

function toBase64Url(bytes: ArrayBuffer): string {
  const binary = String.fromCharCode(...new Uint8Array(bytes));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function digest(value: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

/** Fixed-length digest comparison for the user-entered access password. */
export async function passwordMatches(candidate: string, expected: string): Promise<boolean> {
  const [candidateDigest, expectedDigest] = await Promise.all([
    digest(candidate),
    digest(expected),
  ]);
  let diff = 0;
  for (let i = 0; i < candidateDigest.length; i++) {
    diff |= candidateDigest[i] ^ expectedDigest[i];
  }
  return diff === 0;
}

async function sign(payload: string, secret: string): Promise<string> {
  return toBase64Url(await crypto.subtle.sign("HMAC", await key(secret), encoder.encode(payload)));
}

/** A token good for [SESSION_MAX_AGE_SECONDS] from now. */
export async function createSessionToken(secret: string, now: number = Date.now()): Promise<string> {
  const expiresAt = now + SESSION_MAX_AGE_SECONDS * 1000;
  return `${expiresAt}.${await sign(String(expiresAt), secret)}`;
}

/**
 * True when [token] carries a signature this secret produced and has not
 * expired. Comparison is length-safe and constant-time over the digest, so a
 * caller cannot narrow in on a valid signature byte by byte.
 */
export async function verifySessionToken(
  token: string | undefined,
  secret: string,
  now: number = Date.now()
): Promise<boolean> {
  if (!token) return false;

  const separator = token.lastIndexOf(".");
  if (separator <= 0) return false;

  const expiresAt = Number(token.slice(0, separator));
  if (!Number.isFinite(expiresAt) || expiresAt <= now) return false;

  return timingSafeEqual(token.slice(separator + 1), await sign(String(expiresAt), secret));
}

export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * A post-login destination that stays on this site.
 *
 * `from` reaches the action through a hidden field fed by `?from=`, so an
 * unchecked value made a successful login redirect anywhere — a phishing
 * primitive borrowing this domain's credibility. A protocol-relative `//host`
 * is rejected alongside absolute URLs; browsers treat it as off-site too.
 */
export function safeRedirectTarget(from: unknown, fallback = "/transcript"): string {
  if (typeof from !== "string") return fallback;
  if (!from.startsWith("/") || /[\\\r\n]/.test(from)) return fallback;
  try {
    const base = new URL("https://deutschflow.invalid");
    const target = new URL(from, base);
    if (target.origin !== base.origin || target.pathname.startsWith("/login")) return fallback;
    return `${target.pathname}${target.search}${target.hash}`;
  } catch {
    return fallback;
  }
}
