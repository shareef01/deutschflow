import type { NextConfig } from "next";

/**
 * DeutschFlow PWA build config.
 *
 * The app is local-first: every byte of user data lives in IndexedDB (Dexie),
 * speech is the browser's on-device engine, and the only network call is the
 * Groq translation request made directly from the client. There is therefore no
 * server-side data, no SSR state, and no route-level fetching — the PWA service
 * worker (Phase 6) will precache the static build output for offline boot.
 */
/**
 * Security headers travel with the build, not with the host.
 *
 * These used to live only in vercel.json, so `next start` — and any host that is
 * not Vercel — served the app with no CSP, no frame protection and no referrer
 * policy at all. The page holds a decryptable API key; its headers should be a
 * property of the application. vercel.json still declares them so the CDN sets
 * them on static assets it serves without invoking Next, and the two are identical
 * on purpose.
 *
 * `'unsafe-inline'` in script-src is Next's requirement for its bootstrap, and it
 * is the boundary of what the key vault protects: the vault defends a copied
 * profile directory, not script running on this origin. That trade is stated in
 * the README's privacy section rather than left for a reader to infer.
 *
 * Both documented ways out were tried, and both cost more than they buy here:
 *
 * - A nonce needs `proxy.ts` to mint one per request, and Next says plainly that
 *   "to use a nonce, your page must be dynamically rendered" - static
 *   optimisation off, no CDN caching. This app prerenders all ten routes and
 *   boots offline from a service-worker precache; it holds no server-side state
 *   at all, so paying for per-request rendering would dismantle the thing that
 *   makes it work offline.
 *
 * - `experimental.sri` keeps the routes static and does add integrity hashes to
 *   the six external chunks. It does not help: the prerendered HTML also carries
 *   eight *inline* bootstrap scripts, and an inline script cannot have an
 *   integrity attribute. Dropping 'unsafe-inline' with SRI on builds fine and
 *   then fails in the browser - hydration never runs and the service worker
 *   never registers, which the Playwright smoke suite catches.
 *
 * What makes the trade acceptable is the absence of a way in: the app renders no
 * untrusted HTML (no dangerouslySetInnerHTML, no innerHTML, no eval, no
 * document.write) and loads no third-party script. The only inline script on the
 * page is Next's own. Revisit if any of those stops being true.
 */
const CONTENT_SECURITY_POLICY = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  // The Groq endpoint is the only host the app ever talks to.
  "connect-src 'self' https://api.groq.com",
  "worker-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join("; ");

const SECURITY_HEADERS = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Content-Security-Policy", value: CONTENT_SECURITY_POLICY },
];

const nextConfig: NextConfig = {
  reactStrictMode: true,
  async headers() {
    return [{ source: "/:path*", headers: SECURITY_HEADERS }];
  },
};

export default nextConfig;
