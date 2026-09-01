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
/**
 * Named for the build that registered this worker — see PwaRegister and
 * next.config.ts. It was the literal "deutschflow-v1" forever, so the activate
 * sweep below never matched anything and every deploy's assets piled up.
 */
const BUILD = new URL(self.location.href).searchParams.get("v") || "dev";
const CACHE_NAME = `deutschflow-${BUILD}`;

const APP_SHELL = [
  "/",
  "/transcript",
  "/history",
  "/vocabulary",
  "/study",
  "/practice",
  "/settings",
  "/manifest.json",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then(async (cache) => {
        // Individually, not addAll. Every shell route is behind the auth gate, so
        // an expired session redirects them to /login — and Cache.put rejects a
        // redirected response, which made addAll throw, install fail, and the new
        // worker never activate, with nothing on screen saying so. A shell entry
        // that cannot be precached is a worse offline experience, not a broken
        // install; the fetch handler applies the same redirect rule at line ~68.
        await Promise.allSettled(
          APP_SHELL.map(async (url) => {
            const response = await fetch(url);
            if (!response.ok || response.redirected) return;
            await cache.put(url, response);
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
        Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))
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
  // transcript of a tab the user has visited, or the Transcript page as the
  // guaranteed entry point.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          // A redirect here is the auth gate bouncing an expired session to
          // /login — `fetch` follows it, so caching the response would store
          // the login page under the app route that was requested, and an
          // offline launch would serve the login screen at that route with
          // nothing on screen saying why. Only final, non-redirected app
          // responses enter the cache.
          if (response.redirected || new URL(response.url).pathname === "/login") {
            return response;
          }
          const copy = response.clone();
          void caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
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
              void caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
            }
            return response;
          })
          .catch(() => cached);
        return cached ?? network;
      })
    );
  }
});
