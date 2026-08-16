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
  return (
    <div className="mb-5 flex w-full items-start gap-3 rounded-xl bg-error-container/30 px-4 py-3 border border-error/20">
      <WarningIcon className="size-5 shrink-0 text-error mt-0.5" />
      <p className="text-sm text-on-error-container leading-relaxed">{message}</p>
    </div>
  );
}
