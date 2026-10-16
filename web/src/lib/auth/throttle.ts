/**
 * A brute-force brake on the access gate.
 *
 * The gate's primary strength is the entropy of one shared secret (SITE_PASSWORD),
 * and until this existed an attacker could submit guesses as fast as the deployment
 * would serve them — a server action is a plain POST with no cost to the caller.
 *
 * ARCHITECTURAL LIMITATIONS & SECURITY BOUNDARIES:
 * 1. Process-Local / Serverless Cold Starts:
 *    This tracker is an in-memory Map scoped to a single Node.js / edge runtime process.
 *    Serverless instances (e.g. Vercel, AWS Lambda, Cloud Run) do not share memory across
 *    containers. An attacker distributing requests across multiple container instances
 *    receives independent quotas, and any cold-start event resets local counters to zero.
 * 2. IP Rotation / Distributed Botnets:
 *    Guards key on client identifiers (typically IP addresses). An attacker rotating through
 *    large proxy pools or residential botnets can distribute attempts without accumulating
 *    sufficient failures on any single key to trigger progressive cooldown.
 * 3. All-Locked Eviction Nuance:
 *    The map is capped at MAX_TRACKED (10,000) entries to prevent memory exhaustion (DoS).
 *    Under saturation, unlocked/idle keys are evicted first. However, if an adversary
 *    saturates all 10,000 slots with actively locked keys and continues flooding, the engine
 *    must evict the earliest-expiring lockouts to avoid unbounded memory growth. This means
 *    under extreme distributed saturation, an attacker could theoretically cause an active
 *    lockout to be forgotten before its full cooldown elapses.
 *
 * For robust, distributed credential stuffing protection across serverless edges, this
 * throttle should be backed by a centralized store (e.g., Upstash Redis, Redis KV) or
 * managed edge firewall rate-limiting (e.g., Cloudflare WAF).
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
    if (now - entry.last > WINDOW_MS && entry.nextAllowed <= now) {
      attempts.delete(key);
    }
  }

  // Eviction under saturation: Never evict an actively locked-out caller
  // just because an attacker is flooding with dummy identifiers.
  if (attempts.size > MAX_TRACKED) {
    const targetSize = Math.floor(MAX_TRACKED * 0.8);
    const needed = attempts.size - targetSize;

    const unlocked: [string, Attempts][] = [];
    const locked: [string, Attempts][] = [];

    for (const item of attempts.entries()) {
      if (item[1].nextAllowed > now) {
        locked.push(item);
      } else {
        unlocked.push(item);
      }
    }

    // Evict oldest unlocked/cooldown-free entries first
    unlocked.sort((a, b) => a[1].last - b[1].last);
    let removed = 0;
    for (let i = 0; i < unlocked.length && removed < needed; i++) {
      attempts.delete(unlocked[i][0]);
      removed++;
    }

    // Only if all entries in the entire map are actively locked out,
    // evict the lockouts expiring soonest.
    if (removed < needed) {
      locked.sort((a, b) => a[1].nextAllowed - b[1].nextAllowed);
      for (let i = 0; i < locked.length && removed < needed; i++) {
        attempts.delete(locked[i][0]);
        removed++;
      }
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
