"use client";

import type { ReactNode } from "react";

/**
 * OracleMic — the recording control, and the one place in the app allowed to
 * be theatrical.
 *
 * Three layers, drawn outward from the icon (Compose equivalent:
 * ui/components/OracleMic.kt):
 *  1. A halo, well outside the button, carrying a radial fall-off to
 *     transparent — lit from within rather than filled in.
 *  2. A sweep-gradient mesh on the disc itself, rotated by a single continuous
 *     12s transition — a sweep rather than a linear gradient, because a linear
 *     one visibly sweeps back and forth; a sweep just turns.
 *  3. The icon, upright.
 *
 * Idle, the halo breathes and the mesh sits at the deep end of the ramp. Live,
 * both jump to full azure and the halo stops breathing and simply burns — the
 * state change has to be readable at a glance from across a room.
 */
export function OracleMic({
  icon,
  label,
  isListening,
  isBusy,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  isListening: boolean;
  isBusy: boolean;
  onClick: () => void;
}) {
  const accent = isListening ? "#00e5ff" : "#0a84ff";
  const haloTop = isListening ? "rgba(0, 229, 255, 0.45)" : "rgba(10, 132, 255, 0.45)";
  const haloMid = isListening ? "rgba(0, 229, 255, 0.12)" : "rgba(10, 132, 255, 0.12)";

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={isBusy}
      aria-label={label}
      className="press-scale relative grid size-56 shrink-0 place-items-center rounded-full focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-azure-glow"
    >
      {/* 1. The halo — breathes while idle, burns steady while listening. */}
      <span
        aria-hidden
        className={`absolute inset-0 rounded-full transition-opacity duration-500 ${
          isListening ? "opacity-100" : "animate-breath"
        }`}
        style={{
          background: `radial-gradient(circle, ${haloTop}, ${haloMid}, transparent 70%)`,
        }}
      />

      {/* 2. The mesh — one sweep gradient turning continuously. */}
      <span
        aria-hidden
        className="absolute size-[108px] animate-mesh-rotation rounded-full"
        style={{
          background: `conic-gradient(from 0deg, ${accent}, rgba(10,132,255,0.55), #061021, rgba(10,132,255,0.55), ${accent})`,
        }}
      />
      {/* A bright rim so the disc has an edge against the halo. */}
      <span
        aria-hidden
        className={`absolute size-[108px] rounded-full border-2 transition-opacity duration-500 ${
          isListening ? "opacity-100" : "animate-breath"
        }`}
        style={{ borderColor: accent }}
      />

      {/* 3. The icon, upright — outside the rotating layer. */}
      {isBusy ? (
        <span className="size-9 animate-spin rounded-full border-[3px] border-azure-glow border-t-transparent" />
      ) : (
        <span className="relative size-10 text-on-surface">{icon}</span>
      )}
    </button>
  );
}
