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
      {/* The instruction this screen is: one quiet line, then the sentence. */}
      <p className="mb-2 w-full pl-1 text-label-large text-on-surface-variant">
        {t("practice.listenRepeat")}
      </p>

      {/* The hero, anchored to the top: the sentence and its Listen control on
          one baseline. */}
      <div className="glass-surface shadow-lg shadow-azure-glow/5">
        <div className="flex items-center gap-6 p-8">
          <p lang="de" className="min-w-0 flex-1 hyphens-auto break-words text-3xl font-bold leading-tight text-on-surface">
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

              {/* An honest score: the share of the target's words the recogniser
                  heard. Word matching, not phoneme analysis — the label says
                  what is measured, and the list below shows which words carried
                  the misses. */}
              {wordResults.length > 0 && (
                <p className="mt-4 text-label-large text-on-surface-variant">
                  {t("practice.wordMatch", [
                    Math.round(
                      (wordResults.filter((result) => result.isCorrect).length * 100) /
                        wordResults.length
                    ),
                  ])}
                </p>
              )}

              <p className="mt-4 w-full text-center text-base text-on-surface">
                {spokenText}
              </p>

              {wordResults.length > 0 && (
                <ul className="mt-4 flex w-full flex-col items-center gap-1">
                  {wordResults.map((result, index) => (
                    <li key={`${result.word}-${index}`} className="flex items-center gap-2">
                      {result.isCorrect ? (
                        <svg
                          viewBox="0 0 24 24"
                          className="size-4 shrink-0 text-tertiary"
                          fill="currentColor"
                          role="img"
                          aria-label={t("practice.wordCorrect")}
                        >
                          <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm-1.2 14.6-4.2-4.2 1.4-1.4 2.8 2.8 6-6 1.4 1.4-7.4 7.4Z" />
                        </svg>
                      ) : (
                        <svg
                          viewBox="0 0 24 24"
                          className="size-4 shrink-0 text-warning"
                          fill="currentColor"
                          role="img"
                          aria-label={t("practice.wordTryAgain")}
                        >
                          <path d="M12 2 1 21h22L12 2Zm1 14h-2v2h2v-2Zm0-7h-2v5h2V9Z" />
                        </svg>
                      )}
                      <span
                        className={`text-body-medium ${
                          result.isCorrect ? "text-on-surface" : "text-on-surface-variant"
                        }`}
                      >
                        {result.word}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
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
