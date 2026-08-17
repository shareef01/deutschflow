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
  /**
   * Edge + fill tint. "deep" is the electric blue default (the app's action
   * colour); "azure" is the calm cyan (transcription accents); "error", "green"
   * and "amber" carry the status set — Practice's Stop and the Study feedback
   * grades.
   */
  glow?: "deep" | "azure" | "error" | "green" | "amber";
}

export function GlassButton({
  children,
  glow = "deep",
  className = "",
  ...rest
}: GlassButtonProps) {
  return (
    <button
      className={[
        "glass-button",
        glow === "azure" ? "edge-azure" : "",
        glow === "error" ? "edge-error" : "",
        glow === "green" ? "edge-green" : "",
        glow === "amber" ? "edge-amber" : "",
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
