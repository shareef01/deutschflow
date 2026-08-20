"use client";

import type { ReactNode } from "react";

/**
 * OracleMic — the recording control, the calm version.
 *
 * One disc: an opaque fill, a hairline edge, the icon upright. Idle it sits
 * quiet in the accent blue; listening, the edge and fill turn up to the cyan
 * and a single ring pulses outside the disc — the one allowed pulse in the
 * app, and only while the microphone is actually open. (Mirror of the Android
 * ui/components/OracleMic.kt redesign: the rotating mesh and radial halo are
 * gone.)
 */
export function OracleMic({
  icon,
  label,
  isListening,
  isBusy,
  onClick,
  large = false,
}: {
  icon: ReactNode;
  label: string;
  isListening: boolean;
  isBusy: boolean;
  onClick: () => void;
  large?: boolean;
}) {
  // The token values, previously retyped as literals.
  const accent = isListening ? "var(--color-azure-glow)" : "var(--color-azure-deep)";

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={isBusy}
      aria-label={label}
      className={`press-scale relative grid place-items-center rounded-full focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-azure-glow ${
        large ? "size-38" : "size-28"
      }`}
    >
      {/* The listening ring: one stroke outside the disc, breathing only while live. */}
      {isListening && (
        <span
          aria-hidden="true"
          className="absolute inset-0 animate-pulse rounded-full border-[1.5px]"
          style={{ borderColor: accent }}
        />
      )}

      {/* The disc itself. */}
      <span
        aria-hidden="true"
        className={`grid place-items-center rounded-full border ${
          large ? "size-32" : "size-24"
        }`}
        style={{
          borderColor: accent,
          backgroundColor: isListening
            ? "color-mix(in srgb, var(--color-azure-glow) 16%, transparent)"
            : "var(--color-glass-raised)",
        }}
      >
        {isBusy ? (
          <span className="size-9 animate-spin rounded-full border-[3px] border-azure-glow border-t-transparent" />
        ) : (
          <span
            className={`${large ? "size-12" : "size-9"} text-on-surface`}
            style={{ color: isListening ? accent : undefined }}
          >
            {icon}
          </span>
        )}
      </span>
    </button>
  );
}
