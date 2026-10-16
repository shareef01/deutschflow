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
function clearSessionCookie(response: NextResponse): NextResponse {
  response.cookies.set(SESSION_COOKIE, "", {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    maxAge: 0,
    expires: new Date(0),
    path: "/",
  });
  response.headers.set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
  return response;
}

export async function POST(request: Request) {
  const url = new URL("/login", request.url);
  const response = NextResponse.json({ success: true, redirect: url.pathname });
  return clearSessionCookie(response);
}

export async function GET(request: Request) {
  const url = new URL("/login", request.url);
  const response = NextResponse.redirect(url);
  return clearSessionCookie(response);
}
