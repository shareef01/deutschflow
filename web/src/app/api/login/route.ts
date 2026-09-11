import { NextRequest, NextResponse } from "next/server";
import {
  SESSION_COOKIE,
  SESSION_MAX_AGE_SECONDS,
  createSessionToken,
  passwordMatches,
  safeRedirectTarget,
  sessionSecret,
  sitePassword,
} from "@/lib/auth/session";
import { delayForNextAttempt, recordFailure, recordSuccess } from "@/lib/auth/throttle";

function callerKey(request: NextRequest): string {
  // Vercel replaces this header at its trusted boundary. On direct-to-Node
  // hosting it is caller-controlled, so do not trust it: use one shared bucket.
  if (process.env.VERCEL !== "1") return "unknown";
  const forwarded = request.headers.get("x-forwarded-for");
  return forwarded?.split(",")[0]?.trim() || "unknown";
}

export async function POST(request: NextRequest) {
  const masterPassword = sitePassword();
  const signingSecret = sessionSecret();
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
  const caller = callerKey(request);
  const retryAfterMs = delayForNextAttempt(caller);

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
    recordFailure(caller);
    return NextResponse.json(
      { error: "That key doesn't match. Check for stray spaces and try again." },
      { status: 401 }
    );
  }

  recordSuccess(caller);
  const response = NextResponse.json({ redirect: target });
  response.cookies.set(SESSION_COOKIE, await createSessionToken(signingSecret), {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    maxAge: SESSION_MAX_AGE_SECONDS,
    path: "/",
  });
  return response;
}
