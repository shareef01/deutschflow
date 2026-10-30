"use client";

import Link from "next/link";
import { useDashboard, type MasteryStats } from "@/hooks/useDashboard";
import { useI18n } from "@/hooks/useI18n";
import { DAILY_XP_GOAL, currentStreak, todayKey } from "@/lib/db/repository";
import type { ActivityEntry } from "@/lib/db/schema";
import { useMemo } from "react";
import { GlassButton } from "./GlassButton";
import { PlayArrowIcon } from "@/components/icons";

/** The bound translator. Typed, so an unknown key is a compile error. */
type Translate = ReturnType<typeof useI18n>["t"];

/**
 * The Study dashboard, in the order of the questions it answers: what to do
 * today, how today is going, then how the library is doing over time. Every
 * figure is read from storage: the due count from the study session, XP and the
 * heatmap from the activity log, mastery on Android's 21-day interval.
 */
export function DashboardContent({
  onStartReview,
  dueCount = 0,
}: {
  onStartReview?: () => void;
  dueCount?: number;
}) {
  const { userStats, activityLog, masteryStats, todayXp } = useDashboard();
  const { t } = useI18n();

  return (
    // A scroll region that must be reachable by keyboard. It contains controls in
    // most states, but not in all of them (the first-run dashboard is text and
    // numbers only), and a scrollable box with nothing focusable inside cannot be
    // scrolled without a pointer — `axe` flags it as `scrollable-region-focusable`,
    // which became visible once the app shell gained a bounded height and this pane
    // started scrolling itself instead of growing the page.
    <div
      tabIndex={0}
      role="group"
      aria-label={t("dashboard.tab")}
      className="h-full overflow-y-auto focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-azure-glow"
    >
      <div className="mx-auto grid w-full max-w-[var(--container-workspace)] gap-[var(--space-4)] px-[var(--gutter)] py-[var(--space-5)] lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
        <TodayCard
          dueCount={dueCount}
          totalWords={masteryStats.totalWords}
          onStartReview={onStartReview}
          t={t}
        />
        <DailyGoalCard xp={todayXp} streak={currentStreak(userStats)} t={t} />

        <h2 className="eyebrow mt-[var(--space-4)] pl-1 lg:col-span-2">{t("dashboard.progress")}</h2>
        <MasteryBreakdownCard stats={masteryStats} t={t} />
        <ActivityHeatmapCard logs={activityLog} t={t} />
      </div>
    </div>
  );
}

/**
 * Three honest states rather than one card with a zero in it. An empty library
 * used to read "All caught up!" - true, and no help to someone who has not
 * started.
 */
function TodayCard({
  dueCount,
  totalWords,
  onStartReview,
  t,
}: {
  dueCount: number;
  totalWords: number;
  onStartReview?: () => void;
  t: Translate;
}) {
  const [title, body] =
    totalWords === 0
      ? [t("dashboard.noWords"), t("dashboard.noWordsBody")]
      : dueCount > 0
        ? [
            dueCount === 1
              ? t("dashboard.dueTodayBannerOne", [dueCount])
              : t("dashboard.dueTodayBanner", [dueCount]),
            t("dashboard.dueTodayBody"),
          ]
        : [t("dashboard.allCaughtUp"), t("dashboard.allCaughtUpBody")];

  return (
    <section aria-labelledby="dashboard-today" className="glass-surface flex flex-col p-5 sm:p-6">
      <p className="eyebrow">{t("dashboard.today")}</p>
      <h2 id="dashboard-today" className="mt-2 text-headline-small text-on-surface">{title}</h2>
      <p className="mt-1 max-w-prose text-body-medium text-on-surface-variant">{body}</p>
      <div className="mt-auto pt-5">
        {totalWords === 0 ? (
          <Link
            href="/transcript"
            className="glass-button edge-solid press-scale inline-flex h-12 items-center justify-center px-6 text-label-large font-bold"
          >
            {t("dashboard.goToTranscript")}
          </Link>
        ) : (
          onStartReview && (
            <GlassButton
              type="button"
              glow={dueCount > 0 ? "solid" : "deep"}
              onClick={onStartReview}
              className="w-full px-6 sm:w-auto"
            >
              <span className="flex items-center justify-center gap-2 text-label-large font-bold">
                {dueCount > 0 && <PlayArrowIcon className="size-4" />}
                {dueCount > 0 ? t("dashboard.startReview") : t("dashboard.practiceAnyway")}
              </span>
            </GlassButton>
          )
        )}
      </div>
    </section>
  );
}

