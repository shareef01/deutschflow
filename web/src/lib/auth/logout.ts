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
export interface PurgeResult {
  success: boolean;
  purgedCount: number;
  error?: string;
}

export interface SwPurgeResult {
  success: boolean;
  swStatus: "acknowledged" | "timeout" | "no_controller" | "error";
  purgedCount?: number;
  error?: string;
}

const SESSION_DB_NAME = "deutschflow-session";
const SESSION_STORE_NAME = "auth";

/**
 * Persists session state to a dedicated IndexedDB store.
 * Operates independently of Cache Storage so the logged-out state survives
 * service worker restarts even if Cache Storage deletion fails.
 */
export async function setPersistentSessionLoggedOut(loggedOut: boolean): Promise<void> {
  if (typeof indexedDB === "undefined") return;
  try {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(SESSION_DB_NAME, 1);
      req.onupgradeneeded = () => {
        if (!req.result.objectStoreNames.contains(SESSION_STORE_NAME)) {
          req.result.createObjectStore(SESSION_STORE_NAME);
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });

    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(SESSION_STORE_NAME, "readwrite");
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
      tx.objectStore(SESSION_STORE_NAME).put({ loggedOut, updatedAt: Date.now() }, "state");
    });
  } catch (err) {
    console.warn("Failed to set persistent session state from window context:", err);
  }
}

/**
 * Enumerates all active Cache Storage instances starting with 'deutschflow-'
 * and deletes all protected navigation requests, including URLs with query strings
 * (e.g. /vocabulary?sort=recent). Safe static assets and build chunks are preserved.
 *
 * Accurately reports whether all required deletions succeeded or if any errors occurred.
 */
export async function purgeProtectedCaches(
  storage?: CacheStorage
): Promise<PurgeResult> {
  const cacheStorage =
    storage ?? (typeof window !== "undefined" && "caches" in window ? window.caches : null);
  if (!cacheStorage) {
    return { success: false, purgedCount: 0, error: "CacheStorageUnavailable" };
  }

  let purgedCount = 0;
  let caughtError: string | undefined;

  try {
    const keys = await cacheStorage.keys();
    for (const key of keys) {
      if (key.startsWith(CACHE_PREFIX)) {
        const cache = await cacheStorage.open(key);
        // 1. Purge static route names directly
        for (const route of APP_SHELL_ROUTES) {
          try {
            const deleted = await cache.delete(route);
            if (deleted) purgedCount++;
          } catch (err) {
            caughtError = err instanceof Error ? err.name : "CacheDeleteError";
          }
        }
        // 2. Enumerate all cached requests to purge query-string variations
        try {
          const requests = await cache.keys();
          for (const req of requests) {
            const reqUrl = new URL(req.url);
            if (isProtectedAppRoute(reqUrl.pathname)) {
              try {
                const deleted = await cache.delete(req);
                if (deleted) purgedCount++;
              } catch (err) {
                caughtError = err instanceof Error ? err.name : "CacheDeleteError";
              }
            }
          }
        } catch (err) {
          caughtError = err instanceof Error ? err.name : "CacheKeysError";
        }
      }
    }
  } catch (err) {
    caughtError = err instanceof Error ? err.name : "CacheStorageKeysError";
  }

  if (caughtError) {
    return { success: false, purgedCount, error: caughtError };
  }
  return { success: true, purgedCount };
}

/**
 * Asynchronously communicates with the controlling Service Worker using a MessageChannel
 * to request cache purging and awaits an explicit acknowledgement message.
 * Falls back gracefully on timeout so navigation is never permanently blocked.
 */
export async function notifyServiceWorkerPurge(
  controller?: ServiceWorker | null,
  timeoutMs = 1000
): Promise<SwPurgeResult> {
  const swController =
    controller !== undefined
      ? controller
      : typeof navigator !== "undefined"
      ? navigator.serviceWorker?.controller
      : null;

  if (!swController || typeof MessageChannel === "undefined") {
    return { success: false, swStatus: "no_controller" };
  }

  return new Promise((resolve) => {
    let settled = false;
    const channel = new MessageChannel();

    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        channel.port1.close();
        resolve({
          success: false,
          swStatus: "timeout",
          error: "ServiceWorkerTimeout",
        });
      }
    }, timeoutMs);

    channel.port1.onmessage = (event) => {
      if (!settled && event.data?.type === "CACHE_PURGED") {
        settled = true;
        clearTimeout(timer);
        channel.port1.close();
        const success = event.data.success === true;
        resolve({
          success,
          swStatus: success ? "acknowledged" : "error",
          purgedCount: event.data.purgedCount,
          error: event.data.error,
        });
      }
    };

    try {
      swController.postMessage(
        { type: "PURGE_PROTECTED_CACHE" },
        [channel.port2]
      );
    } catch (err) {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        resolve({
          success: false,
          swStatus: "error",
          error: err instanceof Error ? err.name : "MessagePostError",
        });
      }
    }
  });
}

