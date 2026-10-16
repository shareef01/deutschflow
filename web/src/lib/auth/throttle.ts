/**
 * A brute-force brake on the access gate.
 *
 * The gate's whole strength is the entropy of one shared secret, and until this
 * existed an attacker could submit guesses as fast as the deployment would serve
 * them — a server action is a plain POST with no cost to the caller.
 *
 * Deliberately in-memory, and the limits of that are worth stating rather than
 * hiding: serverless instances do not share this map, so an attacker who reaches
 * several instances gets several allowances, and a cold start resets it. It stops
 * naive scripted guessing, which is the realistic threat against a personal
 * instance; it is not a substitute for a high-entropy SITE_PASSWORD, which is the
 * control doing most of the work. A durable counter needs KV or Upstash — a
 * dependency decision, not a code one.
 */

/** Failures allowed before attempts receive an immediate retry response. */
const FREE_ATTEMPTS = 3;

/** Doubling cooldown: 4th failure retries after 1s, 5th after 2s, 6th after 4s… */
const BASE_DELAY_MS = 1_000;
const MAX_DELAY_MS = 30_000;

/** A quiet period this long forgets the caller entirely. */
const WINDOW_MS = 15 * 60 * 1_000;

interface Attempts {
  count: number;
  last: number;
  nextAllowed: number;
}

const attempts = new Map<string, Attempts>();

/** Bounded so a flood of distinct keys cannot grow the map without limit. */
export const MAX_TRACKED = 10_000;

function normalizeKey(key: unknown): string {
  if (typeof key !== "string" || !key.trim()) return "unknown";
  return key.trim().slice(0, 128);
}

function prune(now: number): void {
  for (const [key, entry] of attempts) {
    if (now - entry.last > WINDOW_MS) attempts.delete(key);
  }
  // LRU eviction: evict oldest 20% entries instead of full clear(),
  // which would otherwise allow an attacker to wipe everyone's throttling
  // by flooding with distinct fake keys.
  if (attempts.size > MAX_TRACKED) {
    const toRemove = attempts.size - Math.floor(MAX_TRACKED * 0.8);
    const sorted = Array.from(attempts.entries()).sort((a, b) => a[1].last - b[1].last);
    for (let i = 0; i < toRemove && i < sorted.length; i++) {
      attempts.delete(sorted[i][0]);
    }
  }
}

/**
 * How long this caller must wait before their next guess is worth making.
 *
 * @returns milliseconds until another attempt is accepted, or 0 within the allowance.
 */
export function delayForNextAttempt(rawKey: string, now: number = Date.now()): number {
  const key = normalizeKey(rawKey);
  prune(now);
  const entry = attempts.get(key);
  if (!entry || now - entry.last > WINDOW_MS) return 0;
  return Math.max(0, entry.nextAllowed - now);
}

export function recordFailure(rawKey: string, now: number = Date.now()): void {
  const key = normalizeKey(rawKey);
  prune(now);
  const entry = attempts.get(key);
  const count = entry && now - entry.last <= WINDOW_MS ? entry.count + 1 : 1;
  const cooldown = count >= FREE_ATTEMPTS
    ? Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** (count - FREE_ATTEMPTS))
    : 0;
  attempts.set(key, { count, last: now, nextAllowed: now + cooldown });
}

/** A correct password clears the record: the caller has proved who they are. */
export function recordSuccess(rawKey: string): void {
  const key = normalizeKey(rawKey);
  attempts.delete(key);
}

/** Returns the number of currently tracked rate-limiting entries. */
export function getTrackedCount(): number {
  return attempts.size;
}

/** Test seam — the map is module state and would otherwise leak between cases. */
export function resetThrottle(): void {
  attempts.clear();
}
