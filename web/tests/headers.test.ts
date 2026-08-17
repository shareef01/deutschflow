import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import nextConfig from "../next.config";

/**
 * The security headers are declared twice, and must not drift.
 *
 * They live in next.config.ts so they travel with the build — a plain
 * `next start`, or any host that is not Vercel, used to serve none of them,
 * which matters for a page holding a decryptable API key. vercel.json keeps its
 * own copy for the assets the CDN answers without invoking Next.
 *
 * Two declarations of one policy is a drift waiting to happen, and the drift
 * would be silent and security-relevant: the weaker of the two would simply win
 * wherever it was consulted. This is the thing that notices.
 */

type HeaderEntry = { key: string; value: string };

async function nextHeaders(): Promise<HeaderEntry[]> {
  const routes = await nextConfig.headers!();
  return routes.flatMap((route) => route.headers as HeaderEntry[]);
}

function vercelHeaders(): HeaderEntry[] {
  const path = resolve(__dirname, "../vercel.json");
  const parsed = JSON.parse(readFileSync(path, "utf8")) as {
    headers: { source: string; headers: HeaderEntry[] }[];
  };
  return parsed.headers.flatMap((rule) => rule.headers);
}

function valueOf(headers: HeaderEntry[], key: string): string | undefined {
  return headers.find((h) => h.key.toLowerCase() === key.toLowerCase())?.value;
}

const SECURITY_HEADERS = [
  "Content-Security-Policy",
  "X-Frame-Options",
  "X-Content-Type-Options",
  "Referrer-Policy",
];

describe("security headers are declared identically in both places", () => {
  for (const key of SECURITY_HEADERS) {
    it(`${key} matches between next.config.ts and vercel.json`, async () => {
      const fromNext = valueOf(await nextHeaders(), key);
      const fromVercel = valueOf(vercelHeaders(), key);

      expect(fromNext, `${key} missing from next.config.ts`).toBeDefined();
      expect(fromVercel, `${key} missing from vercel.json`).toBeDefined();
      expect(fromNext).toBe(fromVercel);
    });
  }
});

describe("the policy itself", () => {
  it("allows the Groq endpoint and nothing else off-origin", async () => {
    const csp = valueOf(await nextHeaders(), "Content-Security-Policy")!;

    // The one host the app talks to. A second origin appearing here should be a
    // deliberate decision, not a diff nobody read.
    expect(csp).toContain("connect-src 'self' https://api.groq.com");
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
  });

  it("still applies to every route", async () => {
    const routes = await nextConfig.headers!();
    expect(routes.some((route) => route.source === "/:path*")).toBe(true);
  });
});
