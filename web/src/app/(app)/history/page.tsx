"use client";

import { useHistory } from "@/hooks/useHistory";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { SearchInput } from "@/components/ui/GlassTextField";
import { DeleteIcon, HistoryIcon } from "@/components/icons";
import { t } from "@/lib/i18n";
import type { TranscriptEntry } from "@/lib/db/schema";
import type { Lang } from "@/lib/i18n";

/**
 * HistoryScreen — ui/screens/HistoryScreen.kt port: a live, searchable list of
 * past transcripts with per-row delete.
 */
export default function HistoryPage() {
  const { query, setQuery, transcripts, deleteTranscript } = useHistory();
  const { t: translate, lang } = useI18n();

  return (
    <div className="flex h-full min-h-0 flex-col px-6 py-4">
      <div className="pt-2">
        <SearchInput
          value={query}
          onChange={setQuery}
          placeholder={translate("history.searchHint")}
        />
      </div>

      <div className="mt-6 min-h-0 flex-1">
        {transcripts.length === 0 ? (
          <EmptyState
            icon={<HistoryIcon className="size-full" />}
            message={translate("history.emptyTitle")}
            description={translate("history.emptyBody")}
          />
        ) : (
          <ul className="flex h-full flex-col gap-3 overflow-y-auto pb-4">
            {transcripts.map((transcript) => (
              <HistoryRow
                key={transcript.id}
                transcript={transcript}
                lang={lang}
                onDelete={() => deleteTranscript(transcript)}
              />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function HistoryRow({
  transcript,
  lang,
  onDelete,
}: {
  transcript: TranscriptEntry;
  lang: Lang;
  onDelete: () => void;
}) {
  return (
    <li className="glass-surface p-5 shadow-md shadow-azure-glow/10 transition-shadow hover:shadow-lg hover:shadow-azure-glow/15">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          {/* Muted, not primary: the timestamp is the least important thing in
              the row and it used to be the loudest, in brand blue. */}
          <p className="text-xs font-medium text-on-surface-variant uppercase tracking-wider">
            {formatHistoryDate(transcript.timestamp, lang)}
          </p>
          <p className="mt-2 line-clamp-3 text-base text-on-surface leading-relaxed">{transcript.fullText}</p>
        </div>
        <button
          type="button"
          onClick={onDelete}
          aria-label={t("action.delete")}
          className="press-scale shrink-0 rounded-lg p-2.5 text-error/60 hover:text-error hover:bg-error/10 transition-all active:bg-error/20"
        >
          <DeleteIcon className="size-5" />
        </button>
      </div>
    </li>
  );
}

/**
 * "MMM dd, yyyy • HH:mm" in English, "dd. MMM yyyy • HH:mm" in German — the two
 * Android date formats, rendered through the locale the language chose.
 */
function formatHistoryDate(timestamp: number, lang: Lang): string {
  const locale = lang === "de" ? "de-DE" : "en-US";
  const date = new Intl.DateTimeFormat(locale, {
    month: "short",
    day: "2-digit",
    year: "numeric",
  }).format(timestamp);
  const time = new Intl.DateTimeFormat(locale, {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(timestamp);
  return `${date} • ${time}`;
}
