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
  getThrottleDelay,
  recordThrottleFailure,
  recordThrottleSuccess,
} from "@/lib/auth/throttle";

export function extractCallerKey(headers: Headers, isVercel: boolean = process.env.VERCEL === "1"): string {
  // Vercel replaces this header at its trusted edge boundary. On direct-to-Node
  // hosting it is caller-controlled, so do not trust it: use one shared bucket.
  if (!isVercel) return "unknown";
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
  const retryAfterMs = await getThrottleDelay(caller);

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
    await recordThrottleFailure(caller);
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
