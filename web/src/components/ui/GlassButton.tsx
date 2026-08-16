import type { ButtonHTMLAttributes, ReactNode } from "react";

/**
 * GlassButton — the app's one primary action button: no solid fill, no solid
 * edge. A transparent container over a 5%-white glass fill, ringed by the same
 * cyan gradient every card uses, with a bold label.
 *
 * Compose equivalent: GlassComponents.kt `GlassButton`. `glow` recolours the
 * edge — Practice's "Stop" passes the error colour so a recording still reads
 * as stop-the-world.
 */
interface GlassButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  children: ReactNode;
  /** Edge colour when not glowing azure (e.g. the error colour for Stop). */
  glow?: "azure" | "error";
}

export function GlassButton({
  children,
  glow = "azure",
  className = "",
  ...rest
}: GlassButtonProps) {
  return (
    <button
      className={[
        "glass-button",
        glow === "error" ? "edge-error" : "",
        "press-scale",
        "h-12",
        "rounded-full",
        "select-none",
        "font-bold",
        "text-on-surface",
        "disabled:text-on-surface-variant",
        "transition-all",
        "duration-200",
        "hover:shadow-lg",
        "hover:shadow-azure-glow/20",
        "active:shadow-md",
        "focus-visible:outline-2",
        "focus-visible:outline-offset-2",
        "focus-visible:outline-azure-glow",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      {...rest}
    >
      {children}
    </button>
  );
}
