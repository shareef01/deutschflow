"use client";

import { useEffect } from "react";

/**
 * Registers the service worker in production only — in development it would
 * race the dev server's HMR and serve stale assets.
 */
export function PwaRegister() {
  useEffect(() => {
    if (process.env.NODE_ENV !== "production") return;
    if (!("serviceWorker" in navigator)) return;

    // The build id rides on the query string. The file's bytes are identical
    // between deploys, so without it the browser has no reason to fetch the new
    // worker — and the worker itself reads it back out of self.location to name
    // its cache, which is what lets activate() evict the previous build's.
    const build = process.env.NEXT_PUBLIC_BUILD_ID ?? "dev";

    navigator.serviceWorker.register(`/sw.js?v=${encodeURIComponent(build)}`).catch((error) => {
      // Registration failure must never take the app down; PWA support is an
      // enhancement on top of a fully working web app.
      console.error("Service worker registration failed", error);
    });
  }, []);

  return null;
}
