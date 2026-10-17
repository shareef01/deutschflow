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

export function extractCallerKey(
  headers: Headers,
  isVercel: boolean = process.env.VERCEL === "1"
): string {
  // If not on Vercel or headers lack provider edge signature, do not trust caller headers.
  if (!isVercel) return "unknown";

  // Vercel Edge writes x-vercel-id on all requests that traversed its edge infrastructure.
  // If x-vercel-id is absent, the environment variable cannot be used as sole proof of edge trust.
  const vercelId = headers.get("x-vercel-id");
  if (!vercelId) {
    return "unknown";
  }

  // x-real-ip is overwritten by Vercel edge to the connecting socket IP, avoiding client prepending.
  const realIp = headers.get("x-real-ip")?.trim();
  if (realIp) return realIp;

  const forwarded = headers.get("x-forwarded-for");
  return forwarded?.split(",")[0]?.trim() || "unknown";
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
