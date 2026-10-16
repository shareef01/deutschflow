import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/session";

/**
 * Logout Route Handler — explicit server-side session termination.
 *
 * Logout invalidates the active browser's session by clearing the HttpOnly
 * `df_access` cookie with `maxAge: 0` and `expires: Epoch 0`.
 *
 * Architecture Note on Session Revocation:
 * Because DeutschFlow session tokens are stateless signed HMAC tokens (<expiry>.<hmac>),
 * clearing the HttpOnly cookie terminates the session for this browser client.
 * Server-side revocation of all outstanding session tokens across all devices
 * requires incrementing SESSION_EPOCH or rotating SESSION_SECRET / SITE_PASSWORD.
 */
function isHttpsRequest(request?: Request): boolean {
  if (!request) return false;
  const forwardedProto = request.headers.get("x-forwarded-proto");
  if (forwardedProto) {
    return forwardedProto.split(",")[0].trim().toLowerCase() === "https";
  }
  return request.url.startsWith("https://");
}

export function isAllowedOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (!origin) return true;
  try {
    const originUrl = new URL(origin);
    const requestUrl = new URL(request.url);
    return originUrl.origin === requestUrl.origin;
  } catch {
    return false;
  }
}

function clearSessionCookie(response: NextResponse, request?: Request): NextResponse {
  const isSecure = process.env.NODE_ENV === "production" && isHttpsRequest(request);
  response.cookies.set(SESSION_COOKIE, "", {
    httpOnly: true,
    secure: isSecure,
    sameSite: "lax",
    maxAge: 0,
    expires: new Date(0),
    path: "/",
  });
  response.headers.set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
  return response;
}

export async function POST(request: Request) {
  if (!isAllowedOrigin(request)) {
    return NextResponse.json({ error: "Forbidden: cross-origin logout request" }, { status: 403 });
  }
  const url = new URL("/login", request.url);
  const response = NextResponse.json({ success: true, redirect: url.pathname });
  return clearSessionCookie(response, request);
}

export async function GET(request: Request) {
  const url = new URL("/login", request.url);
  const response = NextResponse.redirect(url);
  return clearSessionCookie(response, request);
}
