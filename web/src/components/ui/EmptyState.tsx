import type { ReactNode } from "react";

/**
 * EmptyState — the centred disc + title + description block every empty screen
 * shares. Compose equivalent: ui/components/EmptyState.kt.
 *
 * Three things this gets right, each of which it used to get wrong on a phone:
 *
 * - `min-h-full`, not `size-full`. This block is usually the whole of a scrolling
 *   region, and a fixed 100% height made a flex item of it, so on a short viewport
 *   the icon disc was squashed into an oval instead of the region scrolling.
 * - A `shrink-0` disc, and a smaller one under `sm`. At 320px the app bar, the
 *   search field and the bottom bar leave roughly 340px; a 176px disc plus a
 *   five-line description pushed the block's one action below the fold, where it
 *   was cut in half by the navigation bar.
 * - `px-6`, not `px-12`. Fixed 48px gutters left a 224px text column at 320px,
 *   which wrapped the instruction to five lines and made the block taller than
 *   the space it had.
 */
export function EmptyState({
  icon,
  message,
  description,
  action,
}: {
  icon: ReactNode;
  message: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex min-h-full w-full flex-col items-center justify-center px-6 py-6 text-center sm:px-12 sm:py-12">
      {/* Solid disc from the elevation ramp — alpha-dimmed would float. Sized to
          introduce the message, not to be the screen: at 176px on desktop it was
          the largest object on every empty page, bigger than its own action. */}
      <div className="flex size-20 shrink-0 items-center justify-center rounded-full bg-surface-container-high">
        <span className="size-9 text-on-surface-muted">{icon}</span>
      </div>
      <h2 className="mt-5 text-title-large text-on-surface">{message}</h2>
      {description != null && (
        <p className="mt-2 max-w-[46ch] text-body-medium text-on-surface-variant">{description}</p>
      )}
      {action != null && (
        // The wrapper owns the horizontal padding: a GlassButton has none of its
        // own, so a call site that forgets `px-*` renders a button the exact width
        // of its label. A descendant variant beats the utility, so this also
        // settles the sites that pass their own.
        <div className="mt-5 flex items-center justify-center [&>button]:min-h-11 [&>button]:px-6 sm:mt-6">
          {action}
        </div>
      )}
    </div>
  );
}
