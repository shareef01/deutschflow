"use client";

import type { ReactNode } from "react";

/**
 * ModalDialog — the AlertDialog equivalent (opaque surface from the elevation
 * ramp, because dialogs are drawn over arbitrary content and cannot be
 * see-through). Used by the library's add/edit editor.
 */
export function ModalDialog({
  title,
  onDismiss,
  children,
  actions,
}: {
  title?: string;
  onDismiss: () => void;
  children: ReactNode;
  actions: ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-label={title ?? "Dialog"}>
      <button
        type="button"
        aria-label="Dismiss"
        onClick={onDismiss}
        className="absolute inset-0 animate-backdrop-in cursor-default bg-black/60 backdrop-blur-sm"
      />
      <div className="relative w-full max-w-md animate-sheet-up rounded-xl bg-surface-container-high p-5 shadow-2xl">
        {title != null && (
          <h2 className="text-title-medium font-bold text-on-surface">{title}</h2>
        )}
        <div className={title != null ? "mt-4 space-y-2" : ""}>{children}</div>
        <div className="mt-6 flex justify-end gap-2">{actions}</div>
      </div>
    </div>
  );
}
