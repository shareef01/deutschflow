import type { ReactNode } from "react";

/**
 * EmptyState — the centred disc + title + description block every empty screen
 * shares. Compose equivalent: ui/components/EmptyState.kt.
 */
export function EmptyState({
  icon,
  message,
  description,
}: {
  icon: ReactNode;
  message: string;
  description?: string;
}) {
  return (
    <div className="flex size-full flex-col items-center justify-center px-12 py-12 text-center">
      {/* Solid disc from the elevation ramp — alpha-dimmed would float. */}
      <div className="flex size-44 items-center justify-center rounded-full bg-surface-container-high">
        <span className="size-20 text-on-surface-muted">{icon}</span>
      </div>
      <h2 className="mt-8 text-xl font-bold tracking-wide text-on-surface">{message}</h2>
      {description != null && (
        <p className="mt-4 max-w-sm text-base leading-relaxed text-on-surface-variant">{description}</p>
      )}
    </div>
  );
}
