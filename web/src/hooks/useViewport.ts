import { useEffect, useState } from "react";

/**
 * Layout queries — deliberately two of them, because the app makes two different
 * decisions and they do not share a threshold.
 *
 * One `min-width: 768px` hook used to drive both, and each half was wrong in its
 * own way on a real device:
 *
 * - The side rail needs vertical room, not horizontal. A phone held sideways is
 *   844px wide and 390px tall, so it qualified as "desktop" and got a rail that
 *   did not fit: Study and Practice fell below the fold, and because the rail is
 *   sticky with visible overflow, scrolling could not reach them. Two of the five
 *   destinations were simply unreachable.
 *
 * - The library's master-detail split needs more width than the rail does. At
 *   768px the empty detail pane took 42% of the screen and squeezed the list to
 *   about 280px - narrower than the same list on a 390px phone. A tablet showed
 *   less content than a phone, and clipped German words the phone rendered whole.
 *
 * Hence a height floor on both, and a higher width floor on the split.
 */

/** Five destinations plus their labels need roughly this much vertical room. */
const RAIL = "(min-width: 768px) and (min-height: 600px)";

/** Two panes only make sense once both can hold their content. */
const SPLIT = "(min-width: 1024px) and (min-height: 600px)";

function useMediaQuery(query: string): boolean {
  // False on the server and on the first client render, so the markup matches
  // and hydration does not warn; the effect corrects it immediately.
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    const mediaQuery = window.matchMedia(query);
    setMatches(mediaQuery.matches);

    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches);
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, [query]);

  return matches;
}

/** True when the left rail fits; false means the bottom bar, however wide the screen. */
export function useHasRail(): boolean {
  return useMediaQuery(RAIL);
}

/** True when the library can show list and detail side by side. */
export function useHasSplitView(): boolean {
  return useMediaQuery(SPLIT);
}
