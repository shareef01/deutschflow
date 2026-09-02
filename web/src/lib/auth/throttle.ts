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

/** Failures allowed before the delay starts biting. */
const FREE_ATTEMPTS = 3;

/** Doubling from here: 4th failure waits 1s, 5th 2s, 6th 4s… */
const BASE_DELAY_MS = 1_000;
const MAX_DELAY_MS = 30_000;

/** A quiet period this long forgets the caller entirely. */
const WINDOW_MS = 15 * 60 * 1_000;

interface Attempts {
  count: number;
  last: number;
}

const attempts = new Map<string, Attempts>();

/** Bounded so a flood of distinct keys cannot grow the map without limit. */
const MAX_TRACKED = 10_000;

function prune(now: number): void {
  for (const [key, entry] of attempts) {
    if (now - entry.last > WINDOW_MS) attempts.delete(key);
  }
  if (attempts.size > MAX_TRACKED) attempts.clear();
}

/**
 * How long this caller must wait before their next guess is worth making.
 *
 * @returns milliseconds to sleep, 0 when they are still within the free allowance.
 */
export function delayForNextAttempt(key: string, now: number = Date.now()): number {
  prune(now);
  const entry = attempts.get(key);
  if (!entry || now - entry.last > WINDOW_MS) return 0;
  if (entry.count < FREE_ATTEMPTS) return 0;
  const exponent = entry.count - FREE_ATTEMPTS;
  return Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** exponent);
}

export function recordFailure(key: string, now: number = Date.now()): void {
  prune(now);
  const entry = attempts.get(key);
  attempts.set(
    key,
    entry && now - entry.last <= WINDOW_MS
      ? { count: entry.count + 1, last: now }
      : { count: 1, last: now }
  );
}

/** A correct password clears the record: the caller has proved who they are. */
export function recordSuccess(key: string): void {
  attempts.delete(key);
}

/** Test seam — the map is module state and would otherwise leak between cases. */
export function resetThrottle(): void {
  attempts.clear();
}
