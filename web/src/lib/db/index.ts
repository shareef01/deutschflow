import { DeutschFlowDB } from "./schema";

export * from "./schema";
export * from "./repository";
export * from "./settings";
export * from "./vault";

/**
 * The single database instance — the analogue of the Hilt-singleton
 * `AppDatabase`. Import `db` from here; never construct a second `DeutschFlowDB`
 * (Dexie forbids two instances over the same name, mirroring Room's
 * "one instance per file per process" rule).
 */
export const db = new DeutschFlowDB();

/**
 * Whether another tab is holding this database at an older version.
 *
 * IndexedDB will not upgrade a database while an older connection is open, and it
 * does not fail — it *waits*, indefinitely, with no error and nothing on screen.
 * A user with the app open in two tabs after a deploy would have sat looking at a
 * page that never loaded its data. Dexie surfaces this as `blocked`; the app has to
 * do the telling.
 *
 * A plain module-level flag with subscribers rather than a store library: this
 * changes at most once in a session, and the app already reads module singletons
 * through useSyncExternalStore.
 */
let upgradeBlocked = false;
const blockedListeners = new Set<() => void>();

export function isUpgradeBlocked(): boolean {
  return upgradeBlocked;
}

export function subscribeUpgradeBlocked(listener: () => void): () => void {
  blockedListeners.add(listener);
  return () => {
    blockedListeners.delete(listener);
  };
}

db.on("blocked", () => {
  upgradeBlocked = true;
  for (const listener of blockedListeners) listener();
});

/**
 * The other side of the same problem: THIS tab is the old connection holding a
 * newer one hostage. Closing lets the other tab through, and the closed database
 * then rejects reads — which is correct and visible, where blocking the other tab
 * forever is neither.
 */
db.on("versionchange", () => {
  db.close();
  upgradeBlocked = true;
  for (const listener of blockedListeners) listener();
});
