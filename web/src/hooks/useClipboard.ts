"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useI18n } from "./useI18n";

export interface ClipboardFeedback {
  message: string;
  variant: "default" | "error";
}

export async function copyToClipboard(
  text: string,
  clipboardApi: { writeText: (text: string) => Promise<void> } | undefined = typeof navigator !== "undefined"
    ? navigator.clipboard
    : undefined
): Promise<{ success: boolean; messageKey: "action.copied" | "action.copyFailed"; variant: "default" | "error" }> {
  try {
    if (!clipboardApi?.writeText) {
      return { success: false, messageKey: "action.copyFailed", variant: "error" };
    }
    await clipboardApi.writeText(text);
    return { success: true, messageKey: "action.copied", variant: "default" };
  } catch {
    return { success: false, messageKey: "action.copyFailed", variant: "error" };
  }
}

/**
 * useClipboard — centralized clipboard interaction hook.
 *
 * Feature detects navigator.clipboard, awaits writeText, reports distinct
 * localized success and failure feedback, and automatically clears the feedback
 * after a customizable timeout. Debounces rapid subsequent copy attempts to avoid
 * noisy visual feedback.
 */
export function useClipboard({ timeoutMs = 2500 }: { timeoutMs?: number } = {}) {
  const { t } = useI18n();
  const [feedback, setFeedback] = useState<ClipboardFeedback | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isCopyingRef = useRef(false);

  const clearTimer = useCallback(() => {
    if (timeoutRef.current !== null) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  }, []);

  useEffect(() => {
    return clearTimer;
  }, [clearTimer]);

  const copy = useCallback(
    async (text: string): Promise<boolean> => {
      if (isCopyingRef.current) return false;
      isCopyingRef.current = true;
      clearTimer();

      try {
        const result = await copyToClipboard(text);
        setFeedback({ message: t(result.messageKey), variant: result.variant });
        timeoutRef.current = setTimeout(() => {
          setFeedback(null);
        }, timeoutMs);
        return result.success;
      } finally {
        isCopyingRef.current = false;
      }
    },
    [clearTimer, t, timeoutMs]
  );

  const dismissFeedback = useCallback(() => {
    clearTimer();
    setFeedback(null);
  }, [clearTimer]);

  return {
    copy,
    feedback,
    dismissFeedback,
  };
}
