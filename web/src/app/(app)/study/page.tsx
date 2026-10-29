"use client";

import { useEffect, useRef, useState } from "react";
import { useStudy } from "@/hooks/useStudy";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { GlassButton } from "@/components/ui/GlassButton";
import { SegmentedTabs } from "@/components/ui/SegmentedTabs";
import { CheckIcon, RefreshIcon, SchoolIcon, VolumeUpIcon } from "@/components/icons";
import { ReviewQuality, calculateNextReview, formatSrsInterval } from "@/lib/ai/srs";
import { shouldIgnoreShortcut } from "@/lib/a11y/keyboard";
import { DashboardContent } from "@/components/ui/DashboardContent";

type TabType = "dashboard" | "flashcards";

export default function StudyPage() {
  const study = useStudy();
  const [selectedTab, setSelectedTab] = useState<TabType>("dashboard");
  const [hasUserSelectedTab, setHasUserSelectedTab] = useState(false);
  const { t } = useI18n();

  // Picked once, when the session first loads. dueCount now falls as cards are
  // rated, so re-deciding on every change would yank the user off the
  // completion screen onto the dashboard the moment the last card is rated.
  const hasAutoSelectedTab = useRef(false);
  useEffect(() => {
    if (!hasUserSelectedTab && !hasAutoSelectedTab.current && study.status === "ready") {
      hasAutoSelectedTab.current = true;
      setSelectedTab(study.dueCount > 0 ? "flashcards" : "dashboard");
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
          {selectedTab === "dashboard" && (
            <DashboardContent
              dueCount={study.dueCount}
              onStartReview={() => handleTabSelect("flashcards")}
            />
          )}
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

function ArticleBadge({ article }: { article?: string }) {
  if (!article || article === "none") return null;
  const lower = article.toLowerCase();
  const colorClasses =
    lower === "der"
      ? "bg-primary/20 text-primary-light border-primary/30"
      : lower === "die"
      ? "bg-rose-500/20 text-rose-300 border-rose-500/30"
      : lower === "das"
      ? "bg-tertiary/20 text-tertiary border-tertiary/30"
      : "bg-surface-variant text-on-surface-variant border-outline-variant";

  return (
    <span
      className={`inline-flex items-center rounded-md px-2.5 py-0.5 text-xs font-bold uppercase tracking-wider border ${colorClasses}`}
    >
      {article}
    </span>
  );
}

function FlashcardMode({
  study,
  onNavigateToDashboard,
}: {
  study: ReturnType<typeof useStudy>;
  onNavigateToDashboard: () => void;
}) {
  const { t, lang } = useI18n();
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
    sessionReviewedCount,
    initialSessionSize,
    canGrade,
  } = study;

  const safeIndex = Math.min(Math.max(currentIndex, 0), Math.max(studyList.length - 1, 0));
  const currentItem = studyList[safeIndex];

  useEffect(() => {
    if (currentItem) autoPlay(currentItem.germanText);
  }, [currentIndex, currentItem?.id, autoPlay, currentItem]);

  /**
   * Keyboard shortcuts for desktop studying.
   *
   * Two things this has to get right, and did not:
   *
   * 1. **It defers to whatever already has focus.** The handler used to skip only
   *    text fields, so Space with the "Again" button focused flipped the card
   *    *instead of* grading it (the `preventDefault` suppressed the button's own
   *    activation), and "3" graded a card from anywhere on the page — including
   *    while a control the user had deliberately focused was waiting for input.
   *    `shouldIgnoreShortcut` encodes the two-tier rule: typing keys stand down in
   *    any text field, activation keys additionally stand down on any control that
   *    already handles them.
   * 2. **Grading keys need a revealed answer.** `canGrade` is the same flag the
   *    buttons use, so the keyboard and the pointer cannot disagree.
   */
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (shouldIgnoreShortcut(e)) return;

      if (
        e.code === "Space" ||
        e.key === "Enter" ||
        e.key === "ArrowUp" ||
        e.key === "ArrowDown"
      ) {
        e.preventDefault();
        flipCard();
      } else if (canGrade && e.key === "1") {
        e.preventDefault();
        void submitReview(ReviewQuality.AGAIN);
      } else if (canGrade && e.key === "2") {
        e.preventDefault();
        void submitReview(ReviewQuality.HARD);
      } else if (e.key === "3") {
        e.preventDefault();
        void submitReview(ReviewQuality.GOOD);
      } else if (e.key === "4") {
        e.preventDefault();
        void submitReview(ReviewQuality.EASY);
      } else if (e.key === "s" || e.key === "S") {
        e.preventDefault();
        skipCard();
      } else if ((e.key === "r" || e.key === "R") && currentItem) {
        e.preventDefault();
        speak(currentItem.germanText);
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [flipCard, submitReview, skipCard, speak, currentItem, canGrade]);

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
        <p className="text-body-large text-error">
          {loadError ? t(loadError) : t("study.loadError")}
        </p>
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
          <div className="relative flex size-20 items-center justify-center rounded-full bg-primary/20 text-primary">
            <div className="absolute inset-0 rounded-full border border-primary/40 animate-ping opacity-25" />
            <CheckIcon className="size-10" />
          </div>
          <h2 className="mt-6 text-2xl font-bold text-on-surface">
            {t("study.completedTitle")}
          </h2>
          <p className="mt-2 text-body-medium text-on-surface-variant">
            {t("study.completedBody")}
          </p>

          <div className="mt-5 flex items-center justify-center gap-2 rounded-xl bg-surface-container-high/60 px-4 py-2 border border-outline-variant/30 text-sm font-semibold text-azure-glow">
            <CheckIcon className="size-4 text-primary" />
            <span>{t("study.reviewedStat", [sessionReviewedCount || totalWords])}</span>
          </div>

          <GlassButton
            type="button"
            onClick={onNavigateToDashboard}
            className="mt-7 h-12 w-full"
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

  const totalInSession = Math.max(initialSessionSize, sessionReviewedCount + studyList.length);
  const currentCardNumber = Math.min(sessionReviewedCount + 1, totalInSession);
  const progressPercent =
    totalInSession > 0
      ? Math.min(100, Math.round((sessionReviewedCount / totalInSession) * 100))
      : 0;

  const againInterval = formatSrsInterval(
    calculateNextReview(currentItem, ReviewQuality.AGAIN).interval,
    lang
  );
  const hardInterval = formatSrsInterval(
    calculateNextReview(currentItem, ReviewQuality.HARD).interval,
    lang
  );
  const goodInterval = formatSrsInterval(
    calculateNextReview(currentItem, ReviewQuality.GOOD).interval,
    lang
  );
  const easyInterval = formatSrsInterval(
    calculateNextReview(currentItem, ReviewQuality.EASY).interval,
    lang
  );

  return (
    <div className="min-h-0 h-full overflow-y-auto">
      <div className="mx-auto flex min-h-full flex-col items-center justify-center px-6 py-8">
      <ErrorBanner message={reviewError ? t(reviewError) : ttsError} />

      {/* Progress & Context Header */}
      <div className="w-full max-w-2xl space-y-2.5">
        {isExtraPractice && (
          <p className="px-1 text-center text-xs font-medium text-on-surface-variant">
            {t("study.extraPractice")}
          </p>
        )}
        <div className="flex items-center justify-between text-sm">
          <div className="flex items-center gap-2">
            <h2 className="text-title-medium font-bold text-on-surface">
              {t("study.session")}
            </h2>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold text-on-surface-variant">
              {t("study.progressCounter", [currentCardNumber, totalInSession])}
            </span>
            <span className="rounded-md bg-surface-container-high px-2 py-0.5 text-xs font-semibold text-primary">
              {t("study.remaining", [studyList.length])}
            </span>
          </div>
        </div>
        {/* Animated Progress Bar */}
        <div className="w-full bg-surface-variant/40 h-1.5 rounded-full overflow-hidden border border-outline-variant/30">
          <div
            className="h-full azure-gradient transition-all duration-300 ease-out rounded-full"
            style={{ width: `${progressPercent}%` }}
          />
        </div>
      </div>

      {/* 3D Flip Card Container */}
      <div className="flex w-full flex-1 items-center justify-center py-4">
        <div className="relative w-full max-w-2xl min-h-[280px]">
          {/* Main accessible flip card button */}
          <button
            type="button"
            onClick={flipCard}
            aria-expanded={isFlipped}
            aria-label={isFlipped ? t("study.showGerman") : t("study.showTranslation")}
            className="glass-surface relative block w-full min-h-[280px] max-h-[460px] [perspective:1200px] cursor-pointer text-left focus-visible:outline-2 focus-visible:outline-azure-glow shadow-xl shadow-azure-glow/10 hover:shadow-2xl hover:shadow-azure-glow/20 transition-all select-none rounded-2xl motion-reduce:transition-none border border-azure-glow/20 hover:border-azure-glow/40"
          >
            <div
              className={`relative h-full w-full min-h-[280px] transition-transform duration-500 [transform-style:preserve-3d] motion-reduce:transition-none ${
                isFlipped ? "[transform:rotateY(180deg)]" : ""
              }`}
            >
              {/* Front: German Word */}
              <div className="absolute inset-0 flex items-center justify-center [backface-visibility:hidden]">
                <div className="flex flex-col items-center px-7 py-8 text-center">
                  <ArticleBadge article={currentItem.article} />
                  <span className="mt-3 block text-3xl sm:text-4xl font-extrabold tracking-tight text-azure-glow drop-shadow-sm">
                    {currentItem.germanText}
                  </span>
                  <span className="mt-8 text-xs font-medium text-on-surface-muted uppercase tracking-wider flex items-center gap-1.5">
                    <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] rounded bg-surface-variant border border-outline-variant/40 font-mono">
                      Space
                    </kbd>
                    {t("study.tapToFlip")}
                  </span>
                </div>
              </div>

              {/* Back: Translation & Details */}
              <div className="absolute inset-0 flex items-center justify-center [transform:rotateY(180deg)] [backface-visibility:hidden]">
                <div className="flex flex-col items-center px-7 py-8 text-center">
                  <span className="text-label-small font-medium text-on-surface-muted uppercase tracking-wider">
                    {t("library.fieldTranslation")}
                  </span>
                  <span className="mt-2 block text-2xl sm:text-3xl font-bold text-on-surface">
                    {currentItem.englishTranslation}
                  </span>

                  {/* German reference reminder on answer flip. The article is the badge below. */}
                  <span className="mt-1.5 text-sm font-semibold text-azure-glow">
                    {currentItem.germanText}
                  </span>

                  <div className="mt-3 flex items-center gap-2">
                    {currentItem.article && currentItem.article !== "none" && (
                      <ArticleBadge article={currentItem.article} />
                    )}
                    {currentItem.plural && (
                      <span className="text-xs text-on-surface-muted bg-surface-container px-2 py-0.5 rounded border border-outline-variant/30">
                        pl. {currentItem.plural}
                      </span>
                    )}
                  </div>

                  {currentItem.exampleSentence && (
                    <div className="mt-4 max-w-md rounded-xl bg-surface-container/60 px-4 py-2.5 text-center border border-outline-variant/30">
                      <p className="text-sm italic text-on-surface-variant">
                        „{currentItem.exampleSentence}“
                      </p>
                    </div>
                  )}

                  <span className="mt-6 text-xs font-medium text-on-surface-muted uppercase tracking-wider flex items-center gap-1.5">
                    <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] rounded bg-surface-variant border border-outline-variant/40 font-mono">
                      Space
                    </kbd>
                    {t("study.showGerman")}
                  </span>
                </div>
              </div>
            </div>
          </button>

          {/* Standalone Speak button */}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              speak(currentItem.germanText);
            }}
            aria-label={t("action.speak")}
            className="press-scale absolute top-4 right-4 z-10 rounded-full p-2.5 text-azure-glow hover:bg-azure-glow/10 transition-colors focus-visible:outline-2 focus-visible:outline-azure-glow border border-transparent hover:border-azure-glow/20"
          >
            <VolumeUpIcon className="size-6" />
          </button>
        </div>
      </div>

      {/* The grading row. Disabled until the answer is showing — a grade is a
          statement about recall, and there is nothing to recall before the reveal.
          The hint says why, for anyone who cannot see that the buttons are dimmed. */}
      <p
        id="study-grade-hint"
        className="mt-4 text-center text-xs font-medium text-on-surface-variant"
      >
        {canGrade ? t("study.shortcutHint") : t("study.revealToGrade")}
      </p>

      {/* 4-Tier SRS Feedback Buttons with Interval Previews and Keyboard Shortcuts */}
      <div className="mt-2 grid w-full max-w-2xl grid-cols-2 gap-3 sm:grid-cols-4">
        <GlassButton
          type="button"
          glow="error"
          onClick={() => submitReview(ReviewQuality.AGAIN)}
          disabled={!canGrade}
          aria-describedby="study-grade-hint"
          className="w-full min-h-[3.75rem] py-2 flex flex-col items-center justify-center gap-0.5"
        >
          <span className="text-sm font-bold uppercase tracking-wider flex items-center gap-1">
            {t("study.again")}
            <kbd className="hidden sm:inline-block opacity-60 text-[10px] font-mono">
              [1]
            </kbd>
          </span>
          <span className="text-xs font-medium text-error opacity-90">
            {againInterval}
          </span>
        </GlassButton>

        <GlassButton
          type="button"
          glow="amber"
          onClick={() => submitReview(ReviewQuality.HARD)}
          disabled={!canGrade}
          aria-describedby="study-grade-hint"
          className="w-full min-h-[3.75rem] py-2 flex flex-col items-center justify-center gap-0.5"
        >
          <span className="text-sm font-bold uppercase tracking-wider flex items-center gap-1">
            {t("study.hard")}
            <kbd className="hidden sm:inline-block opacity-60 text-[10px] font-mono">
              [2]
            </kbd>
          </span>
          <span className="text-xs font-medium text-warning opacity-90">
            {hardInterval}
          </span>
        </GlassButton>

        <GlassButton
          type="button"
          onClick={() => submitReview(ReviewQuality.GOOD)}
          disabled={!canGrade}
          aria-describedby="study-grade-hint"
          className="w-full min-h-[3.75rem] py-2 flex flex-col items-center justify-center gap-0.5"
        >
          <span className="text-sm font-bold uppercase tracking-wider flex items-center gap-1">
            {t("study.good")}
            <kbd className="hidden sm:inline-block opacity-60 text-[10px] font-mono">
              [3]
            </kbd>
          </span>
          <span className="text-xs font-medium text-primary-light opacity-90">
            {goodInterval}
          </span>
        </GlassButton>

        <GlassButton
          type="button"
          glow="green"
          onClick={() => submitReview(ReviewQuality.EASY)}
          disabled={!canGrade}
          aria-describedby="study-grade-hint"
          className="w-full min-h-[3.75rem] py-2 flex flex-col items-center justify-center gap-0.5"
        >
          <span className="text-sm font-bold uppercase tracking-wider flex items-center gap-1">
            {t("study.easy")}
            <kbd className="hidden sm:inline-block opacity-60 text-[10px] font-mono">
              [4]
            </kbd>
          </span>
          <span className="text-xs font-medium text-tertiary opacity-90">
            {easyInterval}
          </span>
        </GlassButton>
      </div>

      <div className="mt-5 flex items-center gap-4">
        <button
          type="button"
          onClick={skipCard}
          className="text-sm font-medium text-on-surface-muted hover:text-on-surface transition-colors flex items-center gap-1.5"
        >
          <span>{t("study.skip")}</span>
          <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] rounded bg-surface-variant border border-outline-variant/40 font-mono">
            S
          </kbd>
        </button>
      </div>
      </div>
    </div>
  );
}
