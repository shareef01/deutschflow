import { useCallback, useEffect, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getLanguage, setLanguage } from "@/lib/db/settings";
import {
  detectBrowserLang,
  getLangSnapshot,
  setCurrentLang,
  subscribeLang,
  translate,
  type Lang,
  type TKey,
} from "@/lib/i18n";

/**
 * useI18n — the app-language equivalent of the Android per-app language.
 *
 * The persisted choice wins; otherwise the browser's preferred language
 * decides (German browser → German UI). All instances share one module-level
 * language store (useSyncExternalStore), so a change on the Settings page
 * re-renders the AppShell and every screen at once. The module's current
 * language is the same store, so non-React modules (recognizer, tts, groq)
 * localize the messages they generate.
 */
export function useI18n() {
  const lang = useSyncExternalStore(subscribeLang, getLangSnapshot, getLangSnapshot);

  // Load the persisted choice (or detect from the browser) once.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const stored = await getLanguage(db);
      if (cancelled) return;
      const resolved: Lang =
        stored === "de" || stored === "en" ? stored : detectBrowserLang();
      applyLang(resolved);
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const applyLang = useCallback((next: Lang) => {
    setCurrentLang(next);
    if (typeof document !== "undefined") document.documentElement.lang = next;
  }, []);

  /** Persist a user choice from the Settings language section. */
  const changeLang = useCallback(
    (next: Lang) => {
      applyLang(next);
      void setLanguage(db, next);
    },
    [applyLang]
  );

  const t = useCallback(
    (key: TKey, params?: (string | number)[]) => translate(lang, key, params),
    [lang]
  );

  return { lang, t, changeLang };
}
