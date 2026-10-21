"use client";

import { BookmarkAddIcon } from "@/components/icons";
import { GlassButton } from "./GlassButton";
import { GlassCard } from "./GlassCard";
import { useI18n } from "@/hooks/useI18n";
import { useDialogFocus } from "@/hooks/useDialogFocus";
import type { WordDetails } from "@/lib/ai/groq";
import { savesUnderAnotherWord } from "@/lib/db/schema";

/**
 * WordDetailsSheet — the interrogation result, in a bottom sheet on the dark
 * theme. The word, article and plural are read first; the English meaning and a
 * contextual German example follow, and one glowing button saves exactly this
 * structured word to the library.
 *
 * Compose equivalent: ui/components/WordDetailsBottomSheet.kt.
 */
export function WordDetailsSheet({
  details,
  tappedWord = null,
  onDismiss,
  onSave,
}: {
  details: WordDetails | null;
  /** The chip that was tapped; the lookup may answer with its dictionary form. */
  tappedWord?: string | null;
  onDismiss: () => void;
  onSave: (details: WordDetails) => void;
}) {
  // Split so the open sheet is its own component: useDialogFocus has to run on
  // mount with the container in the tree, and a hook called above an early
  // `return null` would run once against a ref that was never attached.
  if (details === null) return null;
  return (
    <OpenWordDetailsSheet details={details} tappedWord={tappedWord} onDismiss={onDismiss} onSave={onSave} />
  );
}

function OpenWordDetailsSheet({
  details,
  tappedWord,
  onDismiss,
  onSave,
}: {
  details: WordDetails;
  tappedWord: string | null;
  onDismiss: () => void;
  onSave: (details: WordDetails) => void;
}) {
  const { t } = useI18n();
  const sheetRef = useDialogFocus<HTMLDivElement>(onDismiss);

  const facts = [
    details.article && details.article !== "none"
      ? t("wordSheet.article", [details.article])
      : "",
    details.plural ? t("wordSheet.plural", [details.plural]) : "",
    details.conjugationOrInfinitive
      ? t("wordSheet.verb", [details.conjugationOrInfinitive])
      : "",
  ].filter(Boolean);

  return (
    <div
      ref={sheetRef}
      tabIndex={-1}
      className="fixed inset-0 z-50 outline-none"
      role="dialog"
      aria-modal="true"
      aria-label={details.word}
    >
      {/* Backdrop */}
      <button
        type="button"
        aria-label={t("action.back")}
        onClick={onDismiss}
        className="absolute inset-0 animate-backdrop-in cursor-default bg-black/60 backdrop-blur-sm"
      />

      {/* The sheet */}
      <div className="absolute inset-x-0 bottom-0 mx-auto w-full max-w-lg animate-sheet-up rounded-t-[24px] bg-surface-container px-4 pb-6 pt-4 shadow-2xl">
        <div className="mx-auto mb-4 h-1 w-10 rounded-full bg-surface-container-highest" />

        <div className="flex flex-col items-center text-center">
          <h2 className="text-headline-medium text-primary">{details.word}</h2>

          {/* The lookup returns the dictionary form, and that is what Save files. When
              it is not the word that was tapped ("gehen" for "ging"), say so, so the
              save - which may fold into an entry already under that name - is no
              surprise. */}
          {savesUnderAnotherWord(tappedWord, details.word) && (
            <p className="mt-1 text-body-small text-on-surface-variant">
              {t("wordSheet.dictionaryForm", [tappedWord ?? ""])}
            </p>
          )}

          {facts.length > 0 && (
            <p className="mt-2 text-label-large text-on-surface-variant">{facts.join("  ·  ")}</p>
          )}

          <div className="mt-6">
            <p className="text-label-medium font-bold text-primary">
              {t("wordSheet.meaning")}
            </p>
            <p className="mt-1 text-body-large text-on-surface">{details.meaning}</p>
          </div>

          {details.exampleSentence && (
            <GlassCard className="mt-6 w-full text-left" contentPadding="p-4">
              <p className="text-label-medium font-bold text-primary">{t("detail.example")}</p>
              <p className="mt-2 text-body-large text-on-surface">{details.exampleSentence}</p>
            </GlassCard>
          )}

          <GlassButton type="button" onClick={() => onSave(details)} className="mt-6 w-full">
            <span className="flex items-center justify-center gap-2">
              <BookmarkAddIcon className="size-5" />
              <span className="text-label-large font-bold text-azure-glow">
                {t("transcript.save")}
              </span>
            </span>
          </GlassButton>
        </div>
      </div>
    </div>
  );
}
