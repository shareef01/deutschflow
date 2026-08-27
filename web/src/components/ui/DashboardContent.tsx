"use client";

import { useDashboard, type MasteryStats } from "@/hooks/useDashboard";
import { useI18n } from "@/hooks/useI18n";
import { todayKey } from "@/lib/db/repository";
import type { ActivityEntry } from "@/lib/db/schema";
import { useMemo } from "react";

/** The bound translator. Typed, so an unknown key is a compile error. */
type Translate = ReturnType<typeof useI18n>["t"];

export function DashboardContent() {
  const { userStats, activityLog, masteryStats, todayXp } = useDashboard();
  const { t } = useI18n();

  return (
    <div className="flex flex-col gap-8 p-6 overflow-y-auto h-full">
      {/* Daily Goal & Streak */}
      <DailyGoalCard xp={todayXp} streak={userStats?.streak ?? 0} t={t} />

      {/* Retention Analytics */}
      <MasteryBreakdownCard stats={masteryStats} t={t} />

      {/* Activity Heatmap */}
      <ActivityHeatmapCard logs={activityLog} t={t} />
    </div>
  );
}

function DailyGoalCard({ xp, streak, t }: { xp: number; streak: number; t: Translate }) {
  const goal = 50;
  const progress = Math.min(xp / goal, 1);
  const radius = 40;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - progress * circumference;

  return (
    <div className="glass-surface p-8 flex flex-col sm:flex-row items-center gap-8 shadow-xl shadow-azure-glow/5">
      <div className="relative size-32 flex items-center justify-center">
        <svg className="size-full -rotate-90">
          <circle
            cx="64"
            cy="64"
            r={radius}
            fill="transparent"
            stroke="currentColor"
            strokeWidth="8"
            className="text-white/5"
          />
          <circle
            cx="64"
            cy="64"
            r={radius}
            fill="transparent"
            stroke="url(#azure-gradient)"
            strokeWidth="10"
            strokeDasharray={circumference}
            style={{ strokeDashoffset: offset }}
            strokeLinecap="round"
            className="transition-all duration-1000 ease-out"
          />
          <defs>
            <linearGradient id="azure-gradient" x1="0%" y1="0%" x2="100%" y2="100%">
              {/* The palette's cyan, not a fourth one: #00E5FF was in neither theme. */}
              <stop offset="0%" stopColor="var(--color-azure-glow)" />
              <stop offset="100%" stopColor="var(--color-azure-deep)" />
            </linearGradient>
          </defs>
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-3xl font-black text-on-surface leading-none">{xp}</span>
          <span className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest mt-1">XP</span>
        </div>
      </div>

      <div className="flex-1 text-center sm:text-left space-y-2">
        <h3 className="text-xl font-black text-on-surface tracking-tight uppercase">
          {t("dashboard.dailyGoal")}
        </h3>
        <p className="text-sm text-on-surface-variant font-medium">
          {progress >= 1
            ? t("dashboard.goalAchieved")
            : t("dashboard.xpRemaining", [goal - xp])}
        </p>
        <div className="inline-flex mt-4 px-4 py-1.5 rounded-full bg-primary/10 border border-primary/20 items-center gap-2">
          <span className="text-sm font-black text-primary">
            {t("dashboard.streak", [streak])}
          </span>
        </div>
      </div>
    </div>
  );
}

function MasteryBreakdownCard({ stats, t }: { stats: MasteryStats; t: Translate }) {
  return (
    <div className="glass-surface p-8 space-y-6">
      <h3 className="text-sm font-black text-primary tracking-[0.2em] uppercase">
        {t("dashboard.retention")}
      </h3>
      <div className="grid grid-cols-1 xs:grid-cols-3 gap-4">
        <RetentionTile label={t("dashboard.mastered")} count={stats.masteredWords} color="text-green-400" />
        <RetentionTile label={t("dashboard.learning")} count={stats.learningWords} color="text-azure-glow" />
        <RetentionTile label={t("dashboard.new")} count={stats.newWords} color="text-on-surface-variant" />
      </div>
    </div>
  );
}

function RetentionTile({ label, count, color }: { label: string; count: number; color: string }) {
  return (
    <div className="bg-surface-variant/20 rounded-2xl p-5 border border-white/5">
      <span className={`text-3xl font-black ${color}`}>{count}</span>
      <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest mt-1">{label}</p>
    </div>
  );
}

function ActivityHeatmapCard({ logs, t }: { logs: ActivityEntry[]; t: Translate }) {
  const weeksToShow = 12;
  const daysToShow = weeksToShow * 7;
  const activityMap = useMemo(() => {
      const map = new Map<string, number>();
      logs.forEach(log => map.set(log.date, log.xpGained));
      return map;
  }, [logs]);

  const cells = useMemo(() => {
      const result = [];
      const today = new Date();
      for (let i = 0; i < daysToShow; i++) {
          const d = new Date(today);
          d.setDate(today.getDate() - (daysToShow - 1 - i));
          // todayKey, not toISOString: the map is keyed by local calendar dates —
          // the same key addActivityXp writes. A UTC key rolled evening XP onto
          // the wrong cell for every user outside UTC.
          const dateStr = todayKey(d);
          const xp = activityMap.get(dateStr) || 0;
          result.push({ date: dateStr, xp });
      }
      return result;
  }, [activityMap, daysToShow]);

  return (
    <div className="glass-surface p-8 space-y-6">
      <h3 className="text-sm font-black text-primary tracking-[0.2em] uppercase">
        {t("dashboard.heatmap")}
      </h3>

      <div className="grid grid-flow-col grid-rows-7 gap-1.5 h-32 w-fit">
          {cells.map((cell, i) => {
              const color = cell.xp >= 100 ? 'bg-green-400' :
                           cell.xp >= 50 ? 'bg-green-400/70' :
                           cell.xp >= 20 ? 'bg-green-400/40' :
                           cell.xp > 0 ? 'bg-green-400/20' : 'bg-white/5';
              return (
                  <div key={i} className={`size-3 rounded-[2px] ${color}`} title={`${cell.date}: ${cell.xp} XP`} />
              )
          })}
      </div>

      <p className="text-[10px] font-medium text-on-surface-variant uppercase tracking-widest opacity-50">
        {t("dashboard.heatmapSub")}
      </p>
    </div>
  );
}
