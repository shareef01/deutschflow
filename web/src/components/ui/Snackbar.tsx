"use client";

/**
 * Snackbar — the one transient confirmation this app gives (Android's
 * SnackbarHost). The caller owns the message state and clearing; this component
 * only renders the message and its glass treatment.
 */
export function Snackbar({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-24 z-40 flex justify-center px-4 md:bottom-8">
      <div className="glass-surface max-w-md px-4 py-3">
        <p className="text-body-medium text-on-surface">{message}</p>
      </div>
    </div>
  );
}
