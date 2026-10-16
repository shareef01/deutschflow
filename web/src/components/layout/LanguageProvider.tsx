"use client";

import { useEffect } from "react";
import { useI18n } from "@/hooks/useI18n";

/**
 * Synchronizes active language to document.documentElement.lang so assistive
 * technologies (screen readers, VoiceOver, TalkBack) announce German and
 * English interface text with the correct phonetic rules across all routes.
 */
export function LanguageProvider({ children }: { children: React.ReactNode }) {
  const { lang } = useI18n();

  useEffect(() => {
    if (typeof document !== "undefined") {
      document.documentElement.lang = lang;
    }
  }, [lang]);

  return <>{children}</>;
}
