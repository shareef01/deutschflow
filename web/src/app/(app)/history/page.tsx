"use client";

import { useMemo, useRef, useState } from "react";
import { useHistory } from "@/hooks/useHistory";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { SearchInput } from "@/components/ui/GlassTextField";
import { Snackbar } from "@/components/ui/Snackbar";
import { DeleteIcon, HistoryIcon } from "@/components/icons";
import type { TranscriptEntry } from "@/lib/db/schema";
import type { Lang } from "@/lib/i18n";

/**
 * HistoryScreen — past sessions as learning objects, not database rows.
 *
 * Transcripts are grouped by calendar day ("Today", "Yesterday", then the
 * date), each row shows when it happened and how much was said, and deleting
 * confirms through a snackbar with Undo rather than an instant, irreversible
 * tap.
 */
export default function HistoryPage() {
  const { query, setQuery, transcripts, deleteTranscript, restoreTranscript } = useHistory();
  const { t, lang } = useI18n();

  const [snackbar, setSnackbar] = useState<{ message: string; undo?: () => void } | null>(
    null
  );
  const snackbarTimer = useRef<number | null>(null);

  const showSnackbar = (message: string, undo?: () => void) => {
    setSnackbar({ message, undo });
    if (snackbarTimer.current !== null) window.clearTimeout(snackbarTimer.current);
    snackbarTimer.current = window.setTimeout(() => setSnackbar(null), 4_000);
  };

  const groups = useMemo(() => groupByDay(transcripts), [transcripts]);

  return (
    <div className="mx-auto flex h-full min-h-0 w-full max-w-[var(--container-wide)] flex-col px-[var(--gutter)] py-[var(--space-5)]">
      <div className="max-w-[var(--container-reading)] pt-2">
        <SearchInput
          value={query}
          onChange={setQuery}
          placeholder={t("history.searchHint")}
        />
      </div>

      <div className="mt-6 min-h-0 flex-1 overflow-y-auto pb-4">
        {transcripts.length === 0 ? (
          <EmptyState
            icon={<HistoryIcon className="size-full" />}
            message={t("history.emptyTitle")}
            description={t("history.emptyBody")}
          />
        ) : (
          <div className="flex flex-col gap-3">
            {groups.map(([day, entries]) => (
              <div key={day.toISOString()} className="flex flex-col gap-3">
                <DayLabel day={day} lang={lang} />
                {/* One column until there is genuinely room for two. Widening
                    the page without splitting the list just stretched every
                    card to an unreadable measure. */}
                <div className="grid gap-[var(--space-3)] xl:grid-cols-2">
                {entries.map((transcript) => (
                  <HistoryRow
                    key={transcript.id}
                    transcript={transcript}
                    lang={lang}
                    onDelete={() => {
                      deleteTranscript(transcript);
                      showSnackbar(t("history.deleted"), () =>
                        restoreTranscript({
                          fullText: transcript.fullText,
                          timestamp: transcript.timestamp,
                        })
                      );
                    }}
                    t={t}
                  />
                ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <Snackbar
        message={snackbar?.message ?? null}
        action={snackbar?.undo ? { label: t("action.undo"), onClick: snackbar.undo } : undefined}
      />
    </div>
  );
}

/** Groups the newest-first list by calendar day, preserving order. */
function groupByDay(transcripts: TranscriptEntry[]): [Date, TranscriptEntry[]][] {
  const map = new Map<string, TranscriptEntry[]>();
  for (const entry of transcripts) {
    const day = new Date(entry.timestamp);
    day.setHours(0, 0, 0, 0);
    const key = day.toISOString();
    const list = map.get(key);
    if (list) list.push(entry);
    else map.set(key, [entry]);
  }
  return Array.from(map.entries()).map(([key, entries]) => [new Date(key), entries]);
}

function DayLabel({ day, lang }: { day: Date; lang: Lang }) {
  const { t } = useI18n();
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);

  const text =
    day.getTime() === today.getTime()
      ? t("history.today")
      : day.getTime() === yesterday.getTime()
        ? t("history.yesterday")
        : new Intl.DateTimeFormat(lang === "de" ? "de-DE" : "en-US", {
            month: "short",
            day: "numeric",
            year: "numeric",
          }).format(day);

  return (
    <h2 className="mt-3 pl-1 text-label-large text-on-surface-variant">{text}</h2>
  );
}

function HistoryRow({
  transcript,
  lang,
  onDelete,
  t,
}: {
  transcript: TranscriptEntry;
  lang: Lang;
  onDelete: () => void;
  t: ReturnType<typeof useI18n>["t"];
}) {
  const time = new Intl.DateTimeFormat(lang === "de" ? "de-DE" : "en-US", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(transcript.timestamp);
  const wordCount = transcript.fullText.split(/\s+/).filter((w) => w.length > 0).length;

  return (
    <div className="glass-surface p-5 shadow-md shadow-azure-glow/10 transition-shadow hover:shadow-lg hover:shadow-azure-glow/15">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <p lang="de" className="mt-2 line-clamp-3 hyphens-auto break-words text-base leading-relaxed text-on-surface">
            {transcript.fullText}
          </p>
          {/* When it happened and how much was said — metadata, quiet. */}
          <p className="mt-2 text-label-medium text-on-surface-variant">
            {time} · {t("history.words", [wordCount])}
          </p>
        </div>
        <button
          type="button"
          onClick={onDelete}
          aria-label={t("action.delete")}
          className="press-scale shrink-0 rounded-lg p-3 text-error/60 transition-all hover:bg-error/10 hover:text-error active:bg-error/20"
        >
          <DeleteIcon className="size-5" />
        </button>
      </div>
    </div>
  );
}
