"use client";

import { useEffect, useMemo } from "react";
import { useStudy } from "@/hooks/useStudy";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { GlassButton } from "@/components/ui/GlassButton";
import { SchoolIcon, VolumeUpIcon } from "@/components/icons";

/**
 * StudyScreen — ui/screens/StudyScreen.kt port.
 *
 * A shuffled snapshot of the library, a 3D flip card, autoplay gated by
 * Settings, and a once-per-session XP award behind the "Got it!" button.
 */
export default function StudyPage() {
  const { t } = useI18n();
  const {
    studyList,
    currentIndex,
    isFlipped,
    hasLoaded,
    ttsError,
    flipCard,
    nextCard,
    autoPlay,
    speak,
    rewardCurrentCard,
  } = useStudy();

  // Coerced once, then used for the card, the bar and the caption alike.
  const safeIndex = Math.min(Math.max(currentIndex, 0), Math.max(studyList.length - 1, 0));
  const currentItem = studyList[safeIndex];

  // Speaks the card unless auto-play is switched off in Settings.
  useEffect(() => {
    if (currentItem) autoPlay(currentItem.germanText);
  }, [currentIndex, currentItem?.id, autoPlay, currentItem]);

  // Hold the frame rather than claiming the library is empty before it is read.
  if (!hasLoaded) return null;

  if (studyList.length === 0) {
    return (
      <EmptyState
        icon={<SchoolIcon className="size-full" />}
        message={t("study.emptyTitle")}
        description={t("study.emptyBody")}
      />
    );
  }

  const progress = ((safeIndex + 1) / studyList.length) * 100;

  return (
    <div className="flex h-full min-h-0 flex-col items-center justify-center overflow-y-auto px-6 py-8">
      {/* Autoplay is the one place the app speaks without being asked, so a
          device that cannot speak German has to say so here. */}
      <ErrorBanner message={ttsError} />

      {/* The session header: what this is and how far through the pass the
          learner is, without outshouting the card itself. */}
      <div className="flex w-full max-w-2xl items-center justify-between">
        <h2 className="text-title-medium text-on-surface">{t("study.session")}</h2>
        <span className="text-label-medium text-on-surface-variant">
          {t("study.remaining", [Math.max(studyList.length - safeIndex, 1)])}
        </span>
      </div>

      {/* The card is centred in what is left rather than filling it. */}
      <div className="flex w-full flex-1 items-center justify-center">
        <button
          type="button"
          onClick={flipCard}
          aria-label={isFlipped ? t("study.showGerman") : t("study.showTranslation")}
          className="glass-surface block w-full min-h-[260px] max-h-[440px] max-w-2xl [perspective:1200px] focus-visible:outline-2 focus-visible:outline-azure-glow shadow-xl shadow-azure-glow/10 hover:shadow-2xl hover:shadow-azure-glow/20 transition-shadow"
        >
          <div
            className={`relative h-full w-full transition-transform duration-500 [transform-style:preserve-3d] ${
              isFlipped ? "[transform:rotateY(180deg)]" : ""
            }`}
          >
            {/* Front: German */}
            <div className="absolute inset-0 flex items-center justify-center [backface-visibility:hidden]">
              <div className="flex flex-col items-center px-7 py-8 text-center">
                <span className="text-label-small font-medium text-on-surface-muted">
                  {t("library.fieldGerman")}
                </span>
                <h2 className="mt-3 text-3xl font-bold text-azure-glow">{currentItem.germanText}</h2>
                <span
                  role="button"
                  tabIndex={0}
                  onClick={(event) => {
                    event.stopPropagation();
                    speak(currentItem.germanText);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.stopPropagation();
                      speak(currentItem.germanText);
                    }
                  }}
                  aria-label={t("action.speak")}
                  className="press-scale mt-5 rounded-full p-3 text-azure-glow hover:opacity-80 transition-opacity"
                >
                  <VolumeUpIcon className="size-7" />
                </span>
                <span className="mt-5 text-xs font-medium text-on-surface-muted uppercase tracking-wider">{t("study.tapToFlip")}</span>
              </div>
            </div>

            {/* Back: Translation */}
            <div className="absolute inset-0 flex items-center justify-center [transform:rotateY(180deg)] [backface-visibility:hidden]">
              <div className="flex flex-col items-center px-7 py-8 text-center">
                <span className="text-label-small font-medium text-on-surface-muted">
                  {t("library.fieldTranslation")}
                </span>
                <h2 className="mt-3 text-3xl font-bold text-on-surface">
                  {currentItem.englishTranslation}
                </h2>
                <span className="mt-5 text-lg font-black text-tertiary">{t("study.gotIt")}</span>
              </div>
            </div>
          </div>
        </button>
      </div>

      {/* The honest feedback row. All four advance the card; Good and Easy bank
          the XP award (once per card, per session). The spaced-repetition
          scheduler that would steer WHEN a card returns is not implemented yet
          — the UI is shaped for it, nothing pretends it already remembers. */}
      {/* Four grades across a 320px screen give each about 70px, which cannot
          hold its label. Two-by-two first, one row once there is room. */}
      <div className="mt-[var(--space-6)] grid w-full max-w-2xl grid-cols-2 gap-[var(--space-3)] sm:grid-cols-4">
        <GlassButton type="button" glow="error" onClick={nextCard} className="w-full">
          <span className="text-sm font-bold">{t("study.again")}</span>
        </GlassButton>
        <GlassButton type="button" glow="amber" onClick={nextCard} className="w-full">
          <span className="text-sm font-bold">{t("study.hard")}</span>
        </GlassButton>
        <GlassButton
          type="button"
          onClick={() => {
            rewardCurrentCard();
            nextCard();
          }}
          className="w-full"
        >
          <span className="text-sm font-bold">{t("study.good")}</span>
        </GlassButton>
        <GlassButton
          type="button"
          glow="green"
          onClick={() => {
            rewardCurrentCard();
            nextCard();
          }}
          className="w-full"
        >
          <span className="text-sm font-bold">{t("study.easy")}</span>
        </GlassButton>
      </div>

      <div className="mt-8 h-2 w-full max-w-2xl overflow-hidden rounded-full bg-surface-variant">
        <div
          className="h-full rounded-full bg-azure-glow transition-[width] duration-300"
          style={{ width: `${progress}%` }}
        />
      </div>
      <p className="mt-3 text-xs font-medium text-on-surface-variant uppercase tracking-wider">
        {t("study.progress", [safeIndex + 1, studyList.length])}
      </p>
    </div>
  );
}
