import { WarningIcon } from "@/components/icons";

/**
 * ErrorBanner — the one way the app tells the user something went wrong in
 * place. The last message outlives the null that starts the exit, so the banner
 * shrinks away with its text intact instead of blanking first.
 *
 * Compose equivalent: ui/components/ErrorBanner.kt.
 */
export function ErrorBanner({ message }: { message: string | null }) {
  if (!message) return null;
  // Announced, not just shown. The Compose counterpart posts the same thing through
  // announceForAccessibility; on the web a live region does fire for content that
  // appears, so this is the supported route rather than a workaround.
  return (
    <div
      // Announced when it appears rather than waiting to be found. A banner
      // saying the microphone was refused, or that German speech is missing, is
      // the answer to "why did nothing happen" — and a user who cannot see it
      // arrive is the one most likely to be asking. `polite` so it follows what
      // the screen reader is already saying instead of cutting across it.
      role="status"
      aria-live="polite"
      className="mb-5 flex w-full items-start gap-3 rounded-xl bg-error-container/30 px-4 py-3 border border-error/20"
    >
      <WarningIcon className="size-5 shrink-0 text-error mt-0.5" />
      <p className="max-w-[60ch] text-sm leading-relaxed text-on-error-container">{message}</p>
    </div>
  );
}
