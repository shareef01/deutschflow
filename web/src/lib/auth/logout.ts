/**
 * DeutschFlow Client-Side Session Termination & Cache Invalidation Pipeline.
 *
 * Enforces verified server session termination before initiating client-side
 * cache purging or cross-tab session invalidation.
 */

export const APP_SHELL_ROUTES = [
  "/",
  "/transcript",
  "/history",
  "/vocabulary",
  "/study",
  "/practice",
  "/settings",
] as const;

export const STATIC_ASSETS = [
  "/manifest.json",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
] as const;

export const CACHE_PREFIX = "deutschflow-";
export const AUTH_BROADCAST_CHANNEL = "deutschflow_auth";

/**
 * Checks whether a given URL pathname represents a private/protected application route.
 * Static assets, Next.js build chunks, public icons, login, and APIs are excluded.
 */
export function isProtectedAppRoute(pathname: string): boolean {
  if ((APP_SHELL_ROUTES as readonly string[]).includes(pathname)) return true;
  if (
    !(STATIC_ASSETS as readonly string[]).includes(pathname) &&
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

/**
 * Enumerates all active Cache Storage instances starting with 'deutschflow-'
 * and deletes all protected navigation requests, including URLs with query strings
 * (e.g. /vocabulary?sort=recent). Safe static assets and build chunks are preserved.
 */
export async function purgeProtectedCaches(
  storage?: CacheStorage
): Promise<number> {
  const cacheStorage =
    storage ?? (typeof window !== "undefined" && "caches" in window ? window.caches : null);
  if (!cacheStorage) return 0;

  let purgedCount = 0;
  try {
    const keys = await cacheStorage.keys();
    for (const key of keys) {
      if (key.startsWith(CACHE_PREFIX)) {
        const cache = await cacheStorage.open(key);
        // 1. Purge static route names directly
        for (const route of APP_SHELL_ROUTES) {
          const deleted = await cache.delete(route);
          if (deleted) purgedCount++;
        }
        // 2. Enumerate all cached requests to purge query-string variations
        const requests = await cache.keys();
        for (const req of requests) {
          const reqUrl = new URL(req.url);
          if (isProtectedAppRoute(reqUrl.pathname)) {
            const deleted = await cache.delete(req);
            if (deleted) purgedCount++;
          }
        }
      }
    }
  } catch (err) {
    console.warn("Failed to purge protected caches:", err);
  }
  return purgedCount;
}

/**
 * Asynchronously communicates with the controlling Service Worker using a MessageChannel
 * to request cache purging and awaits an explicit acknowledgement message.
 * Falls back gracefully on timeout so navigation is never permanently blocked.
 */
export async function notifyServiceWorkerPurge(
  controller?: ServiceWorker | null,
  timeoutMs = 1000
): Promise<boolean> {
  const swController =
    controller !== undefined
      ? controller
      : typeof navigator !== "undefined"
      ? navigator.serviceWorker?.controller
      : null;

  if (!swController || typeof MessageChannel === "undefined") {
    return false;
  }

  return new Promise((resolve) => {
    let settled = false;
    const channel = new MessageChannel();

    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        channel.port1.close();
        resolve(false);
      }
    }, timeoutMs);

    channel.port1.onmessage = (event) => {
      if (!settled && event.data?.type === "CACHE_PURGED") {
        settled = true;
        clearTimeout(timer);
        channel.port1.close();
        resolve(event.data.success !== false);
      }
    };

    try {
      swController.postMessage(
        { type: "PURGE_PROTECTED_CACHE" },
        [channel.port2]
      );
    } catch {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        resolve(false);
      }
    }
  });
}

export interface LogoutResult {
  success: boolean;
  errorType?: "server" | "offline";
  statusCode?: number;
  errorMessage?: string;
}

export interface LogoutOptions {
  fetchFn?: typeof fetch;
  cacheStorage?: CacheStorage;
  swController?: ServiceWorker | null;
  onNavigate?: (url: string) => void;
  broadcastChannelFactory?: (name: string) => BroadcastChannel;
  timeoutMs?: number;
}

/**
 * Executes a verified, complete logout workflow:
 * 1. Requires verified HTTP 200 response from /api/logout (expiring HttpOnly cookie).
 *    If the server returns an error or is unreachable, logout aborts immediately without
 *    purging cache or redirecting, preserving user authentication.
 * 2. Purges protected navigation routes (with query parameters) across all Cache Storage caches.
 * 3. Notifies the service worker via MessageChannel with acknowledgement timeout.
 * 4. Broadcasts LOGOUT on BroadcastChannel to terminate stale authenticated sessions in background tabs.
 * 5. Navigates to /login.
 * 6. Preserves local IndexedDB learning data and encrypted Groq credentials.
 */
export async function performLogout(options: LogoutOptions = {}): Promise<LogoutResult> {
  const fetchFn = options.fetchFn ?? (typeof window !== "undefined" ? window.fetch.bind(window) : fetch);
  const onNavigate =
    options.onNavigate ??
    ((url: string) => {
      if (typeof window !== "undefined") {
        window.location.href = url;
      }
    });

  // Step 1: Verify server session termination first
  let response: Response;
  try {
    response = await fetchFn("/api/logout", {
      method: "POST",
      headers: { Accept: "application/json" },
    });
  } catch (err) {
    return {
      success: false,
      errorType: "offline",
      errorMessage: err instanceof Error ? err.message : "Network offline",
    };
  }

  if (!response.ok) {
    return {
      success: false,
      errorType: "server",
      statusCode: response.status,
      errorMessage: `Server returned HTTP ${response.status}`,
    };
  }

  // Step 2: Purge Cache API for all protected navigation routes
  await purgeProtectedCaches(options.cacheStorage);

  // Step 3: Notify Service Worker with MessageChannel acknowledgement
  await notifyServiceWorkerPurge(options.swController, options.timeoutMs ?? 1000);

  // Step 4: Broadcast session termination to other tabs
  try {
    if (options.broadcastChannelFactory) {
      const channel = options.broadcastChannelFactory(AUTH_BROADCAST_CHANNEL);
      channel.postMessage({ type: "LOGOUT" });
      channel.close();
    } else if (typeof window !== "undefined" && "BroadcastChannel" in window) {
      const channel = new BroadcastChannel(AUTH_BROADCAST_CHANNEL);
      channel.postMessage({ type: "LOGOUT" });
      channel.close();
    }
  } catch (err) {
    console.warn("Failed to broadcast logout to other tabs:", err);
  }

  // Step 5: Navigate initiating tab to /login
  onNavigate("/login");

  return { success: true };
}
