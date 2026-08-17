"use client";

/**
 * Snackbar — the one transient confirmation this app gives. The caller owns the
 * message state and clearing; an optional action (Undo) turns a notification
 * into a reversal.
 */
export function Snackbar({
  message,
  action,
}: {
  message: string | null;
  action?: { label: string; onClick: () => void };
}) {
  if (!message) return null;
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-24 z-40 flex justify-center px-4 md:bottom-8">
      <div className="glass-surface flex max-w-md items-center gap-3 px-4 py-3">
        <p className="text-body-medium text-on-surface">{message}</p>
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            className="pointer-events-auto shrink-0 text-label-large font-semibold text-azure-glow"
          >
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
}
