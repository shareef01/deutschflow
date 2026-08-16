import { useEffect, useState } from "react";
import { liveQuery } from "dexie";

/**
 * Minimal Dexie live binding — the web analogue of collecting a Room Flow.
 * Re-subscribes when `querier` changes identity, so callers wrap the query in
 * useCallback when it captures changing arguments.
 */
export function useLive<T>(querier: () => Promise<T> | T, deps: unknown[]): T | undefined {
  const [value, setValue] = useState<T | undefined>(undefined);

  useEffect(() => {
    const observable = liveQuery(querier);
    const subscription = observable.subscribe({
      next: (result) => setValue(result),
      error: (error) => console.error("Dexie liveQuery failed", error),
    });
    return () => subscription.unsubscribe();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return value;
}
