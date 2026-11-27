/**
 * Origin validation for state-changing authentication endpoints (login, logout).
 *
 * Compares the browser-supplied `Origin` header against the request's own origin.
 * When deployed behind Vercel or an explicitly configured reverse proxy
 * (`TRUSTED_PROXY_HEADER`), also accepts the public origin reconstructed from
 * `X-Forwarded-Proto` + `X-Forwarded-Host` (or `Host`), preventing false 403s
 * when a TLS-terminating proxy forwards HTTPS traffic to an internal HTTP backend.
 */
export function isAllowedOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (!origin) return true;
  try {
    const originUrl = new URL(origin);
    const requestUrl = new URL(request.url);
    if (originUrl.origin === requestUrl.origin) {
      return true;
    }

    const behindTrustedProxy =
      process.env.VERCEL === "1" || Boolean(process.env.TRUSTED_PROXY_HEADER?.trim());
    if (!behindTrustedProxy) {
      return false;
    }

    const forwardedProto = request.headers
      .get("x-forwarded-proto")
      ?.split(",")[0]
      ?.trim()
      .toLowerCase();
    const proto =
      forwardedProto === "https" || forwardedProto === "http"
        ? forwardedProto
        : requestUrl.protocol.replace(":", "");

    const rawHost =
      request.headers.get("x-forwarded-host")?.split(",")[0]?.trim() ||
      request.headers.get("host")?.trim();
    if (!rawHost || /[\s/\\@]/.test(rawHost)) {
      return false;
    }

    const forwardedOrigin = new URL(`${proto}://${rawHost}`).origin;
    return originUrl.origin === forwardedOrigin;
  } catch {
    return false;
  }
}