/**
 * Tells this origin a session exists again, after a successful sign-in.
 *
 * A logout (or an expired cookie) leaves a persisted logged-out flag that makes the
 * service worker refuse to serve cached pages offline. The post-login redirect is a
 * client-side transition the worker never sees as a navigation, so without this the
 * flag outlived the new session and offline boot stayed broken.
 *
 * Clears the flag here as well as in the worker, so it is gone even when no worker
 * controls the page yet. Never throws: a failure only costs offline mode until the
 * next full navigation, which clears the flag too.
 */
export async function markSessionEstablished(
  controller?: ServiceWorker | null
): Promise<void> {
  await setPersistentSessionLoggedOut(false);
  const swController =
    controller !== undefined
      ? controller
      : typeof navigator !== "undefined"
      ? navigator.serviceWorker?.controller
      : null;
  try {
    swController?.postMessage({ type: "SESSION_ESTABLISHED" });
  } catch {
    // The worker clears the flag on the next navigation it serves.
  }
}

export interface LogoutResult {
  success: boolean;
  serverSuccess: boolean;
  cachePurgeSuccess: boolean;
  swPurgeSuccess: boolean;
  privacyCleanupComplete: boolean;
  purgedCount: number;
  cleanupWarning?: string;
  /** Set only when eraseLocalData was passed: whether it finished. */
  localDataErased?: boolean;
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
  /**
   * "Log out and erase this device": run after the server has ended the session,
   * before other tabs are told and this one leaves. Resolves true when everything
   * was erased. Omitted for a plain logout, which keeps the library.
   */
  eraseLocalData?: () => Promise<boolean>;
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
 * 6. Preserves local IndexedDB learning data and encrypted Groq credentials,
 *    unless eraseLocalData is passed. If that erase is incomplete, the tab stays
 *    put so the user is told, instead of landing on /login believing it worked.
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
      serverSuccess: false,
      cachePurgeSuccess: false,
      swPurgeSuccess: false,
      privacyCleanupComplete: false,
      purgedCount: 0,
      errorType: "offline",
      errorMessage: err instanceof Error ? err.message : "Network offline",
    };
  }

  if (!response.ok) {
    return {
      success: false,
      serverSuccess: false,
      cachePurgeSuccess: false,
      swPurgeSuccess: false,
      privacyCleanupComplete: false,
      purgedCount: 0,
      errorType: "server",
      statusCode: response.status,
      errorMessage: `Server returned HTTP ${response.status}`,
    };
  }

  // Step 2: Server session verified terminated. Mark persistent logged-out state in window context
  await setPersistentSessionLoggedOut(true);

  // Step 3: Purge Cache API for all protected navigation routes
  const cachePurgeResult = await purgeProtectedCaches(options.cacheStorage);

  // Step 4: Notify Service Worker with MessageChannel acknowledgement
  const swPurgeResult = await notifyServiceWorkerPurge(options.swController, options.timeoutMs ?? 1000);

  // Determine overall privacy cleanup completeness
  const privacyCleanupComplete = cachePurgeResult.success && swPurgeResult.success;
  const cleanupWarning = !privacyCleanupComplete ? "Local cache purge incomplete" : undefined;
  const totalPurgedCount = cachePurgeResult.purgedCount + (swPurgeResult.purgedCount ?? 0);

  // Only after the server confirmed the logout: an erase that ran and then found
  // the session still alive would leave a logged-in, empty app.
  let localDataErased: boolean | undefined;
  if (options.eraseLocalData) {
    localDataErased = await options.eraseLocalData().catch(() => false);
  }

  // Step 5: Broadcast session termination to other tabs
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

  // Step 6: Navigate initiating tab to /login
  if (localDataErased !== false) onNavigate("/login");

  return {
    success: true,
    serverSuccess: true,
    cachePurgeSuccess: cachePurgeResult.success,
    swPurgeSuccess: swPurgeResult.success,
    privacyCleanupComplete,
    purgedCount: totalPurgedCount,
    cleanupWarning,
    localDataErased,
  };
}
