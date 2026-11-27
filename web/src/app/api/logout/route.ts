import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE, getActiveSigningSecret, verifySessionToken } from "@/lib/auth/session";
import { requiresPublicAuthInfrastructure, revokeSession } from "@/lib/auth/revocation";
import { isAllowedOrigin } from "@/lib/auth/origin";

export { isAllowedOrigin };

/**
 * Logout Route Handler — explicit server-side session termination.
 *
 * Logout invalidates the active browser's session by clearing the HttpOnly
 * `df_access` cookie with `maxAge: 0` and `expires: Epoch 0`.
 *
 * A verified token is revoked before the response clears the cookie.
 */
function isHttpsRequest(request?: Request): boolean {
  if (!request) return false;
  const forwardedProto = request.headers.get("x-forwarded-proto");
  if (forwardedProto) {
    return forwardedProto.split(",")[0].trim().toLowerCase() === "https";
  }
  return request.url.startsWith("https://");
}

function readSessionCookie(request: NextRequest | Request): string | undefined {
  if ("cookies" in request && request.cookies && typeof request.cookies.get === "function") {
    return request.cookies.get(SESSION_COOKIE)?.value;
  }
  const cookie = request.headers.get("cookie")?.split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${SESSION_COOKIE}=`));
  const raw = cookie?.slice(SESSION_COOKIE.length + 1);
  if (!raw) return undefined;
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

function clearSessionCookie(response: NextResponse, request?: Request): NextResponse {
  const isSecure = requiresPublicAuthInfrastructure() || (process.env.NODE_ENV === "production" && isHttpsRequest(request));
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

export async function POST(request: NextRequest | Request) {
  if (!isAllowedOrigin(request)) {
    return NextResponse.json({ error: "Forbidden: cross-origin logout request" }, { status: 403 });
  }
  const token = readSessionCookie(request);
  if (token) {
    const signingSecret = await getActiveSigningSecret();
    if (signingSecret && await verifySessionToken(token, signingSecret)) {
      try {
        await revokeSession(token);
      } catch {
        return NextResponse.json({ error: "Session could not be revoked. Try again." }, {
          status: 503,
          headers: { "Cache-Control": "no-store" },
        });
      }
    }
  }
  const url = new URL("/login", request.url);
  const response = NextResponse.json({ success: true, redirect: url.pathname });
  return clearSessionCookie(response, request);
}

export async function GET() {
  return NextResponse.json(
    { error: "Method Not Allowed. Logout must use POST with CSRF verification." },
    {
      status: 405,
      headers: {
        Allow: "POST",
        "Cache-Control": "no-store, no-cache, must-revalidate",
      },
    }
  );
}
