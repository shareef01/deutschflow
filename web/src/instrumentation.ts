/**
 * Runs once when a server instance starts. The only job here is to flag weak
 * configuration in the deploy logs, where an operator will see it; nothing in
 * the request path should pay for the check.
 */
export async function register(): Promise<void> {
  // The edge runtime has no use for the warning and would log it a second time.
  if (process.env.NEXT_RUNTIME !== "nodejs") return;
  const { sitePasswordWarning } = await import("@/lib/auth/session");
  const warning = sitePasswordWarning();
  if (warning) console.warn(`[deutschflow] ${warning}`);
}
