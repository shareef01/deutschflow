import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/session";

function clearSessionCookie(response: NextResponse): NextResponse {
  response.cookies.set(SESSION_COOKIE, "", {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    maxAge: 0,
    path: "/",
  });
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
