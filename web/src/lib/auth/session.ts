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

/** Default 30 days. Configurable via SESSION_MAX_AGE_SECONDS between 1 hour and 90 days. */
export const DEFAULT_SESSION_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;
export const MIN_SESSION_MAX_AGE_SECONDS = 3600; // 1 hour
export const MAX_SESSION_MAX_AGE_SECONDS = 60 * 60 * 24 * 90; // 90 days

export function getSessionMaxAgeSeconds(): number {
  const raw = process.env.SESSION_MAX_AGE_SECONDS?.trim();
  if (!raw) return DEFAULT_SESSION_MAX_AGE_SECONDS;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed) || parsed < MIN_SESSION_MAX_AGE_SECONDS || parsed > MAX_SESSION_MAX_AGE_SECONDS) {
    return DEFAULT_SESSION_MAX_AGE_SECONDS;
  }
  return parsed;
}

export const SESSION_MAX_AGE_SECONDS = DEFAULT_SESSION_MAX_AGE_SECONDS;

/** HMAC keys need entropy, not merely a non-empty configuration value. */
export const MIN_SESSION_SECRET_BYTES = 32;

const encoder = new TextEncoder();

/** SITE_PASSWORD, tolerating an env var quoted by the shell or the host UI. */
export function sitePassword(): string | null {
  const raw = process.env.SITE_PASSWORD?.trim();
  if (!raw) return null;
  return raw.replace(/^["']|["']$/g, "");
}

/**
 * Independent high-entropy key used only for session authentication.
 *
 * Refuse weak configuration rather than issuing tokens that are trivial to
 * forge. Byte length is used so the requirement holds for non-ASCII secrets as
 * well as the base64 values recommended in the deployment guide.
 */
export function sessionSecret(): string | null {
  const raw = process.env.SESSION_SECRET?.trim();
  if (!raw) return null;
  const secret = raw.replace(/^["']|["']$/g, "");
  return encoder.encode(secret).byteLength >= MIN_SESSION_SECRET_BYTES ? secret : null;
}

/**
 * Global session epoch/version. Incrementing SESSION_EPOCH immediately
 * invalidates every active session cookie across all clients without needing to
 * change the site password or session secret.
 */
export function sessionEpoch(): string {
  const raw = process.env.SESSION_EPOCH?.trim();
  if (!raw) return "1";
  return raw.replace(/^["']|["']$/g, "");
}

/**
 * Derives the active session signing key using HMAC-SHA256 over:
 * - SESSION_SECRET (master session signing seed)
 * - SITE_PASSWORD (access password)
 * - SESSION_EPOCH (explicit revocation version)
 *
 * This guarantees:
 * 1. Rotating SITE_PASSWORD immediately revokes all prior sessions.
 * 2. Rotating SESSION_SECRET immediately revokes all prior sessions.
 * 3. Bumping SESSION_EPOCH immediately revokes all prior sessions.
 */
export async function deriveSessionSigningSecret(
  secret: string,
  password: string,
  epoch: string = sessionEpoch()
): Promise<string> {
  const material = `${secret}:${password}:${epoch}`;
  const hmacKey = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign("HMAC", hmacKey, encoder.encode(material));
  return toBase64Url(signature);
}

/**
 * Returns the derived active signing secret combining SESSION_SECRET, SITE_PASSWORD,
 * and SESSION_EPOCH, or null if unconfigured.
 */
export async function getActiveSigningSecret(): Promise<string | null> {
  const password = sitePassword();
  const secret = sessionSecret();
  if (!password || !secret) return null;
  return deriveSessionSigningSecret(secret, password, sessionEpoch());
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

/** A token good for [maxAgeSeconds] from now. */
export async function createSessionToken(
  secret: string,
  now: number = Date.now(),
  maxAgeSeconds: number = getSessionMaxAgeSeconds()
): Promise<string> {
  const expiresAt = now + maxAgeSeconds * 1000;
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
