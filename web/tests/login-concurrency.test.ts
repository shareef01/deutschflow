import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { POST } from "@/app/api/login/route";
import {
  admitLoginAttempt,
  getThrottleDelay,
  recordThrottleSuccess,
  resetThrottle,
  setCustomThrottleBackend,
  InMemoryThrottleBackend,
  UpstashRedisThrottleBackend,
} from "@/lib/auth/throttle";
import { SESSION_COOKIE } from "@/lib/auth/session";

const PASSWORD = "correct-secure-master-password";
const SECRET = "high-entropy-session-signing-secret-at-least-32-chars";

function makeLoginRequest(ip: string, password: string, headers: Record<string, string> = {}): NextRequest {
  const fd = new FormData();
  fd.set("password", password);
  fd.set("from", "/dashboard");

  return new NextRequest("http://localhost:3000/api/login", {
    method: "POST",
    headers: {
      "x-vercel-id": "fra1::iad1::trace-concurrency-test",
      "x-real-ip": ip,
      ...headers,
    },
    body: fd,
  });
}

function createUpstashSimulator() {
  const redisState = new Map<string, string>();
  const calls: string[] = [];

  const mockFetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const bodyStr = typeof init?.body === "string" ? init.body : "";
    let body: unknown;
    try {
      body = JSON.parse(bodyStr);
    } catch {
      return new Response(JSON.stringify({ error: "Invalid JSON" }), { status: 400 });
    }

    if (Array.isArray(body) && body[0] === "EVAL") {
      calls.push("EVAL");
      const script = String(body[1]);
      if (script.startsWith("redis.call('DEL'")) {
        const countKey = String(body[3]);
        const lastKey = String(body[4]);
        const nextAllowedKey = String(body[5]);
        redisState.delete(countKey);
        redisState.delete(lastKey);
        redisState.delete(nextAllowedKey);
        return new Response(JSON.stringify({ result: 1 }), { status: 200 });
      }
      if (script.includes("KEYS[1], ARGV[1]") || script.startsWith("redis.call('SET'")) {
        const countKey = String(body[3]);
        const lastKey = String(body[4]);
        const nextAllowedKey = String(body[5]);
        redisState.set(countKey, String(body[6]));
        redisState.set(lastKey, String(body[7]));
        redisState.set(nextAllowedKey, String(body[8]));
        return new Response(JSON.stringify({ result: 1 }), { status: 200 });
      }

      const countKey = String(body[3]);
      const lastKey = String(body[4]);
      const nextAllowedKey = String(body[5]);
      const now = Number(body[6]);
      const windowSeconds = Number(body[7]);
      const windowMs = windowSeconds * 1000;
      const freeAttempts = Number(body[8]);
      const baseDelayMs = Number(body[9]);
      const maxDelayMs = Number(body[10]);

      let currentCount = Number(redisState.get(countKey)) || 0;
      const last = Number(redisState.get(lastKey)) || 0;
      let nextAllowed = Number(redisState.get(nextAllowedKey)) || 0;

      if (last > 0 && now - last > windowMs) {
        currentCount = 0;
        nextAllowed = 0;
        redisState.delete(countKey);
        redisState.delete(lastKey);
        redisState.delete(nextAllowedKey);
      }

      if (nextAllowed > now) {
        const retryAfterMs = nextAllowed - now;
        return new Response(JSON.stringify({ result: [0, retryAfterMs, currentCount] }), { status: 200 });
      }

      const newCount = currentCount + 1;
      redisState.set(countKey, String(newCount));
      redisState.set(lastKey, String(now));

      let cooldown = 0;
      if (newCount >= freeAttempts) {
        const exponent = newCount - freeAttempts;
        const delay = baseDelayMs * Math.pow(2, exponent);
        cooldown = Math.min(maxDelayMs, Math.floor(delay));
        nextAllowed = now + cooldown;
        redisState.set(nextAllowedKey, String(nextAllowed));
      } else {
        redisState.delete(nextAllowedKey);
      }

      return new Response(JSON.stringify({ result: [1, 0, newCount, cooldown] }), { status: 200 });
    }

    // Handle /multi-exec
    if (Array.isArray(body)) {
      calls.push("MULTI-EXEC");
      const results = [];
      for (const cmd of body) {
        if (Array.isArray(cmd)) {
          if (cmd[0] === "DEL") {
            redisState.delete(cmd[1]);
            results.push({ result: 1 });
          } else if (cmd[0] === "SET") {
            redisState.set(cmd[1], cmd[2]);
            results.push({ result: "OK" });
          } else if (cmd[0] === "GET") {
            const val = redisState.get(cmd[1]) ?? null;
            results.push({ result: val });
          }
        }
      }
      return new Response(JSON.stringify(results), { status: 200 });
    }

    return new Response(JSON.stringify({ error: "Unsupported mock command" }), { status: 400 });
  };

  return { redisState, calls, mockFetch };
}

