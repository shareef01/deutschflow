import { createHash } from "node:crypto";
import { sessionTokenExpiry } from "./session";

const processState = globalThis as typeof globalThis & {
  deutschflowRevokedSessions?: Map<string, number>;
};
const revokedLocally = processState.deutschflowRevokedSessions ??= new Map();

export function requiresPublicAuthInfrastructure(): boolean {
  return process.env.NODE_ENV === "production"
    && (process.env.VERCEL === "1" || process.env.DEUTSCHFLOW_LOCAL_AUTH !== "1");
}

export function authRedisCredentials() {
  const pairs = [
    [process.env.UPSTASH_REDIS_REST_URL, process.env.UPSTASH_REDIS_REST_TOKEN],
    [process.env.REDIS_REST_URL, process.env.REDIS_REST_TOKEN],
    [process.env.KV_REST_API_URL, process.env.KV_REST_API_TOKEN],
  ];
  const configured = pairs.find(([url, token]) => url?.trim() || token?.trim());
  const url = configured?.[0]?.trim();
  const token = configured?.[1]?.trim();
  if (!url || !token) return null;
  try {
    return new URL(url).protocol === "https:" ? { url, token } : null;
  } catch {
    return null;
  }
}

async function redisCommand(command: (string | number)[]): Promise<unknown> {
  const credentials = authRedisCredentials();
  if (!credentials) {
    throw new Error("An HTTPS Redis session store is required.");
  }
  const response = await fetch(credentials.url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${credentials.token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(command),
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(3_000),
  });
  if (!response.ok) throw new Error("Session store request failed.");
  const data: unknown = await response.json();
  if (!data || typeof data !== "object" || !("result" in data) || "error" in data) {
    throw new Error("Invalid session store response.");
  }
  return data.result;
}

function revocationKey(token: string): string {
  return `deutschflow:revoked:${createHash("sha256").update(token).digest("hex")}`;
}

export async function isSessionRevoked(token: string): Promise<boolean> {
  const key = revocationKey(token);
  if (authRedisCredentials() || requiresPublicAuthInfrastructure()) {
    // Route the lookup to the primary. XX leaves absent keys absent and
    // KEEPTTL preserves the expiry for revocations that already exist.
    return (await redisCommand(["SET", key, "1", "XX", "KEEPTTL", "GET"])) !== null;
  }
  return (revokedLocally.get(key) ?? 0) > Date.now();
}

/** Call only after verifying the signature, to keep external callers from allocating state. */
export async function revokeSession(token: string): Promise<void> {
  const expiry = sessionTokenExpiry(token);
  const now = Date.now();
  if (expiry === null || expiry <= now) return;
  const key = revocationKey(token);
  if (authRedisCredentials() || requiresPublicAuthInfrastructure()) {
    if (await redisCommand(["SET", key, "1", "PXAT", expiry]) !== "OK") {
      throw new Error("Session revocation failed.");
    }
    return;
  }
  for (const [storedKey, storedExpiry] of revokedLocally) {
    if (storedExpiry <= now) revokedLocally.delete(storedKey);
  }
  revokedLocally.set(key, expiry);
}
