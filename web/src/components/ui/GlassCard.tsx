import type { HTMLAttributes, ReactNode } from "react";

/**
 * GlassmorphicCard — the app's one surface treatment as a named component.
 * Compose equivalent: ui/components/GlassComponents.kt.
 *
 * The glass-surface utility carries the 3% white fill and the glowing
 * top-left-to-bottom-right azure edge; this component only owns padding and
 * composition, so a card and the search bar beside it are one stroke.
 */
interface GlassCardProps extends HTMLAttributes<HTMLDivElement> {
  children: ReactNode;
  /** Padding scale token, defaulting to the app's default gap (Spacing.md). */
  contentPadding?: string;
}

export function GlassCard({ children, contentPadding = "p-4", className = "", ...rest }: GlassCardProps) {
  return (
    <div className={`glass-surface ${contentPadding} ${className}`} {...rest}>
      {children}
    </div>
  );
}
