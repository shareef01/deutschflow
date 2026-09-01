"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  SESSION_COOKIE,
  SESSION_MAX_AGE_SECONDS,
  createSessionToken,
  safeRedirectTarget,
  sitePassword,
  timingSafeEqual,
} from "@/lib/auth/session";

/**
 * Checks the submitted key against SITE_PASSWORD and, on a match, issues a
 * signed session cookie (see lib/auth/session.ts for why it is signed).
 */
export async function login(prevState: { error: string } | null, formData: FormData) {
  const password = (formData.get("password") as string)?.trim();
  const target = safeRedirectTarget(formData.get("from"));

  const masterPassword = sitePassword();
  if (!masterPassword) {
    return { error: "This instance has no security key configured. Set SITE_PASSWORD and redeploy." };
  }

  // Constant-time, like verifySessionToken two functions along in session.ts. A
  // short-circuiting `!==` leaks the length and the matching prefix; barely
  // exploitable across a network, but there is no reason for the gate's two
  // comparisons to disagree about whether that matters.
  if (!timingSafeEqual(password ?? "", masterPassword)) {
    return { error: "That key doesn't match. Check for stray spaces and try again." };
  }

  const cookieStore = await cookies();
  cookieStore.set(SESSION_COOKIE, await createSessionToken(masterPassword), {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    maxAge: SESSION_MAX_AGE_SECONDS,
    path: "/",
  });

  redirect(target);
}
