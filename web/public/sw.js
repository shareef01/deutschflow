/**
 * DeutschFlow service worker — offline boot for the installed PWA.
 *
 * App-shell model: the shell routes are precached on install, and the hashed
 * build assets under /_next/static are runtime-cached stale-while-revalidate on
 * the first online load — so after one visit the app opens offline. Navigation
 * requests are network-first with a cache fallback (the cached shell).
 *
 * The Groq API is a POST from the page and is deliberately NOT intercepted
 * here: translations carry the user's own text and the API key is sent by the
 * page, not visible to this worker.
 */

const CACHE_PREFIX = "deutschflow-";
const BUILD = new URL(self.location.href).searchParams.get("v") || "dev";
const CACHE_NAME = `${CACHE_PREFIX}${BUILD}`;

/** Essential navigation routes for offline shell */
const APP_SHELL_ROUTES = [
  "/",
  "/transcript",
  "/history",
  "/vocabulary",
  "/study",
  "/practice",
  "/settings",
];

/** Static assets that support offline operation */
const STATIC_ASSETS = [
  "/manifest.json",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
];

const APP_SHELL = [...APP_SHELL_ROUTES, ...STATIC_ASSETS];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then(async (cache) => {
        // Precaches assets individually. Shell routes may be behind the auth gate
        // during installation; an unauthenticated user redirects to /login.
        // Handled gracefully so the service worker installs reliably.
        await Promise.allSettled(
          APP_SHELL.map(async (url) => {
            try {
              const response = await fetch(url);
              if (!response.ok || response.redirected) return;
              await cache.put(url, response);
            } catch {
              // Ignore network failures for non-essential precache assets during install
            }
          })
        );
      })
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME)
            .map((key) => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Navigations: network-first, fall back to the cached shell — a cached
  // page of a tab the user has visited, or the Transcript page as the
  // guaranteed entry point.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          // If session expired or logged out, network redirects to /login.
          // Invalidate cached private app routes to protect shared devices.
          if (response.redirected && new URL(response.url).pathname === "/login") {
            event.waitUntil(
              caches.open(CACHE_NAME).then(async (cache) => {
                for (const route of APP_SHELL_ROUTES) {
                  await cache.delete(route);
                }
              }).catch(() => {})
            );
            return response;
          }

          // Do not cache error responses, login page, or unexpected redirects
          if (!response.ok || response.redirected || new URL(response.url).pathname === "/login") {
            return response;
          }

          // Attach cache write to event lifetime via event.waitUntil to prevent detached write drops
          const copy = response.clone();
          event.waitUntil(
            caches.open(CACHE_NAME)
              .then((cache) => cache.put(request, copy))
              .catch((err) => {
                console.warn("Service worker navigation cache write failed:", err);
              })
          );

          return response;
        })
        .catch(() =>
          caches.match(request).then((cached) => cached ?? caches.match("/transcript"))
        )
    );
    return;
  }

  // Hashed build assets: stale-while-revalidate (hashes change per build, so a
  // cached copy is always the version it was fetched with).
  if (url.pathname.startsWith("/_next/static/")) {
    event.respondWith(
      caches.match(request).then((cached) => {
        const network = fetch(request)
          .then((response) => {
            if (response.ok) {
              const copy = response.clone();
              // Attach cache write to event lifetime to prevent detached write drops
              event.waitUntil(
                caches.open(CACHE_NAME)
                  .then((cache) => cache.put(request, copy))
                  .catch(() => {})
              );
            }
            return response;
          })
          .catch(() => cached);
        return cached ?? network;
      })
    );
  }
});
