"use client";

import { useEffect } from "react";
import { usePractice } from "@/hooks/usePractice";
import { useI18n } from "@/hooks/useI18n";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { GlassButton } from "@/components/ui/GlassButton";
import { AudioWaveform } from "@/components/ui/AudioWaveform";
import { MicIcon, NavigateNextIcon, StopIcon, VolumeUpIcon } from "@/components/icons";
import { PRACTICE_FEEDBACK_KEYS } from "@/lib/scoring";

/**
 * PracticeScreen — ui/screens/PracticeScreen.kt port.
 *
 * The hero sentence (with word-by-word verdict colours after an attempt), the
 * result region that owns the space beneath it (live waveform → spinner →
 * verdict), and the two glass actions — Speak/Evaluate taking the error edge
 * while recording.
 */
export default function PracticePage() {
  const { t } = useI18n();
  const {
    targetSentence,
    feedback,
    wordResults,
    partialText,
    spokenText,
    isListening,
    isProcessing,
    rmsLevel,
    errorState,
    startPractice,
    stopPractice,
    cancelListening,
    speak,
    nextSentence,
  } = usePractice();

  // OnLeavingScreen: navigating away cancels a recording in flight.
  useEffect(() => () => cancelListening(), [cancelListening]);

  const isPositive = feedback === "PERFECT";
  const feedbackText =
    feedback === "NONE" ? null : t(PRACTICE_FEEDBACK_KEYS[feedback]);

  return (
    <div className="mx-auto flex h-full min-h-0 w-full max-w-3xl flex-col overflow-y-auto px-6 py-8">
      {/* The hero, anchored to the top: the sentence and its Listen control on
          one baseline. */}
      <div className="glass-surface shadow-lg shadow-azure-glow/5">
        <div className="flex items-center gap-6 p-8">
          <p className="min-w-0 flex-1 text-3xl font-bold leading-tight text-on-surface">
            {wordResults.length === 0
              ? targetSentence
              : wordResults.map((result) => (
                  <span
                    key={result.word}
                    className={
                      result.isCorrect ? "font-bold text-azure-glow" : "font-bold text-error"
                    }
                  >
                    {result.word}{" "}
                  </span>
                ))}
          </p>
          <button
            type="button"
            onClick={() => speak(targetSentence)}
aria-label={t("practice.listen")}
            className="glass-button press-scale flex size-14 shrink-0 items-center justify-center text-azure-glow"
          >
            <VolumeUpIcon className="size-7" />
          </button>
        </div>
      </div>

      <ErrorBanner message={errorState} />

      {/* The result region: one glass card that owns the space between the hero
          and the actions. */}
      <div className="w-full flex-1 pt-6">
        <div className="glass-surface p-8 shadow-lg shadow-azure-glow/5">
          {isListening ? (
            <div>
              {partialText && (
                <p className="line-clamp-3 w-full text-center text-base text-on-surface">
                  {partialText}
                </p>
              )}
              <AudioWaveform
                getLevel={() => rmsLevel}
                isActive
                className="mt-4 h-16 w-full"
              />
            </div>
          ) : isProcessing ? (
            <div className="flex min-h-[96px] w-full items-center justify-center">
              <span className="size-9 animate-spin rounded-full border-[3px] border-azure-glow border-t-transparent" />
            </div>
          ) : spokenText.length === 0 ? (
            <p className="w-full px-4 py-8 text-center text-sm text-on-surface-variant">
              {t("practice.intro")}
            </p>
          ) : (
            <div className="flex w-full flex-col items-center">
              {feedbackText && (
                <span
                  className={`rounded-full px-5 py-2 text-center text-sm font-medium ${
                    isPositive
                      ? "bg-tertiary-container text-on-tertiary-container"
                      : "bg-error-container text-on-error-container"
                  }`}
                >
                  {feedbackText}
                </span>
              )}
              <p className="mt-5 w-full text-center text-base text-on-surface">
                {spokenText}
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Both actions are glass. Speak takes the error role only while actually
          recording (stop-the-world), and returns to the cyan edge otherwise. */}
      <div className="mt-8 flex w-full gap-4 pb-6">
        <GlassButton
          type="button"
          disabled={isProcessing}
          glow={isListening ? "error" : "azure"}
          onClick={isListening ? stopPractice : () => void startPractice()}
          className="flex-1"
        >
          <span className="flex items-center justify-center gap-2">
            {isListening ? <StopIcon className="size-5" /> : <MicIcon className="size-5" />}
            <span
              className={`text-sm font-bold ${
                isListening ? "text-error" : "text-on-surface"
              }`}
            >
              {isListening ? t("practice.evaluate") : t("practice.speak")}
            </span>
          </span>
        </GlassButton>
        <GlassButton type="button" onClick={nextSentence} className="flex-1">
          <span className="flex items-center justify-center gap-2">
            <NavigateNextIcon className="size-5" />
            <span className="text-sm font-bold">{t("practice.next")}</span>
          </span>
        </GlassButton>
      </div>
    </div>
  );
}
