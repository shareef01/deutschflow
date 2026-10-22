"use client";

import { BookmarkAddIcon, CloseIcon, VolumeUpIcon } from "@/components/icons";
import { GlassButton } from "./GlassButton";
import { GlassCard } from "./GlassCard";
import { useI18n } from "@/hooks/useI18n";
import { useDialogFocus } from "@/hooks/useDialogFocus";
import { tts } from "@/lib/speech/tts";
import type { WordDetails } from "@/lib/ai/groq";
import { savesUnderAnotherWord } from "@/lib/db/schema";

/**
 * WordDetailsSheet — the interrogation result, in an accessible bottom sheet (mobile)
 * or centered dialog (desktop). The word, article, plural, and TTS are presented first;
 * the English meaning and contextual German example follow, with an explicit save button.
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

  const handleSpeak = () => {
    tts.speak(details.word);
  };

  return (
    <div
      ref={sheetRef}
      tabIndex={-1}
      className="fixed inset-0 z-50 flex items-end justify-center sm:items-center sm:p-4 outline-none"
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

      {/* The sheet / dialog */}
      <div className="relative z-10 w-full max-w-lg max-h-[85dvh] overflow-y-auto animate-sheet-up rounded-t-[24px] sm:rounded-[24px] bg-surface-container px-6 pb-[calc(1.5rem+env(safe-area-inset-bottom))] pt-4 shadow-2xl border border-azure-glow/20">
        {/* Mobile drag handle */}
        <div className="mx-auto mb-3 h-1 w-10 rounded-full bg-surface-container-highest sm:hidden" />

        {/* Top bar with audio and close action */}
        <div className="flex items-center justify-between gap-2 mb-2">
          <button
            type="button"
            onClick={handleSpeak}
            aria-label={t("action.speak")}
            className="press-scale rounded-full p-2 text-azure-glow hover:bg-azure-glow/10 transition-colors focus-visible:outline-2 focus-visible:outline-azure-glow"
          >
            <VolumeUpIcon className="size-5" />
          </button>
          <button
            type="button"
            onClick={onDismiss}
            aria-label={t("action.close")}
            className="press-scale rounded-full p-2 text-on-surface-variant hover:text-on-surface hover:bg-surface-variant/40 transition-colors focus-visible:outline-2 focus-visible:outline-azure-glow"
          >
            <CloseIcon className="size-5" />
          </button>
        </div>

        <div className="flex flex-col items-center text-center">
          <h2 className="text-headline-medium font-bold text-primary hyphens-auto break-words max-w-full">
            {details.word}
          </h2>

          {/* Dictionary form notice */}
          {savesUnderAnotherWord(tappedWord, details.word) && (
            <p className="mt-1 text-body-small text-on-surface-variant">
              {t("wordSheet.dictionaryForm", [tappedWord ?? ""])}
            </p>
          )}

          {facts.length > 0 && (
            <p className="mt-2 text-label-large font-medium text-on-surface-variant">
              {facts.join("  •  ")}
            </p>
          )}

          <div className="mt-5 w-full">
            <p className="text-label-medium font-bold uppercase tracking-wider text-primary">
              {t("wordSheet.meaning")}
            </p>
            <p className="mt-1 text-body-large text-on-surface leading-relaxed">{details.meaning}</p>
          </div>

          {details.exampleSentence && (
            <GlassCard className="mt-5 w-full text-left" contentPadding="p-4">
              <p className="text-label-medium font-bold uppercase tracking-wider text-primary">
                {t("detail.example")}
              </p>
              <p lang="de" className="mt-1.5 text-body-large text-on-surface italic leading-relaxed">
                „{details.exampleSentence}“
              </p>
            </GlassCard>
          )}

          <GlassButton type="button" onClick={() => onSave(details)} className="mt-6 w-full min-h-[48px]">
            <span className="flex items-center justify-center gap-2">
              <BookmarkAddIcon className="size-5 text-azure-glow" />
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
