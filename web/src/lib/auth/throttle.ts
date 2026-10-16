/**
 * A brute-force brake on the access gate.
 *
 * The gate's primary strength is the entropy of one shared secret (SITE_PASSWORD),
 * and until this existed an attacker could submit guesses as fast as the deployment
 * would serve them — a server action is a plain POST with no cost to the caller.
 *
 * ARCHITECTURAL LIMITATIONS & SECURITY BOUNDARIES:
 * 1. Process-Local / Serverless Cold Starts:
 *    The default tracker is an in-memory Map scoped to a single Node.js / edge runtime process.
 *    Serverless instances (e.g. Vercel, AWS Lambda, Cloud Run) do not share memory across
 *    containers. An attacker distributing requests across multiple container instances
 *    receives independent quotas, and any cold-start event resets local counters to zero.
 * 2. Distributed Store Integration:
 *    When configured with UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN (or REDIS_REST_URL),
 *    the throttle automatically routes rate-limiting state to a shared atomic store across
 *    all serverless edge instances.
 *    If the distributed store becomes temporarily unavailable or times out, the system adopts
 *    an explicit fail-secure policy: it falls back to the in-memory throttle to ensure legitimate
 *    logins are not completely broken while maintaining local rate-limiting on every node.
 * 3. IP Rotation / Distributed Botnets:
 *    Guards key on client identifiers (typically IP addresses). An attacker rotating through
 *    large proxy pools or residential botnets can distribute attempts without accumulating
 *    sufficient failures on any single key to trigger progressive cooldown.
 * 4. All-Locked Eviction Nuance:
 *    The local map is capped at MAX_TRACKED (10,000) entries to prevent memory exhaustion (DoS).
 *    Under saturation, unlocked/idle keys are evicted first.
 */

/** Failures allowed before attempts receive an immediate retry response. */
export const FREE_ATTEMPTS = 3;

/** Doubling cooldown: 4th failure retries after 1s, 5th after 2s, 6th after 4s… */
export const BASE_DELAY_MS = 1_000;
export const MAX_DELAY_MS = 30_000;

/** A quiet period this long forgets the caller entirely (15 minutes). */
export const WINDOW_MS = 15 * 60 * 1_000;
export const WINDOW_SECONDS = 15 * 60;

export interface Attempts {
  count: number;
  last: number;
  nextAllowed: number;
}

export function normalizeKey(key: unknown): string {
  if (typeof key !== "string" || !key.trim()) return "unknown";
  return key.trim().slice(0, 128);
}

export function calculateCooldown(count: number): number {
  if (count < FREE_ATTEMPTS) return 0;
  return Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** (count - FREE_ATTEMPTS));
}

export interface ThrottleBackend {
  get(key: string, now: number): Promise<Attempts | null>;
  set(key: string, entry: Attempts, ttlSeconds: number): Promise<void>;
  delete(key: string): Promise<void>;
  reset?(): Promise<void>;
  getTrackedCount?(): number;
}

/** Bounded in-memory store so a flood of distinct keys cannot grow the map without limit. */
export const MAX_TRACKED = 10_000;

export class InMemoryThrottleBackend implements ThrottleBackend {
  private attempts = new Map<string, Attempts>();

  private prune(now: number): void {
    for (const [key, entry] of this.attempts) {
      if (now - entry.last > WINDOW_MS && entry.nextAllowed <= now) {
        this.attempts.delete(key);
      }
    }

    if (this.attempts.size > MAX_TRACKED) {
      const targetSize = Math.floor(MAX_TRACKED * 0.8);
      const needed = this.attempts.size - targetSize;

      const unlocked: [string, Attempts][] = [];
      const locked: [string, Attempts][] = [];

      for (const item of this.attempts.entries()) {
        if (item[1].nextAllowed > now) {
          locked.push(item);
        } else {
          unlocked.push(item);
        }
      }

      unlocked.sort((a, b) => a[1].last - b[1].last);
      let removed = 0;
      for (let i = 0; i < unlocked.length && removed < needed; i++) {
        this.attempts.delete(unlocked[i][0]);
        removed++;
      }

      if (removed < needed) {
        locked.sort((a, b) => a[1].nextAllowed - b[1].nextAllowed);
        for (let i = 0; i < locked.length && removed < needed; i++) {
          this.attempts.delete(locked[i][0]);
          removed++;
        }
      }
    }
  }

  async get(rawKey: string, now: number): Promise<Attempts | null> {
    const key = normalizeKey(rawKey);
    this.prune(now);
    const entry = this.attempts.get(key);
    if (!entry || now - entry.last > WINDOW_MS) return null;
    return entry;
  }

  async set(rawKey: string, entry: Attempts): Promise<void> {
    const key = normalizeKey(rawKey);
    this.attempts.set(key, entry);
  }

  async delete(rawKey: string): Promise<void> {
    const key = normalizeKey(rawKey);
    this.attempts.delete(key);
  }

  async reset(): Promise<void> {
    this.attempts.clear();
  }

  getTrackedCount(): number {
    return this.attempts.size;
  }

  // Synchronous helpers for backwards compatibility
  syncGet(rawKey: string, now: number): Attempts | null {
    const key = normalizeKey(rawKey);
    this.prune(now);
    const entry = this.attempts.get(key);
    if (!entry || now - entry.last > WINDOW_MS) return null;
    return entry;
  }

  syncSet(rawKey: string, entry: Attempts): void {
    const key = normalizeKey(rawKey);
    this.attempts.set(key, entry);
  }

  syncDelete(rawKey: string): void {
    const key = normalizeKey(rawKey);
    this.attempts.delete(key);
  }
}

/**
 * Distributed rate-limiting store using the Upstash Redis REST API.
 * Uses HTTP fetch with bounded timeout, completely compatible with edge and serverless runtimes.
 */
