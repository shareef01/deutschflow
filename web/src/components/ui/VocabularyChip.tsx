/**
 * VocabularyChip — one extracted vocabulary word as an interactive glass pill.
 * A tap interrogates the word; while the Groq call is in flight the pill shows a
 * small azure spinner in place of nothing — the control stays put and just
 * lights up, rather than being replaced by a spinner.
 *
 * Compose equivalent: ui/components/VocabularyChip.kt.
 */
export function VocabularyChip({
  word,
  isLoading,
  onClick,
}: {
  word: string;
  isLoading: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="glass-pill press-scale inline-flex items-center gap-2 px-5 py-2.5 text-sm font-medium text-on-surface transition-all hover:shadow-md hover:shadow-azure-glow/15 active:shadow-sm focus-visible:outline-2 focus-visible:outline-azure-glow"
    >
      {word}
      {isLoading && (
        <span className="size-3.5 animate-spin rounded-full border-2 border-azure-glow border-t-transparent" />
      )}
    </button>
  );
}
