import { NextRequest, NextResponse } from "next/server";
import {
  SESSION_COOKIE,
  createSessionToken,
  getActiveSigningSecret,
  getSessionMaxAgeSeconds,
  passwordMatches,
  safeRedirectTarget,
  sitePassword,
} from "@/lib/auth/session";
import {
  ThrottleStoreError,
  admitLoginAttempt,
  recordThrottleSuccess,
} from "@/lib/auth/throttle";

import { isIP } from "node:net";

/**
 * Standards-compliant IPv4 / IPv6 syntax validator using Node.js libuv parser (node:net.isIP).
 * Returns true if and only if the input is a valid IPv4 or IPv6 address.
 * Rejects malformed addresses, trailing characters, octets > 255, and injection tokens.
 */
export function isValidIp(ip: string | null | undefined): boolean {
  if (!ip || typeof ip !== "string") return false;
  const trimmed = ip.trim();
  if (trimmed.length < 2 || trimmed.length > 45) return false;
  return isIP(trimmed) !== 0;
}

export function extractCallerKey(
  headers: Headers,
  isVercel: boolean = process.env.VERCEL === "1"
): string {
  // Trust Boundary:
  // Outside Vercel (e.g. self-hosted Node.js / Docker), incoming proxy headers (x-forwarded-for,
  // x-real-ip) are client-controlled and untrusted by default unless an internal reverse proxy
  // has been explicitly configured. Without this check, untrusted clients could arbitrarily
  // choose rate-limit identities or spoof other users' IPs.
  // Note: If deployed behind additional proxies (e.g. Cloudflare -> Vercel), x-real-ip
  // reflects the upstream proxy's egress IP unless custom trusted proxy depth is configured.
  if (!isVercel) return "unknown";

  // x-vercel-id is an internal routing trace header assigned by Vercel edge infrastructure
  // (e.g. 'fra1::iad1::...'). It is NOT an authenticated cryptographic signature, but its
  // presence in a Vercel runtime environment indicates the request traversed Vercel edge routing.
  const vercelId = headers.get("x-vercel-id");
  if (!vercelId || !vercelId.trim()) {
    return "unknown";
  }

  // 1. On Vercel, x-real-ip is set by the edge to the connecting socket IP.
  // When present and valid, it is authoritative over forwarded chains.
  const realIp = headers.get("x-real-ip")?.trim();
  if (realIp && isValidIp(realIp)) {
    return realIp;
  }

  // 2. Fallback to x-forwarded-for:
  // In reverse proxies, incoming client-supplied headers are prepended and the proxy appends
  // the true connecting IP to the end of the chain. To prevent client-prepended spoofing,
  // take the rightmost (last) valid hop appended by the edge proxy.
  const forwarded = headers.get("x-forwarded-for");
  if (forwarded) {
    const hops = forwarded
      .split(",")
      .map((h) => h.trim())
      .filter(Boolean);
    const lastHop = hops[hops.length - 1];
    if (lastHop && isValidIp(lastHop)) {
      return lastHop;
    }
  }

  return "unknown";
}

export async function POST(request: NextRequest) {
  const masterPassword = sitePassword();
  const signingSecret = await getActiveSigningSecret();
  if (!masterPassword || !signingSecret) {
    return NextResponse.json(
      { error: "This instance is not fully configured. Set SITE_PASSWORD and SESSION_SECRET, then redeploy." },
      { status: 503 }
    );
  }

  const formData = await request.formData();
  const password = typeof formData.get("password") === "string"
    ? String(formData.get("password")).trim()
    : "";
  const target = safeRedirectTarget(formData.get("from"));
  const caller = extractCallerKey(request.headers);

  let admission;
  try {
    admission = await admitLoginAttempt(caller);
  } catch (err) {
    if (err instanceof ThrottleStoreError) {
      return NextResponse.json(
        { error: "Authentication service temporarily unavailable. Distributed rate limiter store is unreachable." },
        { status: 503 }
      );
    }
    throw err;
  }

  if (!admission.allowed) {
    const retryAfterSeconds = Math.max(1, Math.ceil(admission.retryAfterMs / 1000));
    return NextResponse.json(
      { error: `Too many attempts. Try again in ${retryAfterSeconds} seconds.` },
      {
        status: 429,
        headers: { "Retry-After": String(retryAfterSeconds) },
      }
    );
  }

  if (!(await passwordMatches(password, masterPassword))) {
    return NextResponse.json(
      { error: "That key doesn't match. Check for stray spaces and try again." },
      { status: 401 }
    );
  }

  const forwardedProto = request.headers.get("x-forwarded-proto");
  const isHttps = forwardedProto
    ? forwardedProto.split(",")[0].trim().toLowerCase() === "https"
    : request.url.startsWith("https://");
  const isSecure = process.env.NODE_ENV === "production" && isHttps;

  try {
    await recordThrottleSuccess(caller);
  } catch (err) {
    if (err instanceof ThrottleStoreError) {
      return NextResponse.json(
        { error: "Authentication service temporarily unavailable. Distributed rate limiter store is unreachable." },
        { status: 503 }
      );
    }
    throw err;
  }
  const maxAge = getSessionMaxAgeSeconds();
  const response = NextResponse.json({ redirect: target });
  response.cookies.set(SESSION_COOKIE, await createSessionToken(signingSecret, Date.now(), maxAge), {
    httpOnly: true,
    secure: isSecure,
    sameSite: "lax",
    maxAge,
    path: "/",
  });
  return response;
}
