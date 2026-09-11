"use client";

import { useEffect, useState } from "react";
import { useStudy } from "@/hooks/useStudy";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { GlassButton } from "@/components/ui/GlassButton";
import { SegmentedTabs } from "@/components/ui/SegmentedTabs";
import { CheckIcon, RefreshIcon, SchoolIcon, VolumeUpIcon } from "@/components/icons";
import { ReviewQuality } from "@/lib/ai/srs";
import { DashboardContent } from "@/components/ui/DashboardContent";

type TabType = "dashboard" | "flashcards";

export default function StudyPage() {
  const study = useStudy();
  const [selectedTab, setSelectedTab] = useState<TabType>("dashboard");
  const [hasUserSelectedTab, setHasUserSelectedTab] = useState(false);
  const { t } = useI18n();

  useEffect(() => {
    if (!hasUserSelectedTab && study.status === "ready") {
      if (study.dueCount > 0) {
        setSelectedTab("flashcards");
      } else {
        setSelectedTab("dashboard");
      }
    }
  }, [hasUserSelectedTab, study.status, study.dueCount]);

  const handleTabSelect = (tab: TabType) => {
    setHasUserSelectedTab(true);
    setSelectedTab(tab);
  };

  return (
    <div className="flex h-full flex-col">
      <SegmentedTabs
        value={selectedTab}
        onValueChange={handleTabSelect}
        tabs={[
          { id: "dashboard", label: t("dashboard.tab") },
          { id: "flashcards", label: t("dashboard.flashcardsTab") },
        ]}
        ariaLabel={t("nav.study")}
      />

      <div className="flex-1 min-h-0">
        <div
          id="tabpanel-dashboard"
          role="tabpanel"
          aria-labelledby="tab-dashboard"
          hidden={selectedTab !== "dashboard"}
          className="h-full"
        >
          {selectedTab === "dashboard" && <DashboardContent />}
        </div>

        <div
          id="tabpanel-flashcards"
          role="tabpanel"
          aria-labelledby="tab-flashcards"
          hidden={selectedTab !== "flashcards"}
          className="h-full"
        >
          {selectedTab === "flashcards" && (
            <FlashcardMode
              study={study}
              onNavigateToDashboard={() => handleTabSelect("dashboard")}
            />
          )}
        </div>
      </div>
    </div>
  );
}

function FlashcardMode({
  study,
  onNavigateToDashboard,
}: {
  study: ReturnType<typeof useStudy>;
  onNavigateToDashboard: () => void;
}) {
  const { t } = useI18n();
  const {
    studyList,
    totalWords,
    currentIndex,
    isFlipped,
    status,
    loadError,
    retry,
    isExtraPractice,
    reviewError,
    ttsError,
    flipCard,
    submitReview,
    skipCard,
    restartSession,
    autoPlay,
    speak,
  } = study;

  const safeIndex = Math.min(Math.max(currentIndex, 0), Math.max(studyList.length - 1, 0));
  const currentItem = studyList[safeIndex];

  useEffect(() => {
    if (currentItem) autoPlay(currentItem.germanText);
  }, [currentIndex, currentItem?.id, autoPlay, currentItem]);

  if (status === "loading") {
    return (
      <div className="flex h-full min-h-[300px] items-center justify-center">
        <div className="size-8 animate-spin rounded-full border-2 border-azure-glow border-t-transparent" />
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className="flex h-full min-h-[300px] flex-col items-center justify-center p-6 text-center">
        <p className="text-body-large text-error">{loadError ? t(loadError) : t("study.loadError")}</p>
        <button
          type="button"
          onClick={() => void retry()}
          className="glass-button mt-4 px-5 py-2 text-label-large font-semibold text-azure-glow hover:underline"
        >
          {t("action.retry")}
        </button>
      </div>
    );
  }

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

      <div className="flex w-full flex-1 items-center justify-center py-4">
        <div className="relative w-full max-w-2xl min-h-[260px]">
          {/* Main accessible flip card button */}
          <button
            type="button"
            onClick={flipCard}
            aria-expanded={isFlipped}
            aria-label={isFlipped ? t("study.showGerman") : t("study.showTranslation")}
            className="glass-surface relative block w-full min-h-[260px] max-h-[440px] [perspective:1200px] cursor-pointer text-left focus-visible:outline-2 focus-visible:outline-azure-glow shadow-xl shadow-azure-glow/10 hover:shadow-2xl hover:shadow-azure-glow/20 transition-shadow select-none rounded-2xl motion-reduce:transition-none"
          >
            <div
              className={`relative h-full w-full min-h-[260px] transition-transform duration-500 [transform-style:preserve-3d] motion-reduce:transition-none ${
                isFlipped ? "[transform:rotateY(180deg)]" : ""
              }`}
            >
              {/* Front: German */}
              <div className="absolute inset-0 flex items-center justify-center [backface-visibility:hidden]">
                <div className="flex flex-col items-center px-7 py-8 text-center">
                  <span className="text-label-small font-medium text-on-surface-muted">
                    {t("library.fieldGerman")}
                  </span>
                  <span className="mt-3 block text-3xl font-bold text-azure-glow">{currentItem.germanText}</span>
                  <span className="mt-8 text-xs font-medium text-on-surface-muted uppercase tracking-wider">
                    {t("study.tapToFlip")}
                  </span>
                </div>
              </div>

              {/* Back: Translation */}
              <div className="absolute inset-0 flex items-center justify-center [transform:rotateY(180deg)] [backface-visibility:hidden]">
                <div className="flex flex-col items-center px-7 py-8 text-center">
                  <span className="text-label-small font-medium text-on-surface-muted">
                    {t("library.fieldTranslation")}
                  </span>
                  <span className="mt-3 block text-3xl font-bold text-on-surface">
                    {currentItem.englishTranslation}
                  </span>
                  <div className="mt-4 flex flex-col gap-1">
                    {currentItem.article && currentItem.article !== "none" && (
                      <span className="text-sm font-semibold text-secondary">
                        {currentItem.article} {currentItem.germanText}
                      </span>
                    )}
                    {currentItem.plural && (
                      <span className="text-xs text-on-surface-muted">pl. {currentItem.plural}</span>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </button>

          {/* Standalone Speak button - NOT nested inside the flip button */}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              speak(currentItem.germanText);
            }}
            aria-label={t("action.speak")}
            className="press-scale absolute top-4 right-4 z-10 rounded-full p-2.5 text-azure-glow hover:bg-azure-glow/10 transition-colors focus-visible:outline-2 focus-visible:outline-azure-glow"
          >
            <VolumeUpIcon className="size-6" />
          </button>
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
