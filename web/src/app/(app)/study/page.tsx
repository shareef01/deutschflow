"use client";

import { useEffect, useState } from "react";
import { useStudy } from "@/hooks/useStudy";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { GlassButton } from "@/components/ui/GlassButton";
import { CheckIcon, RefreshIcon, SchoolIcon, VolumeUpIcon } from "@/components/icons";
import { ReviewQuality } from "@/lib/ai/srs";
import { DashboardContent } from "@/components/ui/DashboardContent";

export default function StudyPage() {
  const [selectedTab, setSelectedTab] = useState<"dashboard" | "flashcards">("dashboard");
  const { t } = useI18n();

  return (
    <div className="flex h-full flex-col">
        {/* Tab Selector */}
        <div className="flex w-full justify-center gap-8 border-b border-on-surface/5 bg-background/50 backdrop-blur-md">
            {(["dashboard", "flashcards"] as const).map((tab) => (
                <button
                    key={tab}
                    onClick={() => setSelectedTab(tab)}
                    className={`px-6 py-4 text-sm font-bold uppercase tracking-widest transition-all ${
                        selectedTab === tab
                        ? "text-primary border-b-2 border-primary"
                        : "text-on-surface-variant hover:text-on-surface"
                    }`}
                >
                    {tab === "dashboard" ? t("dashboard.tab") : t("dashboard.flashcardsTab")}
                </button>
            ))}
        </div>

        <div className="flex-1 min-h-0">
            {selectedTab === "dashboard" ? (
              <DashboardContent />
            ) : (
              <FlashcardMode onNavigateToDashboard={() => setSelectedTab("dashboard")} />
            )}
        </div>
    </div>
  );
}

function FlashcardMode({ onNavigateToDashboard }: { onNavigateToDashboard: () => void }) {
  const { t } = useI18n();
  const {
    studyList,
    totalWords,
    currentIndex,
    isFlipped,
    hasLoaded,
    isExtraPractice,
    reviewError,
    ttsError,
    flipCard,
    submitReview,
    skipCard,
    restartSession,
    autoPlay,
    speak,
  } = useStudy();

  const safeIndex = Math.min(Math.max(currentIndex, 0), Math.max(studyList.length - 1, 0));
  const currentItem = studyList[safeIndex];

  useEffect(() => {
    if (currentItem) autoPlay(currentItem.germanText);
  }, [currentIndex, currentItem?.id, autoPlay, currentItem]);

  if (!hasLoaded) return null;

  if (totalWords === 0) {
    return (
      <EmptyState
        icon={<SchoolIcon className="size-full" />}
        message={t("study.emptyTitle")}
        description={t("study.emptyBody")}
      />
    );
  }

  if (studyList.length === 0) {
    return (
      <div className="flex h-full min-h-0 flex-col items-center justify-center p-6">
        <div className="glass-surface flex w-full max-w-md flex-col items-center p-8 text-center shadow-xl shadow-azure-glow/10">
          <div className="flex size-16 items-center justify-center rounded-full bg-primary/20 text-primary">
            <CheckIcon className="size-8" />
          </div>
          <h2 className="mt-6 text-2xl font-bold text-on-surface">
            {t("study.completedTitle")}
          </h2>
          <p className="mt-2 text-body-medium text-on-surface-variant">
            {t("study.completedBody")}
          </p>
          <GlassButton
            type="button"
            onClick={onNavigateToDashboard}
            className="mt-8 h-12 w-full"
          >
            <span className="font-bold">{t("study.completedAction")}</span>
          </GlassButton>
          <button
            type="button"
            onClick={() => void restartSession()}
            className="mt-4 flex items-center justify-center gap-2 text-sm font-semibold text-primary hover:underline"
          >
            <RefreshIcon className="size-4" />
            <span>{t("study.completedRestart")}</span>
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col items-center justify-center overflow-y-auto px-6 py-8">
      <ErrorBanner message={reviewError ? t(reviewError) : ttsError} />

      {/* Nothing was due, so this sitting is a bonus. Said out loud, because a
          schedule that quietly does not move is indistinguishable from one that
          is broken. */}
      {isExtraPractice && studyList.length > 0 && (
        <p className="px-1 pb-2 text-center text-label-medium text-on-surface-variant">
          {t("study.extraPractice")}
        </p>
      )}

      <div className="flex w-full max-w-2xl items-center justify-between">
        <h2 className="text-title-medium text-on-surface">{t("study.session")}</h2>
        <span className="text-label-medium text-on-surface-variant">
          {t("study.remaining", [studyList.length])}
        </span>
      </div>

      <div className="flex w-full flex-1 items-center justify-center">
        <div
          role="region"
          tabIndex={0}
          onClick={flipCard}
          onKeyDown={(event) => {
            if (event.target === event.currentTarget && (event.key === "Enter" || event.key === " ")) {
              event.preventDefault();
              flipCard();
            }
          }}
          aria-label={isFlipped ? t("study.showGerman") : t("study.showTranslation")}
          className="glass-surface block w-full min-h-[260px] max-h-[440px] max-w-2xl [perspective:1200px] cursor-pointer focus-visible:outline-2 focus-visible:outline-azure-glow shadow-xl shadow-azure-glow/10 hover:shadow-2xl hover:shadow-azure-glow/20 transition-shadow select-none"
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
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    speak(currentItem.germanText);
                  }}
                  aria-label={t("action.speak")}
                  className="press-scale mt-5 rounded-full p-3 text-azure-glow hover:opacity-80 transition-opacity focus-visible:outline-2 focus-visible:outline-azure-glow"
                >
                  <VolumeUpIcon className="size-7" />
                </button>
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
                <div className="mt-4 flex flex-col gap-1">
                    {currentItem.article !== "none" && (
                         <span className="text-sm font-semibold text-secondary">{currentItem.article} {currentItem.germanText}</span>
                    )}
                    {currentItem.plural && (
                         <span className="text-xs text-on-surface-muted">pl. {currentItem.plural}</span>
                    )}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="mt-6 grid w-full max-w-2xl grid-cols-2 gap-3 sm:grid-cols-4">
        <GlassButton type="button" glow="error" onClick={() => submitReview(ReviewQuality.AGAIN)} className="w-full">
          <span className="text-sm font-bold uppercase tracking-wider">{t("study.again")}</span>
        </GlassButton>
        <GlassButton type="button" glow="amber" onClick={() => submitReview(ReviewQuality.HARD)} className="w-full">
          <span className="text-sm font-bold uppercase tracking-wider">{t("study.hard")}</span>
        </GlassButton>
        <GlassButton type="button" onClick={() => submitReview(ReviewQuality.GOOD)} className="w-full">
          <span className="text-sm font-bold uppercase tracking-wider">{t("study.good")}</span>
        </GlassButton>
        <GlassButton type="button" glow="green" onClick={() => submitReview(ReviewQuality.EASY)} className="w-full">
          <span className="text-sm font-bold uppercase tracking-wider">{t("study.easy")}</span>
        </GlassButton>
      </div>

      <button
        type="button"
        onClick={skipCard}
        className="mt-6 text-sm font-medium text-on-surface-muted hover:text-on-surface transition-colors"
      >
        {t("study.skip")}
      </button>
    </div>
  );
}
