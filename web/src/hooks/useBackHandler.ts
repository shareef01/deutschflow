import { useEffect, useRef } from "react";

/**
 * useBackHandler — BackHandler port (ui/components/OnLeavingScreen + the
 * BackHandler in VocabularyScreen).
 *
 * When `active`, the browser's back gesture closes the detail instead of
 * leaving the destination: a marker history entry is pushed, and a pop on it
 * runs `onBack`. The marker is removed on cleanup.
 */
export function useBackHandler(active: boolean, onBack: () => void) {
  const onBackRef = useRef(onBack);
  onBackRef.current = onBack;

  useEffect(() => {
    if (!active) return;

    window.history.pushState({ deutschflowDetail: true }, "");

    const onPop = () => onBackRef.current();
    window.addEventListener("popstate", onPop);

    return () => {
      window.removeEventListener("popstate", onPop);
      // Pop our own marker if it is still the top entry, so the user is not
      // left with a dead entry behind them.
      if (window.history.state?.deutschflowDetail) {
        window.history.back();
      }
    };
  }, [active]);
}