function DailyGoalCard({ xp, streak, t }: { xp: number; streak: number; t: Translate }) {
  const goal = DAILY_XP_GOAL;
  const progress = Math.min(xp / goal, 1);
  const radius = 34;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - progress * circumference;

  return (
    <section aria-labelledby="dashboard-goal" className="glass-surface flex items-center gap-5 p-5 sm:p-6">
      <div
        role="progressbar"
        aria-valuenow={xp}
        aria-valuemin={0}
        aria-valuemax={goal}
        aria-label={t("dashboard.dailyGoal")}
        className="relative size-24 shrink-0"
      >
        <svg className="size-full -rotate-90" viewBox="0 0 80 80" aria-hidden="true">
          <circle cx="40" cy="40" r={radius} fill="none" strokeWidth="7" className="stroke-surface-container-highest" />
          <circle
            cx="40"
            cy="40"
            r={radius}
            fill="none"
            strokeWidth="7"
            strokeDasharray={circumference}
            style={{ strokeDashoffset: offset }}
            strokeLinecap="round"
            className="stroke-primary transition-[stroke-dashoffset] duration-700 ease-out"
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-title-large leading-none text-on-surface">{xp}</span>
          <span className="mt-0.5 text-label-small text-on-surface-variant">/ {goal} {t("dashboard.xp")}</span>
        </div>
      </div>

      <div className="min-w-0 flex-1">
        <h2 id="dashboard-goal" className="eyebrow">{t("dashboard.dailyGoal")}</h2>
        <p className="mt-1 text-title-medium text-on-surface">
          {progress >= 1 ? t("dashboard.goalAchieved") : t("dashboard.xpRemaining", [goal - xp])}
        </p>
        <p className="mt-3 inline-flex items-center rounded-full bg-warning-container px-3 py-1 text-label-medium text-on-warning-container">
          {t("dashboard.streak", [streak])}
        </p>
      </div>
    </section>
  );
}

function MasteryBreakdownCard({ stats, t }: { stats: MasteryStats; t: Translate }) {
  const total = stats.totalWords;
  // The palette's success colour, not Tailwind's green-400: that green was in
  // neither theme and measures 1.7:1 on a light ground.
  const segments = [
    { label: t("dashboard.mastered"), count: stats.masteredWords, swatch: "bg-tertiary" },
    { label: t("dashboard.learning"), count: stats.learningWords, swatch: "bg-azure-glow" },
    { label: t("dashboard.new"), count: stats.newWords, swatch: "bg-outline" },
  ];

  return (
    <section aria-labelledby="dashboard-retention" className="glass-surface p-5 sm:p-6">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h3 id="dashboard-retention" className="text-title-medium text-on-surface">{t("dashboard.retention")}</h3>
        <p className="text-label-medium text-on-surface-variant">{t("dashboard.wordsTotal", [total])}</p>
      </div>

      {/* The three buckets as one bar, so the split reads at a glance; the
          numbers below carry the same information for a screen reader. */}
      <div aria-hidden="true" className="mt-4 flex h-2.5 overflow-hidden rounded-full bg-surface-container-highest">
        {total > 0 &&
          segments.map((s) =>
            s.count > 0 ? <span key={s.label} className={s.swatch} style={{ width: `${(s.count / total) * 100}%` }} /> : null
          )}
      </div>

      <dl className="mt-4 grid grid-cols-3 gap-3">
        {segments.map((s) => (
          <div key={s.label} className="min-w-0">
            <dt className="flex items-center gap-1.5 text-label-medium text-on-surface-variant">
              <span aria-hidden="true" className={`size-2.5 shrink-0 rounded-full ${s.swatch}`} />
              <span className="truncate">{s.label}</span>
            </dt>
            <dd className="mt-1 text-headline-small text-on-surface">{s.count}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

const HEATMAP_LEVELS = ["bg-on-surface/8", "bg-tertiary/25", "bg-tertiary/45", "bg-tertiary/70", "bg-tertiary"];

function heatLevel(xp: number) {
  return xp >= 100 ? 4 : xp >= 50 ? 3 : xp >= 20 ? 2 : xp > 0 ? 1 : 0;
}

function ActivityHeatmapCard({ logs, t }: { logs: ActivityEntry[]; t: Translate }) {
  const weeksToShow = 12;
  const daysToShow = weeksToShow * 7;
  const activityMap = useMemo(() => {
    const map = new Map<string, number>();
    logs.forEach((log) => map.set(log.date, log.xpGained));
    return map;
  }, [logs]);

  const cells = useMemo(() => {
    const result = [];
    const today = new Date();
    for (let i = 0; i < daysToShow; i++) {
      const d = new Date(today);
      d.setDate(today.getDate() - (daysToShow - 1 - i));
      const dateStr = todayKey(d);
      const xp = activityMap.get(dateStr) || 0;
      result.push({ date: dateStr, xp });
    }
    return result;
  }, [activityMap, daysToShow]);

  const activeDays = useMemo(() => cells.filter((c) => c.xp > 0).length, [cells]);

  return (
    <section aria-labelledby="dashboard-heatmap" className="glass-surface p-5 sm:p-6">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h3 id="dashboard-heatmap" className="text-title-medium text-on-surface">{t("dashboard.heatmap")}</h3>
        <p className="text-label-medium text-on-surface-variant">{t("dashboard.heatmapSub")}</p>
      </div>

      <div className="mt-4 overflow-x-auto pb-1">
        <div
          role="img"
          aria-label={`${t("dashboard.heatmap")}: ${activeDays} / ${daysToShow} ${t("streak.days", [daysToShow])}`}
          className="grid w-fit grid-flow-col grid-rows-7 gap-1"
        >
          {cells.map((cell) => (
            <div
              key={cell.date}
              className={`size-3.5 rounded-[3px] ${HEATMAP_LEVELS[heatLevel(cell.xp)]}`}
              title={`${cell.date}: ${cell.xp} XP`}
            />
          ))}
        </div>
      </div>

      <div aria-hidden="true" className="mt-3 flex items-center gap-1.5 text-label-small text-on-surface-variant">
        <span className="mr-1">{t("dashboard.heatmapLess")}</span>
        {HEATMAP_LEVELS.map((level) => (
          <span key={level} className={`size-3 rounded-[3px] ${level}`} />
        ))}
        <span className="ml-1">{t("dashboard.heatmapMore")}</span>
      </div>
    </section>
  );
}
