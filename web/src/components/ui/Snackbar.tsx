"use client";

/**
 * Snackbar — the one transient confirmation this app gives. The caller owns the
 * message state and clearing; an optional action (Undo) turns a notification
 * into a reversal.
 */
export function Snackbar({
  message,
  action,
  variant = "default",
  onDismiss,
}: {
  message: string | null;
  action?: { label: string; onClick: () => void };
  variant?: "default" | "error";
  onDismiss?: () => void;
}) {
  if (!message) return null;
  const isError = variant === "error";
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-24 z-40 flex justify-center px-4 md:bottom-8">
      <div
        role={isError ? "alert" : "status"}
        aria-live={isError ? "assertive" : "polite"}
        aria-atomic="true"
        className={`glass-surface flex max-w-md items-center gap-3 px-4 py-3 ${
          isError ? "border-error/40 text-error" : ""
        }`}
      >
        <p className={`text-body-medium ${isError ? "text-error" : "text-on-surface"}`}>{message}</p>
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            className="pointer-events-auto shrink-0 text-label-large font-semibold text-azure-glow hover:underline focus-visible:outline-2 focus-visible:outline-azure-glow rounded"
          >
            {action.label}
          </button>
        )}
        {onDismiss && (
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Dismiss"
            className="pointer-events-auto shrink-0 text-on-surface-variant hover:text-on-surface focus-visible:outline-2 focus-visible:outline-azure-glow rounded px-1"
          >
            ✕
          </button>
        )}
      </div>
    </div>
  );
}
