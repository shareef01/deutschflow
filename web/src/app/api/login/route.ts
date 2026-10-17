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
  getThrottleDelay,
  recordThrottleFailure,
  recordThrottleSuccess,
} from "@/lib/auth/throttle";

/**
 * Strict IPv4 / IPv6 syntax validator.
 * Ensures caller key is a syntactically valid IP address and prevents
 * untrusted client-controlled tokens from arbitrarily picking rate-limit identities.
 */
export function isValidIp(ip: string | null | undefined): boolean {
  if (!ip || typeof ip !== "string") return false;
  const trimmed = ip.trim();
  if (trimmed.length < 3 || trimmed.length > 45) return false;

  // IPv4: 4 octets 0-255 separated by dots
  const ipv4Regex = /^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$/;
  if (ipv4Regex.test(trimmed)) return true;

  // IPv6: standard and compressed notation
  const ipv6Regex = /^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^(([0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4})?::(([0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4})?$/;
  return ipv6Regex.test(trimmed);
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

  // On Vercel, x-real-ip is overwritten by the edge proxy to the connecting socket IP,
  // preventing client-prepended entries.
  const realIp = headers.get("x-real-ip")?.trim();
  if (realIp && isValidIp(realIp)) {
    return realIp;
  }

  // Fallback to first hop of x-forwarded-for if valid IP
  const forwarded = headers.get("x-forwarded-for");
  const firstHop = forwarded?.split(",")[0]?.trim();
  if (firstHop && isValidIp(firstHop)) {
    return firstHop;
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

  let retryAfterMs: number;
  try {
    retryAfterMs = await getThrottleDelay(caller);
  } catch (err) {
    if (err instanceof ThrottleStoreError) {
      return NextResponse.json(
        { error: "Authentication service temporarily unavailable. Distributed rate limiter store is unreachable." },
        { status: 503 }
      );
    }
    throw err;
  }

  if (retryAfterMs > 0) {
    const retryAfterSeconds = Math.ceil(retryAfterMs / 1000);
    return NextResponse.json(
      { error: `Too many attempts. Try again in ${retryAfterSeconds} seconds.` },
      {
        status: 429,
        headers: { "Retry-After": String(retryAfterSeconds) },
      }
    );
  }

  if (!(await passwordMatches(password, masterPassword))) {
    try {
      await recordThrottleFailure(caller);
    } catch (err) {
      if (err instanceof ThrottleStoreError) {
        return NextResponse.json(
          { error: "Authentication service temporarily unavailable. Distributed rate limiter store is unreachable." },
          { status: 503 }
        );
      }
      throw err;
    }
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

  await recordThrottleSuccess(caller);
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
