"use client";

import { useEffect } from "react";
import { getCurrentLang, translate } from "@/lib/i18n";

export default function AppErrorBoundary({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("DeutschFlow screen error:", error);
  }, [error]);

  const lang = getCurrentLang();

  return (
    <div
      role="alert"
      className="mx-auto my-12 max-w-md rounded-2xl border border-on-surface/10 bg-surface p-6 text-center shadow-lg"
    >
      <h1 className="text-title-large font-bold text-on-surface">
        {translate(lang, "error.title")}
      </h1>
      <p className="mt-2 text-body-medium text-on-surface-variant">
        {translate(lang, "error.body")}
      </p>
      <button
        type="button"
        onClick={reset}
        className="mt-5 inline-flex items-center justify-center rounded-xl bg-primary px-5 py-2.5 text-label-large font-semibold text-white transition-colors hover:bg-primary-light"
      >
        {translate(lang, "action.retry")}
      </button>
    </div>
  );
}
