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
   * grades. "solid" is the screen's one primary action: a filled container
   * rather than a tint, so it is unmistakable next to the quieter buttons.
   */
  glow?: "deep" | "azure" | "error" | "green" | "amber" | "solid";
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
        glow === "solid" ? "edge-solid" : "text-on-surface",
        "press-scale",
        "h-12",
        "select-none",
        "font-bold",
        "disabled:text-on-surface-variant",
        "transition-[border-color,background-color,filter]",
        "duration-200",
        "hover:brightness-110",
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
