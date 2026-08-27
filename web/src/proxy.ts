import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { SESSION_COOKIE, sitePassword, verifySessionToken } from "@/lib/auth/session";

/**
 * The access gate, run by the network-boundary proxy (the convention that
 * replaced Next 15's middleware; its runtime is nodejs).
 *
 * Everything is private unless it appears in [PUBLIC_PREFIXES]. API routes are
 * deliberately *not* exempt: the matcher used to exclude them wholesale, which
 * meant the first route anyone added would have been public by default, with
 * nothing in the code to say so.
 */
const PUBLIC_PREFIXES = [
  "/login",
  "/_next", // build output; the CDN serves it regardless of this gate
  "/icons",
  "/manifest.json",
  "/sw.js",
  "/favicon.ico",
];

function isPublic(pathname: string): boolean {
  return PUBLIC_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`)
  );
}

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (isPublic(pathname)) return NextResponse.next();

  const secret = sitePassword();
  if (!secret) {
    // No key configured is a closed door, not an open one — an unconfigured
    // deployment must not be a public one.
    return NextResponse.redirect(new URL("/login", request.url));
  }

  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (await verifySessionToken(token, secret)) return NextResponse.next();

  const url = request.nextUrl.clone();
  url.pathname = "/login";
  url.search = "";
  // Preserved so the user lands where they were headed; validated on the way
  // back out in `safeRedirectTarget`.
  url.searchParams.set("from", pathname);
  return NextResponse.redirect(url);
}

export const config = {
  matcher: "/((?!_next/static|_next/image).*)",
};
