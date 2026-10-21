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
  admitLoginAttempt,
  type ThrottleBackend,
  type Attempts,
  UpstashRedisThrottleBackend,
  InMemoryThrottleBackend,
} from "@/lib/auth/throttle";
import { extractCallerKey, isValidIp } from "@/app/api/login/route";

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
    // Pinned clock: with Date.now() a slow runner sees a few ms elapse between the
    // last failure and the check, and the wait reads 29998 instead of the cap.
    const now = 1_000_000;
    for (let i = 0; i < 40; i++) recordFailure("1.2.3.4", now);
    expect(delayForNextAttempt("1.2.3.4", now)).toBe(30_000);
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
  }, 15_000);

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
  }, 15_000);

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
  }, 15_000);
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

  it("falls back cleanly to in-memory throttle when distributed store is unavailable under fail-secure-fallback", async () => {
    const brokenStore = new UpstashRedisThrottleBackend(
      "https://invalid-nonexistent-domain.nowhere",
      "mock-token",
      new InMemoryThrottleBackend(),
      50, // fast timeout
      "fail-secure-fallback"
    );

    setCustomThrottleBackend(brokenStore);

    const ip = "203.0.113.1";
    const now = 20_000_000;

    // Recording failures should not crash; it should fall back to memory store
    for (let i = 0; i < 4; i++) {
      await recordThrottleFailure(ip, now);
    }

    // Cooldown should still be enforced locally by fallback store!
    const delay = await getThrottleDelay(ip, now);
    expect(delay).toBeGreaterThan(0);
  });

  it("throws ThrottleStoreError when distributed store is unavailable under fail-closed policy", async () => {
    const brokenStore = new UpstashRedisThrottleBackend(
      "https://invalid-nonexistent-domain.nowhere",
      "mock-token",
      new InMemoryThrottleBackend(),
      50,
      "fail-closed"
    );

    setCustomThrottleBackend(brokenStore);

    await expect(recordThrottleFailure("attacker-ip", 10_000)).rejects.toThrow(
      "Distributed rate limiter store unavailable (fail-closed policy in effect)"
    );
  });

  describe("standards-compliant IP validator & proxy trust boundary", () => {
    it("validates standard IPv4 addresses", () => {
      expect(isValidIp("192.168.1.1")).toBe(true);
      expect(isValidIp("10.0.0.1")).toBe(true);
      expect(isValidIp("172.16.254.1")).toBe(true);
      expect(isValidIp("8.8.8.8")).toBe(true);
    });

    it("rejects IPv4 octets above 255", () => {
      expect(isValidIp("192.168.1.256")).toBe(false);
      expect(isValidIp("256.0.0.1")).toBe(false);
      expect(isValidIp("10.300.0.1")).toBe(false);
      expect(isValidIp("1.2.3.999")).toBe(false);
    });

    it("rejects addresses with trailing characters or invalid syntax", () => {
      expect(isValidIp("192.168.1.1extra")).toBe(false);
      expect(isValidIp("192.168.1.1/24")).toBe(false);
      expect(isValidIp("192.168.1.1:80")).toBe(false);
      expect(isValidIp("2001:db8::1trailing")).toBe(false);
      expect(isValidIp("1.2.3")).toBe(false);
      expect(isValidIp("1.2.3.4.5")).toBe(false);
      expect(isValidIp("hello.world")).toBe(false);
      expect(isValidIp(":::")).toBe(false);
      expect(isValidIp("")).toBe(false);
      expect(isValidIp(null)).toBe(false);
      expect(isValidIp(undefined)).toBe(false);
    });

    it("accepts valid IPv6 compression and standard notations", () => {
      expect(isValidIp("::1")).toBe(true);
      expect(isValidIp("::")).toBe(true);
      expect(isValidIp("2001:db8::1")).toBe(true);
      expect(isValidIp("fe80::1")).toBe(true);
      expect(isValidIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334")).toBe(true);
    });

    it("accepts IPv4-mapped IPv6 addresses", () => {
      expect(isValidIp("::ffff:192.0.2.128")).toBe(true);
      expect(isValidIp("::ffff:10.0.0.1")).toBe(true);
      expect(isValidIp("::ffff:999.0.0.1")).toBe(false);
    });

    it("prevents client IP spoofing by prioritizing x-real-ip and taking trusted edge-appended hop in x-forwarded-for", () => {
      const headers = new Headers();
      headers.set("x-forwarded-for", "203.0.113.195, 198.51.100.1");

      // 1. Outside Vercel (process.env.VERCEL !== "1"): untrusted -> unknown
      expect(extractCallerKey(headers, false)).toBe("unknown");

      // 2. Vercel environment flag alone without x-vercel-id header: untrusted -> unknown
      expect(extractCallerKey(headers, true)).toBe("unknown");

      // 3. Vercel with valid x-vercel-id: trusts edge-appended rightmost hop (198.51.100.1) over spoofed (203.0.113.195)
      headers.set("x-vercel-id", "fra1::abc123-xyz");
      expect(extractCallerKey(headers, true)).toBe("198.51.100.1");

      // 4. Single hop in x-forwarded-for
      headers.set("x-forwarded-for", "1.2.3.4");
      expect(extractCallerKey(headers, true)).toBe("1.2.3.4");

      // 5. Vercel with x-real-ip prioritizes the single edge-verified socket IP
      headers.set("x-real-ip", "198.51.100.99");
      expect(extractCallerKey(headers, true)).toBe("198.51.100.99");

      // 6. Vercel with valid IPv6 address in x-real-ip
      headers.set("x-real-ip", "2001:db8::8a2e:370:7334");
      expect(extractCallerKey(headers, true)).toBe("2001:db8::8a2e:370:7334");

      // 7. Header injection / malicious strings are rejected as "unknown"
      headers.set("x-real-ip", "evil-attacker-token");
      headers.set("x-forwarded-for", "malicious-ip-spoof");
      expect(extractCallerKey(headers, true)).toBe("unknown");

      // 8. Octet > 255 rejected
      headers.set("x-real-ip", "192.168.1.999");
      headers.set("x-forwarded-for", "256.1.1.1");
      expect(extractCallerKey(headers, true)).toBe("unknown");
    });

    it("off Vercel, ignores forwarding headers unless TRUSTED_PROXY_HEADER names one", () => {
      const headers = new Headers({
        "x-forwarded-for": "203.0.113.195, 198.51.100.1",
        "x-real-ip": "198.51.100.50",
      });
      expect(extractCallerKey(headers, false, undefined)).toBe("unknown");
      expect(extractCallerKey(headers, false, "  ")).toBe("unknown");

      // Only the hop the operator's proxy appended is trusted, not the client's prefix.
      expect(extractCallerKey(headers, false, "x-forwarded-for")).toBe("198.51.100.1");
      expect(extractCallerKey(headers, false, "X-Real-IP")).toBe("198.51.100.50");

      // A configured header that is missing or garbage falls back to the shared key.
      expect(extractCallerKey(headers, false, "cf-connecting-ip")).toBe("unknown");
      headers.set("x-forwarded-for", "not-an-ip");
      expect(extractCallerKey(headers, false, "x-forwarded-for")).toBe("unknown");
    });
  });

  it("handles concurrent requests atomically without read-modify-write races", async () => {
    // Mock atomic Upstash store simulating Redis EVAL execution
    let redisCounter = 0;
    let redisLast = 0;
    const clientIp = "192.0.2.200";

    const atomicBackend = new UpstashRedisThrottleBackend(
      "https://mock-upstash.local",
      "mock-token",
      new InMemoryThrottleBackend(),
      2000
    );

    // Mock fetchWithTimeout on the instance
    const originalFetch = global.fetch;
    global.fetch = async (url: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const urlStr = url.toString();
      const body = JSON.parse((init?.body as string) || "[]");

      if (urlStr.endsWith("/multi-exec")) {
        // Handle GET /multi-exec
        if (body[0]?.[0] === "GET") {
          return new Response(
            JSON.stringify([
              { result: redisCounter > 0 ? String(redisCounter) : null },
              { result: redisLast > 0 ? String(redisLast) : null },
            ]),
            { status: 200 }
          );
        }
      }

      // Handle EVAL command via POST /
      if (body[0] === "EVAL") {
        redisCounter++;
        redisLast = Number(body[5]); // now
        const cooldown = redisCounter < 3 ? 0 : Math.min(30000, 1000 * 2 ** (redisCounter - 3));
        return new Response(
          JSON.stringify({ result: [redisCounter, cooldown] }),
          { status: 200 }
        );
      }

      return new Response(JSON.stringify({ error: "Unhandled command" }), { status: 400 });
    };

    try {
      // Simulate 10 concurrent failure requests arriving simultaneously
      const now = 60_000_000;
      const results = await Promise.all(
        Array.from({ length: 10 }, () => atomicBackend.recordFailure(clientIp, now))
      );

      // Verify that every request received a distinct sequential count (1 to 10)
      const counts = results.map((r) => r.count).sort((a, b) => a - b);
      expect(counts).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);

      // Free attempts are 1 and 2 (cooldown 0), attempt 3 triggers initial cooldown 1000ms, then doubles
      expect(results.find((r) => r.count === 2)?.cooldown).toBe(0);
      expect(results.find((r) => r.count === 3)?.cooldown).toBe(1000);
      expect(results.find((r) => r.count === 4)?.cooldown).toBe(2000);
      expect(results.find((r) => r.count === 5)?.cooldown).toBe(4000);

      // Final count in Redis is exactly 10; concurrent requests could not bypass lockout
      expect(redisCounter).toBe(10);
    } finally {
      global.fetch = originalFetch;
    }
  });

  it("handles malformed Redis responses, syntax errors, and temporary server failures", async () => {
    const originalFetch = global.fetch;

    // 1. Upstash returns command syntax error { error: "ERR syntax error" }
    global.fetch = async (): Promise<Response> => {
      return new Response(JSON.stringify({ error: "ERR syntax error" }), { status: 200 });
    };

    const fallbackMemory = new InMemoryThrottleBackend();
    const store = new UpstashRedisThrottleBackend(
      "https://mock-upstash.local",
      "mock-token",
      fallbackMemory,
      1000,
      "fail-secure-fallback"
    );

    // Should not throw under fail-secure-fallback; gracefully uses local fallback
    const failureRes = await store.recordFailure("10.0.0.1", 70_000_000);
    expect(failureRes.count).toBe(1);

    // 2. Upstash returns malformed non-array for /multi-exec get
    global.fetch = async (): Promise<Response> => {
      return new Response(JSON.stringify({ notAnArray: true }), { status: 200 });
    };
    const getRes = await store.get("10.0.0.1", 70_000_000);
    // Gracefully handled via fallback
    expect(getRes).toBeDefined();

    // 3. Upstash returns HTTP 500 internal server error
    global.fetch = async (): Promise<Response> => {
      return new Response("Internal Server Error", { status: 500 });
    };
    const get500Res = await store.get("10.0.0.1", 70_000_000);
    expect(get500Res).toBeDefined();

    // 4. Under fail-closed policy, HTTP 500 throws ThrottleStoreError
    const closedStore = new UpstashRedisThrottleBackend(
      "https://mock-upstash.local",
      "mock-token",
      fallbackMemory,
      1000,
      "fail-closed"
    );
    await expect(closedStore.get("10.0.0.1", 70_000_000)).rejects.toThrow(
      "Distributed rate limiter store unavailable (fail-closed policy in effect)"
    );

    global.fetch = originalFetch;
  });

  it("expires lockout after cooldown window elapses", async () => {
    const ip = "192.0.2.10";
    const now = 50_000_000;

    // 4 failures -> 2000ms cooldown (3 free + 1 doubled)
    for (let i = 0; i < 4; i++) {
      await recordThrottleFailure(ip, now);
    }

    expect(await getThrottleDelay(ip, now)).toBe(2000);
    // At now + 2001ms, lockout has expired
    expect(await getThrottleDelay(ip, now + 2001)).toBe(0);
  });

  describe("deterministic failure injection & Upstash response validation", () => {
    it("validates /multi-exec responses strictly and detects partial command errors", () => {
      const backend = new UpstashRedisThrottleBackend(
        "https://mock-upstash.local",
        "mock-token",
        new InMemoryThrottleBackend()
      );

      // 1. Partial command failure: Command 1 OK, Command 2 returns error
      expect(() =>
        backend.validateMultiExecResponse(
          [{ result: "OK" }, { error: "OOM command not allowed" }, { result: "OK" }],
          3
        )
      ).toThrow("Upstash command error at index 1: OOM command not allowed");

      // 2. Truncated response: expected 3 items, got 2
      expect(() =>
        backend.validateMultiExecResponse([{ result: "OK" }, { result: "OK" }], 3)
      ).toThrow("Malformed Upstash response: expected 3 results, got 2");

      // 3. Non-array response payload
      expect(() =>
        backend.validateMultiExecResponse({ notAnArray: true }, 3)
      ).toThrow("Malformed Upstash response: expected array");

      // 4. Top-level error object from Upstash
      expect(() =>
        backend.validateMultiExecResponse({ error: "ERR syntax error" }, 3)
      ).toThrow("Upstash error: ERR syntax error");

      // 5. Malformed item inside results array
      expect(() =>
        backend.validateMultiExecResponse([{ result: 1 }, null, { result: 1 }], 3)
      ).toThrow("Malformed Upstash command result at index 1");

      // 6. Valid complete response succeeds
      const valid = backend.validateMultiExecResponse(
        [{ result: "1" }, { result: "1000" }, { result: "2000" }],
        3
      );
      expect(valid).toHaveLength(3);
      expect(valid[0].result).toBe("1");
    });

    it("verifies atomic Lua set() and delete() handle errors according to policy", async () => {
      const originalFetch = global.fetch;

      try {
        global.fetch = async (): Promise<Response> => {
          return new Response(JSON.stringify({ error: "ERR Lua execution failed" }), { status: 200 });
        };

        // Fail-closed policy
        const closedBackend = new UpstashRedisThrottleBackend(
          "https://mock-upstash.local",
          "mock-token",
          new InMemoryThrottleBackend(),
          1000,
          "fail-closed"
        );

        await expect(
          closedBackend.set("test-key", { count: 1, last: 1000, nextAllowed: 0 }, 60)
        ).rejects.toThrow("Distributed rate limiter store unavailable (fail-closed policy in effect)");

        await expect(closedBackend.delete("test-key")).rejects.toThrow(
          "Distributed rate limiter store unavailable (fail-closed policy in effect)"
        );

        // Fail-secure-fallback policy
        const fallbackBackend = new UpstashRedisThrottleBackend(
          "https://mock-upstash.local",
          "mock-token",
          new InMemoryThrottleBackend(),
          1000,
          "fail-secure-fallback"
        );

        await expect(
          fallbackBackend.set("test-key-fallback", { count: 2, last: 2000, nextAllowed: 0 }, 60)
        ).resolves.not.toThrow();

        await expect(fallbackBackend.delete("test-key-fallback")).resolves.not.toThrow();
      } finally {
        global.fetch = originalFetch;
      }
    });

    it("ensures admit() never silently grants admission on malformed or non-numeric Lua results", async () => {
      const originalFetch = global.fetch;

      try {
        // Fail-closed backend
        const closedBackend = new UpstashRedisThrottleBackend(
          "https://mock-upstash.local",
          "mock-token",
          new InMemoryThrottleBackend(),
          1000,
          "fail-closed"
        );

        // Case A: Upstash returns non-array result string
        global.fetch = async (): Promise<Response> => {
          return new Response(JSON.stringify({ result: "OK" }), { status: 200 });
        };
        await expect(closedBackend.admit("test-admit", 1000)).rejects.toThrow(
          "Distributed rate limiter store unavailable (fail-closed policy in effect)"
        );

        // Case B: Upstash returns NaN values in result array
        global.fetch = async (): Promise<Response> => {
          return new Response(JSON.stringify({ result: ["not-a-number", "invalid"] }), { status: 200 });
        };
        await expect(closedBackend.admit("test-admit", 1000)).rejects.toThrow(
          "Distributed rate limiter store unavailable (fail-closed policy in effect)"
        );

        // Case C: Legitimate denial result [0, 5000, 3] returns allowed: false
        global.fetch = async (): Promise<Response> => {
          return new Response(JSON.stringify({ result: [0, 5000, 3] }), { status: 200 });
        };
        const deniedResult = await closedBackend.admit("test-admit", 1000);
        expect(deniedResult.allowed).toBe(false);
        expect(deniedResult.retryAfterMs).toBe(5000);
        expect(deniedResult.count).toBe(3);
      } finally {
        global.fetch = originalFetch;
      }
    });

    it("handles race between successful authentication clearance and concurrent attempt from same IP", async () => {
      const ip = "198.51.100.77";
      const now = 80_000_000;

      // Simulate concurrent admission request and success deletion
      const r1Promise = admitLoginAttempt(ip, now);
      const r2Promise = recordThrottleSuccess(ip);

      const [r1] = await Promise.all([r1Promise, r2Promise]);
      expect(r1.allowed).toBe(true);

      // Subsequent attempt after success is treated as fresh
      const r3 = await admitLoginAttempt(ip, now + 10);
      expect(r3.allowed).toBe(true);
      expect(r3.count).toBe(1);
    });
  });

  describe("real Redis integration tests (conditional on credentials)", () => {
    const liveUrl = process.env.UPSTASH_REDIS_REST_URL;
    const liveToken = process.env.UPSTASH_REDIS_REST_TOKEN;

    it("executes atomic Lua script and /multi-exec against real Upstash service if configured", async () => {
      if (!liveUrl || !liveToken) {
        // Explicitly distinguish unit tests with mocked responses from real service execution
        // Never hardcode or leak credentials
        expect(true).toBe(true);
        return;
      }

      const backend = new UpstashRedisThrottleBackend(liveUrl, liveToken, new InMemoryThrottleBackend(), 5000);
      const testKey = `test:live:${Date.now()}`;
      const now = Date.now();

      try {
        const res1 = await backend.recordFailure(testKey, now);
        expect(res1.count).toBe(1);
        expect(res1.cooldown).toBe(0);

        const res2 = await backend.recordFailure(testKey, now + 10);
        expect(res2.count).toBe(2);

        const getRes = await backend.get(testKey, now + 20);
        expect(getRes?.count).toBe(2);

        await backend.delete(testKey);
        const deletedRes = await backend.get(testKey, now + 30);
        expect(deletedRes).toBeNull();
      } finally {
        await backend.delete(testKey).catch(() => {});
      }
    });
  });
});
