import { beforeEach, describe, expect, it } from "vitest";
import {
  delayForNextAttempt,
  getTrackedCount,
  getThrottleDelay,
  MAX_TRACKED,
  recordFailure,
  recordSuccess,
  recordThrottleFailure,
  recordThrottleSuccess,
  resetThrottle,
  setCustomThrottleBackend,
  type ThrottleBackend,
  type Attempts,
  UpstashRedisThrottleBackend,
  InMemoryThrottleBackend,
} from "@/lib/auth/throttle";
import { extractCallerKey } from "@/app/api/login/route";

/**
 * The gate's strength is one shared secret, and until this existed the number of
 * guesses an attacker could make was bounded only by how fast the host would serve.
 */

beforeEach(() => resetThrottle());

describe("login throttle", () => {
  it("lets the first few honest mistakes through free", () => {
    for (let i = 0; i < 3; i++) {
      expect(delayForNextAttempt("1.2.3.4")).toBe(0);
      recordFailure("1.2.3.4");
    }
    expect(delayForNextAttempt("1.2.3.4")).toBeGreaterThan(0);
  });

  it("doubles the wait with each further failure", () => {
    const now = 1_000_000;
    for (let i = 0; i < 4; i++) recordFailure("1.2.3.4", now);
    const first = delayForNextAttempt("1.2.3.4", now);
    recordFailure("1.2.3.4", now);
    const second = delayForNextAttempt("1.2.3.4", now);

    expect(second).toBe(first * 2);
  });

  it("counts a cooldown down instead of holding the caller for the whole window", () => {
    const start = 1_000_000;
    for (let i = 0; i < 3; i++) recordFailure("1.2.3.4", start);

    expect(delayForNextAttempt("1.2.3.4", start)).toBe(1_000);
    expect(delayForNextAttempt("1.2.3.4", start + 400)).toBe(600);
    expect(delayForNextAttempt("1.2.3.4", start + 1_000)).toBe(0);
  });

  it("caps the wait so a legitimate user is not locked out forever", () => {
    for (let i = 0; i < 40; i++) recordFailure("1.2.3.4");
    expect(delayForNextAttempt("1.2.3.4")).toBe(30_000);
  });

  it("throttles each caller separately", () => {
    for (let i = 0; i < 10; i++) recordFailure("1.2.3.4");
    expect(delayForNextAttempt("1.2.3.4")).toBeGreaterThan(0);
    expect(delayForNextAttempt("5.6.7.8")).toBe(0);
  });

  it("forgives a caller after a quiet window", () => {
    const start = 1_000_000;
    for (let i = 0; i < 10; i++) recordFailure("1.2.3.4", start);
    expect(delayForNextAttempt("1.2.3.4", start)).toBeGreaterThan(0);
    // Sixteen minutes later.
    expect(delayForNextAttempt("1.2.3.4", start + 16 * 60_000)).toBe(0);
  });

  it("clears the record once the caller proves who they are", () => {
    for (let i = 0; i < 10; i++) recordFailure("1.2.3.4");
    recordSuccess("1.2.3.4");
    expect(delayForNextAttempt("1.2.3.4")).toBe(0);
  });

  it("normalizes malformed and empty keys safely", () => {
    recordFailure("");
    recordFailure("   ");
    expect(delayForNextAttempt("")).toBe(0); // 2 failures on "unknown"
    recordFailure("");
    // 3rd failure on "unknown" triggers cooldown
    expect(delayForNextAttempt("")).toBeGreaterThan(0);
    expect(delayForNextAttempt("unknown")).toBeGreaterThan(0);
  });

  it("handles excessively long keys without breaking", () => {
    const longKey = "a".repeat(1000);
    for (let i = 0; i < 4; i++) recordFailure(longKey);
    expect(delayForNextAttempt(longKey)).toBeGreaterThan(0);
  });

  it("evicts oldest entries without wiping active throttled attackers on key flood", () => {
    const now = 1_000_000;
    const attackerIp = "bad-actor-ip";
    for (let i = 0; i < 5; i++) recordFailure(attackerIp, now);
    expect(delayForNextAttempt(attackerIp, now)).toBeGreaterThan(0);

    // Flood with distinct keys newer than attackerIp
    for (let i = 0; i < 10_005; i++) {
      recordFailure(`fake-ip-${i}`, now + 10);
    }

    // Active map size is bounded
    expect(getTrackedCount()).toBeLessThanOrEqual(MAX_TRACKED);

    // CRITICAL: Attacker must STILL be locked out; eviction must not have freed them!
    expect(delayForNextAttempt(attackerIp, now + 10)).toBeGreaterThan(0);
  });

  it("adversarial flood of unthrottled keys purges unlocked keys first, retaining active lockouts", () => {
    const now = 2_000_000;
    const lockedIp = "locked-target";
    // 6 failures -> locked out for several seconds
    for (let i = 0; i < 6; i++) recordFailure(lockedIp, now);
    expect(delayForNextAttempt(lockedIp, now)).toBeGreaterThan(0);

    // Adversary attempts to clear lockout by flooding dummy keys (1 failure each, 0 cooldown)
    for (let i = 0; i < MAX_TRACKED + 500; i++) {
      recordFailure(`dummy-${i}`, now + 1);
    }

    // Locked IP must still be in cooldown at now + 100ms
    expect(delayForNextAttempt(lockedIp, now + 100)).toBeGreaterThan(0);
  });

  it("evicts earliest-expiring lockouts when all tracked entries are actively locked", () => {
    const now = 3_000_000;
    // Saturate the map with entries that are ALL actively locked out
    // Give each distinct failure counts so their nextAllowed values differ
    for (let i = 0; i < MAX_TRACKED + 100; i++) {
      // 5 failures -> exponential backoff
      for (let f = 0; f < 5; f++) {
        recordFailure(`all-locked-${i}`, now + i);
      }
    }

    // Size must be bounded to MAX_TRACKED
    expect(getTrackedCount()).toBeLessThanOrEqual(MAX_TRACKED);

    // The most recently added entry with a longer future lockout must still be tracked and locked
    const recentKey = `all-locked-${MAX_TRACKED + 50}`;
    expect(delayForNextAttempt(recentKey, now + MAX_TRACKED + 50)).toBeGreaterThan(0);
  });
});

