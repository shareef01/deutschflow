"use client";

import { useEffect, useRef, useState } from "react";
import { useTranscript } from "@/hooks/useTranscript";
import { useSettings } from "@/hooks/useSettings";
import { useI18n } from "@/hooks/useI18n";
import { GlassCard } from "@/components/ui/GlassCard";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { OracleMic } from "@/components/ui/OracleMic";
import { VocabularyChip } from "@/components/ui/VocabularyChip";
import { GlassButton } from "@/components/ui/GlassButton";
import { WordDetailsSheet } from "@/components/ui/WordDetailsSheet";
import { Snackbar } from "@/components/ui/Snackbar";
import { AudioWaveform } from "@/components/ui/AudioWaveform";
import { BookmarkAddIcon, ContentCopyIcon, MicIcon, StopIcon } from "@/components/icons";

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

  const { selectedDialect } = useSettings();

  const [snackbar, setSnackbar] = useState<string | null>(null);
  const snackbarTimer = useRef<number | null>(null);

  const [recordingSeconds, setRecordingSeconds] = useState(0);
  useEffect(() => {
    if (!state.isListening) return;
    setRecordingSeconds(0);
    const timer = window.setInterval(
      () => setRecordingSeconds((seconds) => seconds + 1),
      1_000
    );
    return () => window.clearInterval(timer);
  }, [state.isListening]);
  const duration = `${Math.floor(recordingSeconds / 60)}:${String(
    recordingSeconds % 60
  ).padStart(2, "0")}`;

  const showSnackbar = (message: string) => {
    setSnackbar(message);
    if (snackbarTimer.current !== null) window.clearTimeout(snackbarTimer.current);
    snackbarTimer.current = window.setTimeout(() => setSnackbar(null), 3_000);
  };

  useEffect(() => {
    if (state.wordDetailError) {
      showSnackbar(state.wordDetailError);
      dismissWordDetailError(state.wordDetailError);
    }
  }, [state.wordDetailError, dismissWordDetailError]);

  useEffect(() => () => cancelListening(), [cancelListening]);

  const hasTranscript = state.partialText.length > 0 || state.finalText.length > 0;
  const isEmpty = !hasTranscript && !state.isListening && !isBusy;
  const hasResult = state.translation.length > 0;

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

  if (isEmpty) {
    return (
      <div className="mx-auto flex min-h-0 w-full max-w-[var(--container-workspace)] flex-1 flex-col items-center justify-center px-[var(--gutter)] py-[var(--space-6)]">
        <span className="flex items-center gap-2 rounded-xl bg-secondary-container px-4 py-1.5 text-label-medium text-on-secondary-container">
          <span className="size-2 rounded-full bg-secondary" />
          {t("transcript.language", [selectedDialect])}
        </span>

        <h2 className="mt-6 text-center text-headline-medium text-on-surface">
          {t("transcript.emptyTitle")}
        </h2>
        <p className="mt-2 max-w-sm text-center text-body-medium text-on-surface-variant">
          {t("transcript.emptyBody")}
        </p>

        <OracleMic
          icon={state.isListening ? <StopIcon className="size-full" /> : <MicIcon className="size-full" />}
          label={
            state.isListening ? t("transcript.stopRecording") : t("transcript.startRecording")
          }
          isListening={state.isListening}
          isBusy={isBusy}
          onClick={state.isListening ? stopListening : () => void startListening()}
          large
        />

        <ErrorBanner message={state.errorState} />

        <p className="mt-6 text-label-large text-on-surface-variant">{t("transcript.hint")}</p>

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

  return (
    <div
      className={`mx-auto flex h-full min-h-0 w-full flex-col gap-[var(--space-5)] overflow-y-auto px-[var(--gutter)] py-[var(--space-6)] ${
        hasResult
          ? "max-w-[var(--container-wide)]"
          : "max-w-[var(--container-workspace)]"
      }`}
    >
      <div
        className={`grid min-h-0 items-start gap-[var(--space-6)] ${
          hasResult ? "lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]" : "grid-cols-1"
        }`}
      >
      <div className="flex min-w-0 flex-col gap-[var(--space-5)]">
      <GlassCard
        contentPadding="p-8"
        className={`min-h-[180px] shadow-lg shadow-azure-glow/10 ${
          hasTranscript ? "" : "flex items-center justify-center"
        }`}
      >
        <div
          role="status"
          aria-live="polite"
          className="flex w-full items-start gap-3"
        >
          <p
            className={`min-w-0 flex-1 leading-relaxed ${
              hasTranscript
                ? "text-headline-small text-on-surface"
                : "max-w-md text-center text-body-large text-on-surface-variant"
            }`}
          >
            {hasTranscript
              ? state.isListening
                ? state.partialText
                : state.finalText || state.partialText
              : t("transcript.placeholder")}
          </p>

          {state.finalText.length > 0 && !state.isListening && (
            <button
              type="button"
              onClick={() => void navigator.clipboard?.writeText(state.finalText)}
              aria-label={t("action.copy")}
              className="press-scale rounded-full p-2 text-on-surface-variant transition-opacity hover:opacity-70"
            >
              <ContentCopyIcon className="size-5" />
            </button>
          )}
        </div>

        {state.isListening && (
          <div className="mt-4">
            <AudioWaveform getLevel={() => state.rmsLevel} isActive className="h-10 w-full" />
            <div className="mt-2 flex items-center gap-2">
              <span className="size-2 animate-pulse rounded-full bg-azure-glow" />
              <span className="text-label-large text-azure-glow">{t("transcript.listening")}</span>
              <span className="text-label-medium text-on-surface-variant">{duration}</span>
            </div>
          </div>
        )}
      </GlassCard>

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

        {(state.isListening || isBusy) && (
          <p
            className={`mt-6 flex items-center gap-2.5 text-base font-semibold tracking-wide transition-colors ${
              state.isListening ? "text-azure-glow" : "text-on-surface-muted"
            }`}
          >
            <span
              aria-hidden="true"
              className={`size-2 shrink-0 rounded-full transition-colors ${
                state.isListening
                  ? "animate-pulse bg-azure-glow"
                  : "bg-on-surface-variant/60"
              }`}
            />
            {statusLabel}
          </p>
        )}
      </div>

      {/* Grammar Spotlight Section */}
      {hasResult && state.grammarNotes.length > 0 && (
          <div className="space-y-4 animate-in fade-in slide-in-from-bottom-4 duration-500 delay-200">
              <h2 className="text-[10px] font-black tracking-[0.25em] text-primary uppercase pl-1">
                  Grammar Spotlight
              </h2>
              <div className="flex flex-col gap-3">
                  {state.grammarNotes.map((note, i) => (
                      <div key={i} className="glass-surface p-4 border-l-4 border-primary/40">
                          <div className="flex items-center gap-3">
                              <span className="bg-primary/20 text-primary text-[10px] font-bold px-2 py-0.5 rounded uppercase">
                                  {note.case}
                              </span>
                              <span className="text-sm font-bold text-on-surface">{note.phrase}</span>
                          </div>
                          {note.explanation && (
                              <p className="mt-2 text-xs text-on-surface-variant leading-relaxed">
                                  {note.explanation}
                              </p>
                          )}
                      </div>
                  ))}
              </div>
          </div>
      )}
      </div>

        {hasResult && (
          <aside className="mt-0 w-full min-w-0">
            <div className="flex w-full items-center justify-between">
              <h2 className="text-label-large font-semibold text-secondary">
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
                <h2 className="mt-8 text-label-large font-semibold text-secondary">
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

            <GlassButton type="button" onClick={onSave} className="mt-8 w-full h-14">
              <span className="flex items-center justify-center gap-2">
                <BookmarkAddIcon className="size-5" />
                <span className="text-label-large font-bold">Save to Library</span>
              </span>
            </GlassButton>
          </aside>
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
