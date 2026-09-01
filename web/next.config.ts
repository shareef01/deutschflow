import type { NextConfig } from "next";

/**
 * DeutschFlow PWA build config.
 *
 * The app is local-first for *data*: every byte of user data lives in IndexedDB
 * (Dexie), and there is no server-side state, no SSR data and no route-level
 * fetching — which is what lets the service worker precache the static build
 * output and boot offline.
 *
 * It is not local-first for *speech*. Recognition is the browser's own engine, and
 * Chrome, Edge and Safari all send the captured audio to their vendor. That is
 * stated in PWA_BLUEPRINT.md §7 and surfaced to the user in Settings, because it
 * is the one privacy property where this app differs from the Android one.
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
 * profile directory, not script running on this origin. That trade is stated at
 * the top of lib/db/vault.ts rather than left for a reader to infer.
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

/**
 * The microphone is the only powerful feature this app uses, and only from its own
 * pages. Naming it here stops an embedded third party (there are none today) from
 * inheriting the grant.
 */
const PERMISSIONS_POLICY = "microphone=(self), camera=(), geolocation=()";

const SECURITY_HEADERS = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Permissions-Policy", value: PERMISSIONS_POLICY },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Content-Security-Policy", value: CONTENT_SECURITY_POLICY },
];

/**
 * One id per build, used for two things that must agree: the service worker's cache
 * name and the query string the page registers it with.
 *
 * The cache name used to be the literal "deutschflow-v1" forever, so `activate`'s
 * cleanup sweep — which deletes every cache whose key differs from the current one —
 * never matched anything, and every deploy's hashed chunks accumulated indefinitely.
 * Correctness was fine (hashed URLs cannot serve the wrong bytes) but Cache Storage
 * counts against the origin's quota, and this origin's IndexedDB holds the only copy
 * of the user's library. Unbounded growth there raises the odds of the eviction that
 * costs them everything.
 *
 * The commit SHA on Vercel, a timestamp locally. Also passed to `generateBuildId` so
 * the two never diverge.
 */
const BUILD_ID = process.env.VERCEL_GIT_COMMIT_SHA ?? `dev-${Date.now()}`;

const nextConfig: NextConfig = {
  reactStrictMode: true,
  generateBuildId: async () => BUILD_ID,
  env: { NEXT_PUBLIC_BUILD_ID: BUILD_ID },
  async headers() {
    return [{ source: "/:path*", headers: SECURITY_HEADERS }];
  },
};

export default nextConfig;
