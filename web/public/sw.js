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

/** Check whether a pathname represents a private/protected application route */
function isProtectedAppRoute(pathname) {
  if (APP_SHELL_ROUTES.includes(pathname)) return true;
  if (
    !STATIC_ASSETS.includes(pathname) &&
    !pathname.startsWith("/_next/") &&
    !pathname.startsWith("/icons/") &&
    pathname !== "/favicon.ico" &&
    pathname !== "/manifest.json" &&
    pathname !== "/sw.js" &&
    pathname !== "/login" &&
    !pathname.startsWith("/api/")
  ) {
    return true;
  }
  return false;
}

/** Explicitly remove cached private app shell and navigation routes upon logout */
async function purgeAppShellCache() {
  try {
    const keys = await caches.keys();
    for (const key of keys) {
      if (key.startsWith(CACHE_PREFIX)) {
        const cache = await caches.open(key);
        // 1. Purge static route names directly
        for (const route of APP_SHELL_ROUTES) {
          await cache.delete(route);
        }
        // 2. Enumerate all cached requests to purge query-string variations (e.g. /vocabulary?sort=recent)
        const requests = await cache.keys();
        for (const req of requests) {
          const reqUrl = new URL(req.url);
          if (isProtectedAppRoute(reqUrl.pathname)) {
            await cache.delete(req);
          }
        }
      }
    }

    } catch (err) {
    console.warn("Failed to purge app shell cache:", err);
  }
}

self.addEventListener("message", (event) => {
  if (
    event.data &&
    (event.data.type === "PURGE_PROTECTED_CACHE" ||
      event.data.type === "PURGE_APP_CACHE" ||
      event.data.type === "LOGOUT")
  ) {
    const port = event.ports && event.ports[0];
    event.waitUntil(
      purgeAppShellCache()
        .then(() => {
          if (port) {
            port.postMessage({ type: "CACHE_PURGED", success: true });
          }
        })
        .catch((err) => {
          if (port) {
            port.postMessage({
              type: "CACHE_PURGED",
              success: false,
              error: String(err),
            });
          }
        })
    );
  }
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Authentication and API endpoints (/api/*) must never be intercepted or cached
  if (url.pathname.startsWith("/api/")) {
    return;
  }

  if (request.method !== "GET") return;

  // Navigations: network-first, fall back to the cached shell — a cached
  // page of a tab the user has visited, or the Transcript page as the
  // guaranteed entry point.
  if (request.mode === "navigate") {
    // Do not cache /login; allow network request through or return 503 if offline.
    // Visiting /login directly must NOT terminate sessions in other tabs or purge cache.
    if (url.pathname === "/login") {
      event.respondWith(
        fetch(request).catch(() => {
          return new Response("Offline - Login unavailable", {
            status: 503,
            headers: { "Content-Type": "text/plain" },
          });
        })
      );
      return;
    }

    event.respondWith(
      fetch(request)
        .then((response) => {
          // If session expired or logged out, network redirects to /login or returns /login.
          // Invalidate cached private app routes to protect shared devices.
          if (
            (response.redirected && new URL(response.url).pathname === "/login") ||
            new URL(response.url).pathname === "/login"
          ) {
            event.waitUntil(purgeAppShellCache());
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
        .catch(async () => {
          // If offline: check if request is cached.
          // Note: If logged out, purgeAppShellCache already removed APP_SHELL_ROUTES,
          // so caches.match returns undefined and never leaks private pages.
          const cached = await caches.match(request);
          if (cached) return cached;
          const fallback = await caches.match("/transcript");
          if (fallback) return fallback;
          return new Response("Offline - Content unavailable", {
            status: 503,
            headers: { "Content-Type": "text/plain" },
          });
        })
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