export class UpstashRedisThrottleBackend implements ThrottleBackend {
  constructor(
    private url: string,
    private token: string,
    private fallback: ThrottleBackend = new InMemoryThrottleBackend(),
    private timeoutMs: number = 3_000
  ) {}

  private key(rawKey: string): string {
    return `df:throttle:${normalizeKey(rawKey)}`;
  }

  private async fetchWithTimeout(path: string, options: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await fetch(`${this.url.replace(/\/+$/, "")}${path}`, {
        ...options,
        signal: controller.signal,
        headers: {
          Authorization: `Bearer ${this.token}`,
          "Content-Type": "application/json",
          ...(options.headers || {}),
        },
      });
    } finally {
      clearTimeout(timeout);
    }
  }

  async get(rawKey: string, now: number): Promise<Attempts | null> {
    try {
      const res = await this.fetchWithTimeout(`/get/${encodeURIComponent(this.key(rawKey))}`, {
        method: "GET",
      });
      if (!res.ok) throw new Error(`Upstash returned HTTP ${res.status}`);
      const data = await res.json();
      if (!data || typeof data.result !== "string") return null;
      const parsed = JSON.parse(data.result) as Attempts;
      if (!parsed || typeof parsed.count !== "number" || typeof parsed.last !== "number") return null;
      if (now - parsed.last > WINDOW_MS) return null;
      return parsed;
    } catch {
      // Bounded fallback to memory backend
      return this.fallback.get(rawKey, now);
    }
  }

  async set(rawKey: string, entry: Attempts, ttlSeconds: number): Promise<void> {
    try {
      const res = await this.fetchWithTimeout(
        `/set/${encodeURIComponent(this.key(rawKey))}/${encodeURIComponent(JSON.stringify(entry))}?ex=${ttlSeconds}`,
        { method: "POST" }
      );
      if (!res.ok) throw new Error(`Upstash set returned HTTP ${res.status}`);
    } catch {
      await this.fallback.set(rawKey, entry, ttlSeconds);
    }
  }

  async delete(rawKey: string): Promise<void> {
    try {
      await this.fetchWithTimeout(`/del/${encodeURIComponent(this.key(rawKey))}`, {
        method: "POST",
      });
    } catch {
      await this.fallback.delete(rawKey);
    }
  }

  async reset(): Promise<void> {
    if (this.fallback.reset) await this.fallback.reset();
  }
}

// Module-level default in-memory store
const defaultMemoryBackend = new InMemoryThrottleBackend();
let customBackend: ThrottleBackend | null = null;

export function getActiveThrottleBackend(): ThrottleBackend {
  if (customBackend) return customBackend;
  const url = process.env.UPSTASH_REDIS_REST_URL?.trim() || process.env.REDIS_REST_URL?.trim();
  const token = process.env.UPSTASH_REDIS_REST_TOKEN?.trim() || process.env.REDIS_REST_TOKEN?.trim();
  if (url && token) {
    return new UpstashRedisThrottleBackend(url, token, defaultMemoryBackend);
  }
  return defaultMemoryBackend;
}

export function setCustomThrottleBackend(backend: ThrottleBackend | null): void {
  customBackend = backend;
}

// ============================================================================
// Asynchronous Distributed Rate-Limiting API
// ============================================================================

/**
 * Checks how long this caller must wait before their next guess is allowed.
 * Uses the distributed store if configured, otherwise the in-memory backend.
 */
export async function getThrottleDelay(rawKey: string, now: number = Date.now()): Promise<number> {
  const backend = getActiveThrottleBackend();
  const entry = await backend.get(rawKey, now);
  if (!entry || now - entry.last > WINDOW_MS) return 0;
  return Math.max(0, entry.nextAllowed - now);
}

/**
 * Records an authentication failure. Increments failure count, calculates progressive cooldown,
 * and updates the backend with a TTL matching WINDOW_SECONDS.
 */
export async function recordThrottleFailure(rawKey: string, now: number = Date.now()): Promise<number> {
  const backend = getActiveThrottleBackend();
  const entry = await backend.get(rawKey, now);
  const count = entry && now - entry.last <= WINDOW_MS ? entry.count + 1 : 1;
  const cooldown = calculateCooldown(count);
  const updated: Attempts = { count, last: now, nextAllowed: now + cooldown };
  await backend.set(rawKey, updated, WINDOW_SECONDS);
  return cooldown;
}

/**
 * Records a successful login, resetting rate-limiting counters for this key.
 */
export async function recordThrottleSuccess(rawKey: string): Promise<void> {
  const backend = getActiveThrottleBackend();
  await backend.delete(rawKey);
}

// ============================================================================
// Synchronous API (Backwards compatibility with existing tests & code)
// ============================================================================

export function delayForNextAttempt(rawKey: string, now: number = Date.now()): number {
  const entry = defaultMemoryBackend.syncGet(rawKey, now);
  if (!entry || now - entry.last > WINDOW_MS) return 0;
  return Math.max(0, entry.nextAllowed - now);
}

export function recordFailure(rawKey: string, now: number = Date.now()): void {
  const entry = defaultMemoryBackend.syncGet(rawKey, now);
  const count = entry && now - entry.last <= WINDOW_MS ? entry.count + 1 : 1;
  const cooldown = calculateCooldown(count);
  defaultMemoryBackend.syncSet(rawKey, { count, last: now, nextAllowed: now + cooldown });
}

export function recordSuccess(rawKey: string): void {
  defaultMemoryBackend.syncDelete(rawKey);
}

export function getTrackedCount(): number {
  return defaultMemoryBackend.getTrackedCount();
}

export function resetThrottle(): void {
  defaultMemoryBackend.reset();
  customBackend = null;
}
