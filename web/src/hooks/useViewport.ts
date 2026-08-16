import { useEffect, useState } from "react";

/**
 * Window size class — the web analogue of Material's WindowWidthSizeClass.
 *
 * The Android app switches from a bottom NavigationBar to a NavigationRail at
 * anything wider than Compact. The web port uses the same split: below 768px
 * the bottom bar, at 768px and above the glass left-hand rail.
 */
export function useIsDesktop(): boolean {
  const [isDesktop, setIsDesktop] = useState(false);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(min-width: 768px)");
    setIsDesktop(mediaQuery.matches);

    const onChange = (event: MediaQueryListEvent) => setIsDesktop(event.matches);
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, []);

  return isDesktop;
}
