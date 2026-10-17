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

export type ThrottleFailurePolicy = "fail-secure-fallback" | "fail-closed";

export class ThrottleStoreError extends Error {
  constructor(message: string, readonly cause?: unknown) {
    super(message);
    this.name = "ThrottleStoreError";
  }
}

export interface AdmissionResult {
  allowed: boolean;
  retryAfterMs: number;
  count: number;
  cooldown?: number;
}

export interface ThrottleBackend {
  get(key: string, now: number): Promise<Attempts | null>;
  set(key: string, entry: Attempts, ttlSeconds: number): Promise<void>;
  delete(key: string): Promise<void>;
  recordFailure?(key: string, now: number): Promise<{ count: number; cooldown: number }>;
  admit?(key: string, now: number): Promise<AdmissionResult>;
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

  async recordFailure(rawKey: string, now: number): Promise<{ count: number; cooldown: number }> {
    const key = normalizeKey(rawKey);
    this.prune(now);
    const entry = this.attempts.get(key);
    const count = entry && now - entry.last <= WINDOW_MS ? entry.count + 1 : 1;
    const cooldown = calculateCooldown(count);
    this.attempts.set(key, { count, last: now, nextAllowed: now + cooldown });
    return { count, cooldown };
  }

  async admit(rawKey: string, now: number): Promise<AdmissionResult> {
    const key = normalizeKey(rawKey);
    this.prune(now);
    const entry = this.attempts.get(key);

    // 1. If currently in lockout, deny attempt without advancing counter or extending lockout
    if (entry && now - entry.last <= WINDOW_MS && entry.nextAllowed > now) {
      return {
        allowed: false,
        retryAfterMs: entry.nextAllowed - now,
        count: entry.count,
      };
    }

    // 2. Not in lockout (or window expired): allow this attempt
    const newCount = entry && now - entry.last <= WINDOW_MS ? entry.count + 1 : 1;
    const cooldown = calculateCooldown(newCount);
    const nextAllowed = cooldown > 0 ? now + cooldown : 0;
    this.attempts.set(key, { count: newCount, last: now, nextAllowed });

    return {
      allowed: true,
      retryAfterMs: 0,
      count: newCount,
      cooldown,
    };
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
 * Employs atomic /multi-exec transaction blocks and atomic Lua scripts via /eval to prevent
 * concurrency races and ensure that concurrent requests cannot bypass lockout thresholds.
 */
export class UpstashRedisThrottleBackend implements ThrottleBackend {
  constructor(
    private url: string,
    private token: string,
    private fallback: ThrottleBackend = new InMemoryThrottleBackend(),
    private timeoutMs: number = 3_000,
    private failurePolicy: ThrottleFailurePolicy = (process.env.THROTTLE_FAILURE_POLICY as ThrottleFailurePolicy) || "fail-secure-fallback"
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

  private validateMultiExecResponse(data: unknown, expectedCount = 3): { result?: unknown; error?: string }[] {
    if (!Array.isArray(data)) {
      if (data && typeof data === "object" && "error" in data) {
        throw new Error(`Upstash error: ${(data as { error: string }).error}`);
      }
      throw new Error("Malformed Upstash response: expected array");
    }
    if (data.length !== expectedCount) {
      throw new Error(`Malformed Upstash response: expected ${expectedCount} results, got ${data.length}`);
    }
    for (let i = 0; i < data.length; i++) {
      const item = data[i];
      if (!item || typeof item !== "object") {
        throw new Error(`Malformed Upstash command result at index ${i}`);
      }
      if ((item as { error?: string }).error) {
        throw new Error(`Upstash command error at index ${i}: ${(item as { error?: string }).error}`);
      }
    }
    return data as { result?: unknown; error?: string }[];
  }

  async get(rawKey: string, now: number): Promise<Attempts | null> {
    const k = this.key(rawKey);
    const countKey = `${k}:count`;
    const lastKey = `${k}:last`;
    const nextAllowedKey = `${k}:nextAllowed`;
    try {
      const res = await this.fetchWithTimeout("/multi-exec", {
        method: "POST",
        body: JSON.stringify([
          ["GET", countKey],
          ["GET", lastKey],
          ["GET", nextAllowedKey],
        ]),
      });
      if (!res.ok) throw new Error(`Upstash /multi-exec returned HTTP ${res.status}`);
      const rawData = await res.json();
      const data = this.validateMultiExecResponse(rawData, 3);
      const rawCount = data[0]?.result;
      const rawLast = data[1]?.result;
      const rawNextAllowed = data[2]?.result;
      if (rawCount === null || rawCount === undefined || rawLast === null || rawLast === undefined) {
        return null;
      }
      const count = Number(rawCount);
      const last = Number(rawLast);
      if (Number.isNaN(count) || Number.isNaN(last)) return null;
      if (now - last > WINDOW_MS) return null;
      const nextAllowed = rawNextAllowed !== null && rawNextAllowed !== undefined && !Number.isNaN(Number(rawNextAllowed))
        ? Number(rawNextAllowed)
        : last + calculateCooldown(count);
      return { count, last, nextAllowed };
    } catch (err) {
      if (this.failurePolicy === "fail-closed") {
        throw new ThrottleStoreError("Distributed rate limiter store unavailable (fail-closed policy in effect)", err);
      }
      return this.fallback.get(rawKey, now);
    }
  }

  async recordFailure(rawKey: string, now: number): Promise<{ count: number; cooldown: number }> {
    const k = this.key(rawKey);
    const countKey = `${k}:count`;
    const lastKey = `${k}:last`;

    const luaScript = `
      local countKey = KEYS[1]
      local lastKey = KEYS[2]
      local now = tonumber(ARGV[1])
      local windowSeconds = tonumber(ARGV[2])
      local windowMs = windowSeconds * 1000
      local freeAttempts = tonumber(ARGV[3])
      local baseDelayMs = tonumber(ARGV[4])
      local maxDelayMs = tonumber(ARGV[5])

      local last = tonumber(redis.call('GET', lastKey))
      local currentCount = tonumber(redis.call('GET', countKey))

      local newCount = 1
      if last and currentCount and (now - last) <= windowMs then
        newCount = currentCount + 1
      else
        newCount = 1
      end

      redis.call('SET', countKey, tostring(newCount), 'EX', windowSeconds)
      redis.call('SET', lastKey, tostring(now), 'EX', windowSeconds)

      local cooldown = 0
      if newCount >= freeAttempts then
        local exponent = newCount - freeAttempts
        local delay = baseDelayMs * (2 ^ exponent)
        if delay > maxDelayMs then
          cooldown = maxDelayMs
        else
          cooldown = math.floor(delay)
        end
      end

      return { newCount, cooldown }
    `.trim();

    try {
      // Execute atomic Lua script via Upstash REST command endpoint
      const res = await this.fetchWithTimeout("/", {
        method: "POST",
        body: JSON.stringify([
          "EVAL",
          luaScript,
          2,
          countKey,
          lastKey,
          String(now),
          String(WINDOW_SECONDS),
          String(FREE_ATTEMPTS),
          String(BASE_DELAY_MS),
          String(MAX_DELAY_MS),
        ]),
      });

      if (!res.ok) throw new Error(`Upstash EVAL returned HTTP ${res.status}`);
      const data = await res.json();

      if (data && typeof data === "object" && "error" in data) {
        throw new Error(`Upstash command error: ${(data as { error: string }).error}`);
      }

      if (
        data &&
        typeof data === "object" &&
        Array.isArray((data as { result?: unknown }).result)
      ) {
        const resultArr = (data as { result: [unknown, unknown] }).result;
        const count = Number(resultArr[0]);
        const cooldown = Number(resultArr[1]);
        if (!Number.isNaN(count) && !Number.isNaN(cooldown)) {
          return { count, cooldown };
        }
      }

      throw new Error("Invalid Upstash EVAL response structure");
    } catch (err) {
      if (this.failurePolicy === "fail-closed") {
        throw new ThrottleStoreError("Distributed rate limiter store unavailable (fail-closed policy in effect)", err);
      }
      if (this.fallback.recordFailure) {
        return this.fallback.recordFailure(rawKey, now);
      }
      const fallbackEntry = await this.fallback.get(rawKey, now);
      const count = fallbackEntry && now - fallbackEntry.last <= WINDOW_MS ? fallbackEntry.count + 1 : 1;
      const cooldown = calculateCooldown(count);
      await this.fallback.set(rawKey, { count, last: now, nextAllowed: now + cooldown }, WINDOW_SECONDS);
      return { count, cooldown };
    }
  }

  async admit(rawKey: string, now: number): Promise<AdmissionResult> {
    const k = this.key(rawKey);
    const countKey = `${k}:count`;
    const lastKey = `${k}:last`;
    const nextAllowedKey = `${k}:nextAllowed`;

    const luaScript = `
      local countKey = KEYS[1]
      local lastKey = KEYS[2]
      local nextAllowedKey = KEYS[3]

      local now = tonumber(ARGV[1])
      local windowSeconds = tonumber(ARGV[2])
      local windowMs = windowSeconds * 1000
      local freeAttempts = tonumber(ARGV[3])
      local baseDelayMs = tonumber(ARGV[4])
      local maxDelayMs = tonumber(ARGV[5])

      local currentCount = tonumber(redis.call('GET', countKey)) or 0
      local last = tonumber(redis.call('GET', lastKey)) or 0
      local nextAllowed = tonumber(redis.call('GET', nextAllowedKey)) or 0

      if last > 0 and (now - last) > windowMs then
        currentCount = 0
        nextAllowed = 0
        redis.call('DEL', countKey, lastKey, nextAllowedKey)
      end

      -- 1. Check if currently locked out
      if nextAllowed > now then
        local retryAfterMs = nextAllowed - now
        redis.call('EXPIRE', countKey, windowSeconds)
        redis.call('EXPIRE', lastKey, windowSeconds)
        redis.call('EXPIRE', nextAllowedKey, windowSeconds)
        return { 0, retryAfterMs, currentCount }
      end

      -- 2. Permit attempt and atomically advance counter
      local newCount = currentCount + 1
      redis.call('SET', countKey, tostring(newCount), 'EX', windowSeconds)
      redis.call('SET', lastKey, tostring(now), 'EX', windowSeconds)

      -- 3. If newCount reaches or exceeds freeAttempts, set nextAllowed lockout for subsequent requests
      local cooldown = 0
      if newCount >= freeAttempts then
        local exponent = newCount - freeAttempts
        local delay = baseDelayMs * (2 ^ exponent)
        if delay > maxDelayMs then
          cooldown = maxDelayMs
        else
          cooldown = math.floor(delay)
        end
        nextAllowed = now + cooldown
        redis.call('SET', nextAllowedKey, tostring(nextAllowed), 'EX', windowSeconds)
      else
        nextAllowed = 0
        redis.call('DEL', nextAllowedKey)
      end

      return { 1, 0, newCount, cooldown }
    `.trim();

    try {
      const res = await this.fetchWithTimeout("/", {
        method: "POST",
        body: JSON.stringify([
          "EVAL",
          luaScript,
          3,
          countKey,
          lastKey,
          nextAllowedKey,
          String(now),
          String(WINDOW_SECONDS),
          String(FREE_ATTEMPTS),
          String(BASE_DELAY_MS),
          String(MAX_DELAY_MS),
        ]),
      });

      if (!res.ok) throw new Error(`Upstash EVAL returned HTTP ${res.status}`);
      const data = await res.json();

      if (data && typeof data === "object" && "error" in data) {
        throw new Error(`Upstash command error: ${(data as { error: string }).error}`);
      }

      if (
        data &&
        typeof data === "object" &&
        Array.isArray((data as { result?: unknown }).result)
      ) {
        const resultArr = (data as { result: [unknown, unknown, unknown, unknown?] }).result;
        const allowedNum = Number(resultArr[0]);
        const retryAfterMs = Number(resultArr[1]);
        const count = Number(resultArr[2]);
        const cooldown = resultArr[3] !== undefined ? Number(resultArr[3]) : undefined;

        if (!Number.isNaN(allowedNum) && !Number.isNaN(retryAfterMs)) {
          return {
            allowed: allowedNum === 1,
            retryAfterMs: Math.max(0, retryAfterMs),
            count: Number.isNaN(count) ? 0 : count,
            cooldown: cooldown !== undefined && !Number.isNaN(cooldown) ? cooldown : undefined,
          };
        }
      }

      throw new Error("Invalid Upstash EVAL response structure for admission");
    } catch (err) {
      if (this.failurePolicy === "fail-closed") {
        throw new ThrottleStoreError("Distributed rate limiter store unavailable (fail-closed policy in effect)", err);
      }
      if (this.fallback.admit) {
        return this.fallback.admit(rawKey, now);
      }
      const entry = await this.fallback.get(rawKey, now);
      if (entry && now - entry.last <= WINDOW_MS && entry.nextAllowed > now) {
        return {
          allowed: false,
          retryAfterMs: entry.nextAllowed - now,
          count: entry.count,
        };
      }
      const newCount = entry && now - entry.last <= WINDOW_MS ? entry.count + 1 : 1;
      const cooldown = calculateCooldown(newCount);
      const nextAllowed = cooldown > 0 ? now + cooldown : 0;
      await this.fallback.set(rawKey, { count: newCount, last: now, nextAllowed }, WINDOW_SECONDS);
      return {
        allowed: true,
        retryAfterMs: 0,
        count: newCount,
        cooldown,
      };
    }
  }

  async set(rawKey: string, entry: Attempts, ttlSeconds: number): Promise<void> {
    const k = this.key(rawKey);
    const countKey = `${k}:count`;
    const lastKey = `${k}:last`;
    const nextAllowedKey = `${k}:nextAllowed`;
    try {
      const res = await this.fetchWithTimeout("/multi-exec", {
        method: "POST",
        body: JSON.stringify([
          ["SET", countKey, String(entry.count), "EX", ttlSeconds],
          ["SET", lastKey, String(entry.last), "EX", ttlSeconds],
          ["SET", nextAllowedKey, String(entry.nextAllowed), "EX", ttlSeconds],
        ]),
      });
      if (!res.ok) throw new Error(`Upstash set returned HTTP ${res.status}`);
      const rawData = await res.json();
      this.validateMultiExecResponse(rawData, 3);
    } catch (err) {
      if (this.failurePolicy === "fail-closed") {
        throw new ThrottleStoreError("Distributed rate limiter store unavailable (fail-closed policy in effect)", err);
      }
      await this.fallback.set(rawKey, entry, ttlSeconds);
    }
  }

  async delete(rawKey: string): Promise<void> {
    const k = this.key(rawKey);
    const countKey = `${k}:count`;
    const lastKey = `${k}:last`;
    const nextAllowedKey = `${k}:nextAllowed`;
    try {
      const res = await this.fetchWithTimeout("/multi-exec", {
        method: "POST",
        body: JSON.stringify([
          ["DEL", countKey],
          ["DEL", lastKey],
          ["DEL", nextAllowedKey],
        ]),
      });
      if (!res.ok) throw new Error(`Upstash delete returned HTTP ${res.status}`);
      const rawData = await res.json();
      this.validateMultiExecResponse(rawData, 3);
    } catch (err) {
      if (this.failurePolicy === "fail-closed") {
        throw new ThrottleStoreError("Distributed rate limiter store unavailable (fail-closed policy in effect)", err);
      }
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
 * Atomically admits an incoming login attempt for a caller.
 * Checks rate limits and reserves the attempt slot atomically.
 * Returns whether the attempt is admitted or denied (with retryAfterMs).
 */
export async function admitLoginAttempt(rawKey: string, now: number = Date.now()): Promise<AdmissionResult> {
  const backend = getActiveThrottleBackend();
  if (backend.admit) {
    return backend.admit(rawKey, now);
  }
  const entry = await backend.get(rawKey, now);
  if (entry && now - entry.last <= WINDOW_MS && entry.nextAllowed > now) {
    return {
      allowed: false,
      retryAfterMs: Math.max(0, entry.nextAllowed - now),
      count: entry.count,
    };
  }
  const newCount = entry && now - entry.last <= WINDOW_MS ? entry.count + 1 : 1;
  const cooldown = calculateCooldown(newCount);
  const nextAllowed = cooldown > 0 ? now + cooldown : 0;
  await backend.set(rawKey, { count: newCount, last: now, nextAllowed }, WINDOW_SECONDS);
  return {
    allowed: true,
    retryAfterMs: 0,
    count: newCount,
    cooldown,
  };
}

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
  if (backend.recordFailure) {
    const { cooldown } = await backend.recordFailure(rawKey, now);
    return cooldown;
  }
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