describe("PHASE 2 & 3: Critical Login Concurrency, Atomic Admission & Redis Verification", () => {
  beforeEach(() => {
    vi.stubEnv("SITE_PASSWORD", PASSWORD);
    vi.stubEnv("SESSION_SECRET", SECRET);
    vi.stubEnv("VERCEL", "1");
    resetThrottle();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    resetThrottle();
    vi.restoreAllMocks();
  });

  // 1. One valid login
  it("1. One valid login succeeds, sets session cookie and redirects", async () => {
    const req = makeLoginRequest("198.51.100.1", PASSWORD);
    const res = await POST(req);
    expect(res.status).toBe(200);

    const data = await res.json();
    expect(data.redirect).toBe("/dashboard");
    expect(res.cookies.get(SESSION_COOKIE)).toBeDefined();
    expect(res.cookies.get(SESSION_COOKIE)?.value).toBeTruthy();
  });

  // 2. Incorrect password
  it("2. Incorrect password rejects with 401 and records attempt", async () => {
    const req = makeLoginRequest("198.51.100.2", "wrong-password");
    const res = await POST(req);
    expect(res.status).toBe(401);
    const data = await res.json();
    expect(data.error).toContain("That key doesn't match");
  });

  // 3. Sequential failed logins
  it("3. Sequential failed logins trigger rate-limit lockout after threshold", async () => {
    const ip = "198.51.100.3";

    // Attempts 1, 2, 3: admitted but fail password check (401)
    for (let i = 1; i <= 3; i++) {
      const res = await POST(makeLoginRequest(ip, "bad-password"));
      expect(res.status).toBe(401);
    }

    // Attempt 4: denied at admission (429)
    const res4 = await POST(makeLoginRequest(ip, "bad-password"));
    expect(res4.status).toBe(429);
    expect(res4.headers.get("Retry-After")).toBeTruthy();
    const data4 = await res4.json();
    expect(data4.error).toContain("Too many attempts");
  });

  // 4. Twenty concurrent failed logins (Exact maximum admitted assertion)
  it("4. Twenty concurrent failed logins: exactly 3 admitted (401), 17 denied (429)", async () => {
    const ip = "198.51.100.4";
    const requests = Array.from({ length: 20 }, () => makeLoginRequest(ip, "wrong-password"));

    const responses = await Promise.all(requests.map((r) => POST(r)));
    const statuses = responses.map((r) => r.status);

    const admittedCount = statuses.filter((s) => s === 401).length;
    const rateLimitedCount = statuses.filter((s) => s === 429).length;

    expect(admittedCount).toBe(3);
    expect(rateLimitedCount).toBe(17);
    expect(statuses.length).toBe(20);
  });

  // 5. Fifty concurrent failed logins (Exact maximum admitted assertion)
  it("5. Fifty concurrent failed logins: exactly 3 admitted (401), 47 denied (429)", async () => {
    const ip = "198.51.100.5";
    const requests = Array.from({ length: 50 }, () => makeLoginRequest(ip, "wrong-password"));

    const responses = await Promise.all(requests.map((r) => POST(r)));
    const statuses = responses.map((r) => r.status);

    const admittedCount = statuses.filter((s) => s === 401).length;
    const rateLimitedCount = statuses.filter((s) => s === 429).length;

    expect(admittedCount).toBe(3);
    expect(rateLimitedCount).toBe(47);
    expect(statuses.length).toBe(50);
  });

  // 6. Requests arriving exactly at the configured threshold
  it("6. Requests arriving exactly at the configured threshold transition cleanly", async () => {
    const ip = "198.51.100.6";

    // 2 failed attempts
    expect((await POST(makeLoginRequest(ip, "wrong"))).status).toBe(401);
    expect((await POST(makeLoginRequest(ip, "wrong"))).status).toBe(401);

    // 3rd attempt is exactly at threshold: admitted (401), but sets lockout for next
    const third = await POST(makeLoginRequest(ip, "wrong"));
    expect(third.status).toBe(401);

    // 4th attempt is locked out: 429
    const fourth = await POST(makeLoginRequest(ip, "wrong"));
    expect(fourth.status).toBe(429);
  });

  // 7. Concurrent attempts from different identities
  it("7. Concurrent attempts from different identities do not interfere with each other", async () => {
    const ip1 = "198.51.100.11";
    const ip2 = "198.51.100.12";

    const reqs1 = Array.from({ length: 10 }, () => makeLoginRequest(ip1, "wrong"));
    const reqs2 = Array.from({ length: 10 }, () => makeLoginRequest(ip2, "wrong"));

    const [res1, res2] = await Promise.all([
      Promise.all(reqs1.map((r) => POST(r))),
      Promise.all(reqs2.map((r) => POST(r))),
    ]);

    const admitted1 = res1.filter((r) => r.status === 401).length;
    const denied1 = res1.filter((r) => r.status === 429).length;
    const admitted2 = res2.filter((r) => r.status === 401).length;
    const denied2 = res2.filter((r) => r.status === 429).length;

    expect(admitted1).toBe(3);
    expect(denied1).toBe(7);
    expect(admitted2).toBe(3);
    expect(denied2).toBe(7);
  });

  // 8. Requests during an existing lockout do not inflate counters or extend lockout indefinitely
  it("8. Requests during an existing lockout do not inflate counter or cause permanent lockout", async () => {
    const ip = "198.51.100.8";

    // Trigger lockout (3 failures)
    for (let i = 0; i < 3; i++) {
      await POST(makeLoginRequest(ip, "wrong"));
    }

    // Now in lockout. Blast 15 more attempts during lockout
    const barrage = Array.from({ length: 15 }, () => makeLoginRequest(ip, "wrong"));
    const responses = await Promise.all(barrage.map((r) => POST(r)));

    // All must be 429
    expect(responses.every((r) => r.status === 429)).toBe(true);

    // Admission backend must NOT have incremented count to 18
    const admissionCheck = await admitLoginAttempt(ip);
    expect(admissionCheck.allowed).toBe(false);
    expect(admissionCheck.count).toBe(3); // strictly clamped at 3, not 18!
  });

  // 9. Lockout expiration
  it("9. Lockout expiration allows subsequent attempt after cooldown passes", async () => {
    const ip = "198.51.100.9";
    const now = 20_000_000;

    // Direct admission check with explicit time
    const r1 = await admitLoginAttempt(ip, now);
    expect(r1.allowed).toBe(true);
    const r2 = await admitLoginAttempt(ip, now + 1);
    expect(r2.allowed).toBe(true);
    const r3 = await admitLoginAttempt(ip, now + 2);
    expect(r3.allowed).toBe(true);
    expect(r3.cooldown).toBe(1000);

    // Immediate attempt is denied
    const r4 = await admitLoginAttempt(ip, now + 50);
    expect(r4.allowed).toBe(false);
    expect(r4.retryAfterMs).toBeGreaterThan(0);

    // After cooldown expiration (1001ms later)
    const r5 = await admitLoginAttempt(ip, now + 1005);
    expect(r5.allowed).toBe(true);
  });

  // 10. Redis outage before admission (fail-closed policy vs fail-secure fallback)
  it("10. Redis outage before admission: fail-closed yields 503, fail-secure falls back safely", async () => {
    const originalFetch = global.fetch;

    try {
      // 10a. Fail-closed policy
      global.fetch = async (): Promise<Response> => {
        return new Response("Redis cluster unavailable", { status: 500 });
      };

      const failClosedBackend = new UpstashRedisThrottleBackend(
        "https://mock-redis.upstash.io",
        "mock-token",
        new InMemoryThrottleBackend(),
        1000,
        "fail-closed"
      );
      setCustomThrottleBackend(failClosedBackend);

      const resClosed = await POST(makeLoginRequest("198.51.100.10", "any-password"));
      expect(resClosed.status).toBe(503);
      const dataClosed = await resClosed.json();
      expect(dataClosed.error).toContain("Authentication service temporarily unavailable");

      // 10b. Fail-secure fallback policy
      const failSecureBackend = new UpstashRedisThrottleBackend(
        "https://mock-redis.upstash.io",
        "mock-token",
        new InMemoryThrottleBackend(),
        1000,
        "fail-secure-fallback"
      );
      setCustomThrottleBackend(failSecureBackend);

      const resFallback = await POST(makeLoginRequest("198.51.100.10", "any-password"));
      expect(resFallback.status).toBe(401); // Local fallback handles admission and password check
    } finally {
      global.fetch = originalFetch;
    }
  });

  // 11. Redis outage after admission
  it("11. Redis outage after admission during success clearance handles fail-closed gracefully", async () => {
    const originalFetch = global.fetch;
    let evalCount = 0;

    try {
      global.fetch = async (_input, init): Promise<Response> => {
        const bodyStr = typeof init?.body === "string" ? init.body : "";
        if (bodyStr.includes("freeAttempts")) {
          evalCount++;
          // First attempt EVAL succeeds
          return new Response(JSON.stringify({ result: [1, 0, 1, 0] }), { status: 200 });
        }
        // Failure when clearing token on success
        return new Response("Redis DEL error", { status: 500 });
      };

      const backend = new UpstashRedisThrottleBackend(
        "https://mock-redis.upstash.io",
        "mock-token",
        new InMemoryThrottleBackend(),
        1000,
        "fail-closed"
      );
      setCustomThrottleBackend(backend);

      const res = await POST(makeLoginRequest("198.51.100.20", PASSWORD));
      expect(evalCount).toBe(1);
      // Fail-closed catches store error and returns 503 rather than uncaught rejection
      expect(res.status).toBe(503);
    } finally {
      global.fetch = originalFetch;
    }
  });

  // 12. Malformed Redis responses
  it("12. Malformed Redis responses are handled without state corruption", async () => {
    const originalFetch = global.fetch;

    try {
      // Upstash returns unexpected string or object
      global.fetch = async (): Promise<Response> => {
        return new Response(JSON.stringify({ unexpected: "structure" }), { status: 200 });
      };

      const backend = new UpstashRedisThrottleBackend(
        "https://mock-redis.upstash.io",
        "mock-token",
        new InMemoryThrottleBackend(),
        1000,
        "fail-secure-fallback"
      );
      setCustomThrottleBackend(backend);

      // Falls back to in-memory store cleanly
      const res = await POST(makeLoginRequest("198.51.100.22", "wrong"));
      expect(res.status).toBe(401);
    } finally {
      global.fetch = originalFetch;
    }
  });

  // 13. Redis timeout
  it("13. Redis timeout aborts cleanly and triggers configured policy", async () => {
    const originalFetch = global.fetch;

    try {
      global.fetch = async (_input, init): Promise<Response> => {
        return new Promise((_resolve, reject) => {
          if (init?.signal) {
            init.signal.addEventListener("abort", () => {
              reject(new Error("The operation was aborted due to timeout"));
            });
          }
        });
      };

      const backend = new UpstashRedisThrottleBackend(
        "https://mock-redis.upstash.io",
        "mock-token",
        new InMemoryThrottleBackend(),
        50, // 50ms timeout
        "fail-closed"
      );
      setCustomThrottleBackend(backend);

      const res = await POST(makeLoginRequest("198.51.100.23", "wrong"));
      expect(res.status).toBe(503);
    } finally {
      global.fetch = originalFetch;
    }
  });

  // 14. Successful login after cooldown
  it("14. Successful login after cooldown succeeds and resets all throttle counters", async () => {
    const ip = "198.51.100.24";
    const now = 30_000_000;

    // Trigger lockout
    await admitLoginAttempt(ip, now);
    await admitLoginAttempt(ip, now);
    const r3 = await admitLoginAttempt(ip, now);
    expect(r3.allowed).toBe(true);
    expect(r3.cooldown).toBe(1000);

    // Locked out at now + 500
    expect((await admitLoginAttempt(ip, now + 500)).allowed).toBe(false);

    // Cooldown elapses (now + 1200) -> submit correct password
    // Backend reset on success
    await recordThrottleSuccess(ip);
    expect(await getThrottleDelay(ip, now + 1200)).toBe(0);

    // Next attempt is treated as fresh
    const freshAttempt = await admitLoginAttempt(ip, now + 1200);
    expect(freshAttempt.allowed).toBe(true);
    expect(freshAttempt.count).toBe(1);
  });

  // 15. Upstash Lua EVAL concurrent simulator verification
  it("15. Upstash Lua EVAL simulator admits exactly 3 concurrent requests across 50 simultaneous calls", async () => {
    const originalFetch = global.fetch;
    const sim = createUpstashSimulator();
    global.fetch = sim.mockFetch as unknown as typeof fetch;

    try {
      const backend = new UpstashRedisThrottleBackend(
        "https://mock-upstash.local",
        "test-token",
        new InMemoryThrottleBackend(),
        3000,
        "fail-closed"
      );
      setCustomThrottleBackend(backend);

      const testIp = "203.0.113.99";
      const requests = Array.from({ length: 50 }, () => makeLoginRequest(testIp, "wrong-password"));

      const responses = await Promise.all(requests.map((r) => POST(r)));
      const statuses = responses.map((r) => r.status);

      const admitted401 = statuses.filter((s) => s === 401).length;
      const denied429 = statuses.filter((s) => s === 429).length;

      expect(admitted401).toBe(3);
      expect(denied429).toBe(47);
      expect(sim.calls.filter((c) => c === "EVAL").length).toBe(50);
    } finally {
      global.fetch = originalFetch;
    }
  });
});

