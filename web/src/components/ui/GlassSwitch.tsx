"use client";

/**
 * GlassSwitch — the auto-play toggle. A glass track with an azure knob; the
 * only switch-shaped control the app has, matching the Material Switch role.
 *
 * The button is 44px tall and the track is drawn inside it, rather than the
 * button *being* the 28px track. A switch is the smallest control in the app and
 * it sits in a settings list, where a mis-tap silently flips a setting rather
 * than doing nothing visible — so it is the one most worth making easy to hit.
 * The track keeps its dimensions; only the hit area grew.
 */
export function GlassSwitch({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className="press-scale flex h-11 w-12 shrink-0 items-center justify-center"
    >
      <span
        aria-hidden="true"
        className={`relative block h-7 w-12 rounded-full border transition-colors duration-200 ${
          checked
            ? "border-azure-glow/60 bg-primary-container"
            : "border-outline bg-surface-container-high"
        }`}
      >
        <span
          className={`absolute top-1/2 size-5 -translate-y-1/2 rounded-full transition-all duration-200 ${
            checked ? "left-6 bg-azure-glow" : "left-1 bg-on-surface-variant"
          }`}
        />
      </span>
    </button>
  );
}
