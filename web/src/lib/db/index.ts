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
