"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import {
  SESSION_COOKIE,
  SESSION_MAX_AGE_SECONDS,
  createSessionToken,
  safeRedirectTarget,
  sitePassword,
  timingSafeEqual,
} from "@/lib/auth/session";
import { delayForNextAttempt, recordFailure, recordSuccess } from "@/lib/auth/throttle";

/** Sleeps, so a throttled caller's guess costs them the wait rather than nothing. */
function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Who is guessing.
 *
 * The forwarded client IP where the platform provides one; a single shared bucket
 * otherwise, which throttles everyone together rather than nobody. Trusting
 * x-forwarded-for is only safe behind a proxy that sets it, which is what this app
 * deploys to — direct-to-Node hosting would need this narrowed.
 */
async function callerKey(): Promise<string> {
  const forwarded = (await headers()).get("x-forwarded-for");
  return forwarded?.split(",")[0]?.trim() || "unknown";
}

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
  const caller = await callerKey();

  // Paid before the comparison, so a throttled caller waits whether or not the
  // guess was right — otherwise the delay itself would tell them something.
  const delay = delayForNextAttempt(caller);
  if (delay > 0) await pause(delay);

  if (!timingSafeEqual(password ?? "", masterPassword)) {
    recordFailure(caller);
    return { error: "That key doesn't match. Check for stray spaces and try again." };
  }

  recordSuccess(caller);

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
