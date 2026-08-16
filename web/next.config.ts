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
const nextConfig: NextConfig = {
  reactStrictMode: true,
};

export default nextConfig;
