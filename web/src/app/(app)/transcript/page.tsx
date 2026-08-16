"use client";

import { useEffect, useRef, useState } from "react";
import { useTranscript } from "@/hooks/useTranscript";
import { useI18n } from "@/hooks/useI18n";
import { GlassCard } from "@/components/ui/GlassCard";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { OracleMic } from "@/components/ui/OracleMic";
import { VocabularyChip } from "@/components/ui/VocabularyChip";
import { GlassButton } from "@/components/ui/GlassButton";
import { WordDetailsSheet } from "@/components/ui/WordDetailsSheet";
import { Snackbar } from "@/components/ui/Snackbar";
import { BookmarkAddIcon, ContentCopyIcon, MicIcon, StopIcon } from "@/components/icons";

/**
 * TranscriptScreen — ui/screens/TranscriptScreen.kt port.
 *
 * Mirrors the Android screen: the reserved transcript card, the theatrical mic
 * (breathes idle, burns while listening), the status label, the translation
 * with copy, the tappable vocabulary chips, the single glowing Save button, and
 * the snackbar + word-details sheet.
 */
export default function TranscriptPage() {
  const { t } = useI18n();
  const {
    state,
    isBusy,
    startListening,
    stopListening,
    cancelListening,
    saveToVocabulary,
    interrogateWord,
    saveWordDetails,
    dismissWordDetails,
    dismissWordDetailError,
  } = useTranscript();

  const [snackbar, setSnackbar] = useState<string | null>(null);
  const snackbarTimer = useRef<number | null>(null);

  const showSnackbar = (message: string) => {
    setSnackbar(message);
    if (snackbarTimer.current !== null) window.clearTimeout(snackbarTimer.current);
    snackbarTimer.current = window.setTimeout(() => setSnackbar(null), 3_000);
  };

  // A failed interrogation surfaces through the same snackbar as a save — the
  // recording banner has nothing to do with it. The message is handed back when
  // cleared, so a failure already superseded is left alone.
  useEffect(() => {
    if (state.wordDetailError) {
      showSnackbar(state.wordDetailError);
      dismissWordDetailError(state.wordDetailError);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.wordDetailError]);

  // OnLeavingScreen: navigating to another tab unmounts this page, which is
  // what cancels a recording in flight — half a sentence must not be filed.
  useEffect(() => () => cancelListening(), [cancelListening]);

  const hasTranscript = state.partialText.length > 0 || state.finalText.length > 0;
  const transcriptText = hasTranscript
    ? state.isListening
      ? state.partialText
      : state.finalText || state.partialText
    : t("transcript.placeholder");

  const statusLabel = isBusy
    ? t("transcript.transcribing")
    : state.isListening
      ? t("transcript.listening")
      : t("transcript.hint");

  const onSave = () => {
    if (saveToVocabulary(state.finalText, state.translation)) {
      showSnackbar(t("transcript.saved"));
    }
  };

  const onCopy = () => {
    void navigator.clipboard?.writeText(state.translation);
  };

  return (
    <div className="mx-auto flex h-full min-h-0 w-full max-w-3xl flex-col gap-6 overflow-y-auto px-6 py-8">
      {/* The transcript area keeps its minimum footprint, so the mic below does
          not jump upward the moment text streams in. */}
      <GlassCard
        contentPadding="p-8"
        className={`min-h-[180px] shadow-lg shadow-azure-glow/10 ${
          hasTranscript ? "" : "flex items-center justify-center"
        }`}
      >
        <p
          className={`leading-relaxed font-medium ${
            hasTranscript
              ? "text-xl text-on-surface"
              : "max-w-md text-center text-lg font-semibold text-on-surface-muted"
          }`}
        >
          {transcriptText}
        </p>
      </GlassCard>

      {/* Everything below the transcript shares what the card leaves, and
          scrolls once a translation arrives. */}
      <div className="flex flex-1 flex-col items-center justify-center pb-6">
        <ErrorBanner message={state.errorState} />

        <OracleMic
          icon={state.isListening ? <StopIcon className="size-full" /> : <MicIcon className="size-full" />}
          label={
            state.isListening ? t("transcript.stopRecording") : t("transcript.startRecording")
          }
          isListening={state.isListening}
          isBusy={isBusy}
          onClick={state.isListening ? stopListening : () => void startListening()}
        />

        <p
          className={`mt-6 flex items-center gap-2.5 text-base font-bold tracking-wide transition-colors ${
            state.isListening ? "text-azure-glow" : "text-on-surface-muted"
          }`}
        >
          <span
            aria-hidden="true"
            className={`size-2 shrink-0 rounded-full transition-colors ${
              state.isListening
                ? "animate-pulse bg-azure-glow shadow-[0_0_8px_rgba(0,229,255,0.8)]"
                : "bg-on-surface-variant/60"
            }`}
          />
          {statusLabel}
        </p>

        {state.translation.length > 0 && (
          <div className="mt-8 w-full">
            <div className="flex w-full items-center justify-between">
              <h2 className="text-base font-bold text-azure-glow uppercase tracking-wider">
                {t("transcript.translation")}
              </h2>
              <button
                type="button"
                onClick={onCopy}
                aria-label={t("action.copy")}
                className="press-scale rounded-full p-2 text-on-surface-variant transition-opacity hover:opacity-70"
              >
                <ContentCopyIcon className="size-5" />
              </button>
            </div>

            <div className="glass-surface mt-4 p-6">
              <p className="text-base leading-relaxed">{state.translation}</p>
            </div>

            {state.suggestedWords.length > 0 && (
              <>
                <h2 className="mt-8 text-base font-bold text-azure-glow uppercase tracking-wider">
                  {t("transcript.vocabulary")}
                </h2>
                <div className="mt-4 flex flex-wrap gap-3">
                  {state.suggestedWords.map((word) => (
                    <VocabularyChip
                      key={word}
                      word={word}
                      isLoading={state.interrogatingWord === word}
                      onClick={() => interrogateWord(word)}
                    />
                  ))}
                </div>
              </>
            )}

            <GlassButton type="button" onClick={onSave} className="mt-8 w-full">
              <span className="flex items-center justify-center gap-2">
                <BookmarkAddIcon className="size-5" />
                <span className="text-label-large font-bold">{t("transcript.save")}</span>
              </span>
            </GlassButton>
          </div>
        )}
      </div>

      <Snackbar message={snackbar} />

      <WordDetailsSheet
        details={state.wordDetails}
        onDismiss={dismissWordDetails}
        onSave={(details) => {
          const saved = saveWordDetails(details);
          dismissWordDetails();
          if (saved) showSnackbar(t("transcript.saved"));
        }}
      />
    </div>
  );
}
