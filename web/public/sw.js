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

const SESSION_DB_NAME = "deutschflow-session";
const SESSION_STORE_NAME = "auth";

let currentSessionEpoch = Date.now();
let isSessionTerminated = false;

function openSessionDb() {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") {
      return reject(new Error("IndexedDBUnavailable"));
    }
    const req = indexedDB.open(SESSION_DB_NAME, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(SESSION_STORE_NAME)) {
        req.result.createObjectStore(SESSION_STORE_NAME);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function setPersistentSessionLoggedOut(loggedOut) {
  try {
    const db = await openSessionDb();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(SESSION_STORE_NAME, "readwrite");
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
      tx.objectStore(SESSION_STORE_NAME).put({ loggedOut, updatedAt: Date.now() }, "state");
    });
  } catch (err) {
    console.warn("Failed to update persistent session state:", err);
  }
}

async function isPersistentSessionLoggedOut() {
  try {
    const db = await openSessionDb();
    return await new Promise((resolve) => {
      const tx = db.transaction(SESSION_STORE_NAME, "readonly");
      const req = tx.objectStore(SESSION_STORE_NAME).get("state");
      req.onsuccess = () => resolve(req.result?.loggedOut === true);
      req.onerror = () => resolve(false);
    });
  } catch {
    return false;
  }
}

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
  currentSessionEpoch = Date.now();
  isSessionTerminated = true;
  await setPersistentSessionLoggedOut(true);

  let caughtError = null;
  let purgedCount = 0;

  try {
    const keys = await caches.keys();
    for (const key of keys) {
      if (key.startsWith(CACHE_PREFIX)) {
        const cache = await caches.open(key);
        // 1. Purge static route names directly
        for (const route of APP_SHELL_ROUTES) {
          try {
            const deleted = await cache.delete(route);
            if (deleted) purgedCount++;
          } catch (err) {
            caughtError = err;
          }
        }
        // 2. Enumerate all cached requests to purge query-string variations (e.g. /vocabulary?sort=recent)
        try {
          const requests = await cache.keys();
          for (const req of requests) {
            const reqUrl = new URL(req.url);
            if (isProtectedAppRoute(reqUrl.pathname)) {
              try {
                const deleted = await cache.delete(req);
                if (deleted) purgedCount++;
              } catch (err) {
                caughtError = err;
              }
            }
          }
        } catch (err) {
          caughtError = err;
        }
      }
    }
  } catch (err) {
    caughtError = err;
  }

  if (caughtError) {
    console.warn("Failed to purge app shell cache completely:", caughtError);
    const errorName = caughtError instanceof Error ? caughtError.name : "CachePurgeError";
    throw new Error(errorName);
  }

  return { success: true, purgedCount };
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
        .then((result) => {
          if (port) {
            port.postMessage({
              type: "CACHE_PURGED",
              success: true,
              purgedCount: result.purgedCount,
            });
          }
        })
        .catch((err) => {
          if (port) {
            port.postMessage({
              type: "CACHE_PURGED",
              success: false,
              error: err instanceof Error ? err.message : "CachePurgeError",
            });
          }
        })
    );
  } else if (event.data && event.data.type === "SESSION_ESTABLISHED") {
    currentSessionEpoch = Date.now();
    isSessionTerminated = false;
    event.waitUntil(setPersistentSessionLoggedOut(false));
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

    const requestEpoch = currentSessionEpoch;

    event.respondWith(
      fetch(request)
        .then(async (response) => {
          // If session expired or logged out, network redirects to /login or returns /login.
          // Invalidate cached private app routes to protect shared devices.
          if (
            (response.redirected && new URL(response.url).pathname === "/login") ||
            new URL(response.url).pathname === "/login"
          ) {
            event.waitUntil(purgeAppShellCache().catch(() => {}));
            return response;
          }

          // Do not cache error responses, login page, or unexpected redirects
          if (!response.ok || response.redirected || new URL(response.url).pathname === "/login") {
            return response;
          }

          // Race check: If session terminated or epoch changed during in-flight fetch,
          // do NOT write to cache! This prevents late writes after logout from reintroducing protected content.
          const isLoggedOut =
            isSessionTerminated ||
            currentSessionEpoch !== requestEpoch ||
            (await isPersistentSessionLoggedOut());

          if (isLoggedOut) {
            return response;
          }

          // User successfully accessed protected route online: session is confirmed active
          isSessionTerminated = false;
          event.waitUntil(setPersistentSessionLoggedOut(false));

          // Attach cache write to event lifetime via event.waitUntil to prevent detached write drops
          const copy = response.clone();
          event.waitUntil(
            caches.open(CACHE_NAME)
              .then(async (cache) => {
                // Double check before committing write to cache
                if (
                  isSessionTerminated ||
                  currentSessionEpoch !== requestEpoch ||
                  (await isPersistentSessionLoggedOut())
                ) {
                  return;
                }
                await cache.put(request, copy);
              })
              .catch((err) => {
                console.warn("Service worker navigation cache write failed:", err);
              })
          );

          return response;
        })
        .catch(async () => {
          // Fail-safe protection: if session is terminated or marked logged out,
          // NEVER serve protected cached navigation to a logged-out user!
          const isLoggedOut = isSessionTerminated || (await isPersistentSessionLoggedOut());
          if (isLoggedOut) {
            return new Response("Offline - Content unavailable", {
              status: 503,
              headers: { "Content-Type": "text/plain" },
            });
          }

          // If offline: check if request is cached.
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
