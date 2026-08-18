import type { ReactNode } from "react";

/**
 * The one place a page's width and gutters are decided.
 *
 * Every screen used to invent its own: `px-6 py-8`, `px-6 py-4`, `px-4`,
 * `size-full`, and nothing at all — on top of an app-shell cap of 1152px, and
 * on top of that a second cap of 768px on four of the six pages. The result was
 * a column that never grew: measured on the live site, Settings used 768px of a
 * 1920px screen (60% empty) and 768px of a 2560px screen (70% empty), because
 * the ceiling was a constant rather than a response to anything.
 *
 * A page now names the *kind* of space its content wants and gets the width
 * that suits it at every viewport:
 *
 *   reading    prose, forms, settings — stays near 70 characters
 *   workspace  a single focused column — transcript, study, practice
 *   wide       lists and split panes that genuinely use a large monitor
 *   full       the page manages its own width (split views that fill the shell)
 *
 * Gutters come from `--gutter`, which is a clamp: 16px on a phone, 48px on a
 * large monitor, with everything in between interpolated rather than jumping at
 * a breakpoint.
 */
export type ContainerWidth = "reading" | "workspace" | "wide" | "full";

const WIDTH: Record<ContainerWidth, string> = {
  reading: "max-w-[var(--container-reading)]",
  workspace: "max-w-[var(--container-workspace)]",
  wide: "max-w-[var(--container-wide)]",
  full: "max-w-none",
};

interface PageContainerProps {
  children: ReactNode;
  /** Defaults to the focused single column most screens want. */
  width?: ContainerWidth;
  /**
   * Vertical rhythm. `none` is for screens that own their own scrolling and
   * need the container to be a pure width constraint.
   */
  pad?: "default" | "tight" | "none";
  /** Centre the children vertically when the viewport is taller than they are. */
  center?: boolean;
  className?: string;
}

const PAD: Record<NonNullable<PageContainerProps["pad"]>, string> = {
  default: "px-[var(--gutter)] py-[var(--space-6)]",
  tight: "px-[var(--gutter)] py-[var(--space-4)]",
  none: "px-[var(--gutter)]",
};

export function PageContainer({
  children,
  width = "workspace",
  pad = "default",
  center = false,
  className = "",
}: PageContainerProps) {
  return (
    <div
      className={`mx-auto flex w-full min-h-0 min-w-0 flex-1 flex-col ${WIDTH[width]} ${PAD[pad]} ${
        center ? "justify-center" : ""
      } ${className}`}
    >
      {children}
    </div>
  );
}

/**
 * A titled block within a page — the grouping device that is not a card.
 *
 * Settings had its own local `SectionHeader` and no other screen had one, so
 * grouping was expressed by wrapping everything in bordered cards. Cards should
 * mean "this is one object"; a heading and some space is enough to mean "these
 * belong together".
 */
export function PageSection({
  title,
  description,
  children,
  className = "",
}: {
  title?: string;
  description?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`flex min-w-0 flex-col gap-[var(--space-3)] ${className}`}>
      {title && (
        <div className="flex flex-col gap-[var(--space-1)]">
          <h2 className="text-label-large uppercase tracking-wide text-on-surface-variant">
            {title}
          </h2>
          {description && (
            <p className="max-w-[60ch] text-body-medium text-on-surface-muted">{description}</p>
          )}
        </div>
      )}
      {children}
    </section>
  );
}