describe("distributed rate limiting & client spoofing defense", () => {
  it("enforces rate limits across multiple simulated serverless instances sharing a store", async () => {
    // Shared mock distributed store
    const sharedMap = new Map<string, Attempts>();
    const sharedStore: ThrottleBackend = {
      async get(key: string, now: number) {
        const item = sharedMap.get(key);
        if (!item || now - item.last > 15 * 60 * 1000) return null;
        return item;
      },
      async set(key: string, entry: Attempts) {
        sharedMap.set(key, entry);
      },
      async delete(key: string) {
        sharedMap.delete(key);
      },
    };

    setCustomThrottleBackend(sharedStore);

    const clientIp = "198.51.100.42";
    const now = 10_000_000;

    // Instance 1 records 2 failures
    await recordThrottleFailure(clientIp, now);
    await recordThrottleFailure(clientIp, now + 100);
    expect(await getThrottleDelay(clientIp, now + 200)).toBe(0);

    // Instance 2 receives 3rd failure from same clientIp
    await recordThrottleFailure(clientIp, now + 300);

    // Instance 3 now detects the progressive cooldown (1000ms from 3rd failure)
    const delayInstance3 = await getThrottleDelay(clientIp, now + 400);
    expect(delayInstance3).toBeGreaterThan(0);
    expect(delayInstance3).toBeLessThanOrEqual(1000);

    // Instance 4 receives successful login -> clears key across all instances
    await recordThrottleSuccess(clientIp);
    expect(await getThrottleDelay(clientIp, now + 500)).toBe(0);
  });

  it("falls back cleanly to in-memory throttle when distributed store is unavailable", async () => {
    const brokenStore = new UpstashRedisThrottleBackend(
      "https://invalid-nonexistent-domain.nowhere",
      "mock-token",
      new InMemoryThrottleBackend(),
      50 // fast timeout
    );

    setCustomThrottleBackend(brokenStore);

    const ip = "203.0.113.1";
    const now = 20_000_000;

    // Recording failures should not crash; it should fall back to memory store
    for (let i = 0; i < 3; i++) {
      await recordThrottleFailure(ip, now);
    }

    // Cooldown should still be enforced locally by fallback store!
    const delay = await getThrottleDelay(ip, now);
    expect(delay).toBeGreaterThan(0);
  });

  it("prevents client IP spoofing by ignoring x-forwarded-for when not running behind Vercel", () => {
    const headers = new Headers();
    headers.set("x-forwarded-for", "1.2.3.4, 5.6.7.8");

    // Outside Vercel (e.g. self-hosted Node without trusted reverse proxy config), x-forwarded-for is untrusted
    expect(extractCallerKey(headers, false)).toBe("unknown");

    // Behind trusted Vercel proxy, the first IP is verified by Vercel edge
    expect(extractCallerKey(headers, true)).toBe("1.2.3.4");
  });
});