/**
 * W-04 (AUDIT_2026-10-19). Off Vercel every caller shares the "unknown" bucket
 * unless the operator names a trusted proxy header.
 */
describe("self-hosted (no Vercel edge) caller keys", () => {
  function selfHostedRequest(password: string, headers: Record<string, string> = {}): NextRequest {
    const fd = new FormData();
    fd.set("password", password);
    fd.set("from", "/");
    return new NextRequest("http://localhost:3000/api/login", { method: "POST", headers, body: fd });
  }

  beforeEach(() => {
    vi.stubEnv("SITE_PASSWORD", PASSWORD);
    vi.stubEnv("SESSION_SECRET", SECRET);
    vi.stubEnv("VERCEL", "");
    vi.stubEnv("TRUSTED_PROXY_HEADER", "");
    resetThrottle();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    resetThrottle();
  });

  it("logins in the shared bucket keep working: a success still resets it", async () => {
    // Admission counts every attempt, successes included. Without the reset,
    // four correct logins in 15 minutes would throttle everyone.
    for (let i = 0; i < 5; i++) {
      expect((await POST(selfHostedRequest(PASSWORD))).status).toBe(200);
    }
  });

  it("with TRUSTED_PROXY_HEADER set, each client address gets its own bucket", async () => {
    vi.stubEnv("TRUSTED_PROXY_HEADER", "x-forwarded-for");
    const attacker = { "x-forwarded-for": "203.0.113.7" };
    for (let i = 0; i < 3; i++) {
      expect((await POST(selfHostedRequest("guess", attacker))).status).toBe(401);
    }
    expect((await POST(selfHostedRequest("guess", attacker))).status).toBe(429);

    // A different client behind the same proxy is unaffected.
    const user = { "x-forwarded-for": "198.51.100.20" };
    expect((await POST(selfHostedRequest(PASSWORD, user))).status).toBe(200);
  });
});
