import { useCallback, useEffect, useRef } from "react";
import { recognizer } from "@/lib/speech/recognizer";
import { resolveRecognitionDialect } from "@/lib/speech/dialect";

/** A permission or settings read must not acquire the mic after its owner leaves. */
export function useMicrophoneStart(active = true) {
  const owner = useRef({ active, generation: 0, starting: false });
  const cancelListening = useCallback(() => {
    owner.current.generation++;
    owner.current.starting = false;
    recognizer.cancel();
  }, []);

  useEffect(() => {
    const state = owner.current;
    state.active = active;
    if (!active) cancelListening();
    return () => {
      state.active = false;
      cancelListening();
    };
  }, [active, cancelListening]);

  const startListening = useCallback(async (onAccepted?: () => void) => {
    const state = owner.current;
    if (!state.active || state.starting || recognizer.getSnapshot().isListening) return;
    state.starting = true;
    const generation = ++state.generation;
    const ownsAttempt = () => state.active && generation === state.generation;
    try {
      onAccepted?.();
      const granted = await recognizer.requestMicrophonePermission();
      if (!ownsAttempt()) return;
      if (!granted) {
        recognizer.reportPermissionDenied();
        return;
      }
      const dialect = await resolveRecognitionDialect();
      if (ownsAttempt()) recognizer.startListening(dialect);
    } catch {
      if (ownsAttempt()) recognizer.reportPermissionDenied();
    } finally {
      if (generation === state.generation) state.starting = false;
    }
  }, []);

  return { startListening, cancelListening };
}
