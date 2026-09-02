"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useVocabulary } from "@/hooks/useVocabulary";
import { useHasSplitView } from "@/hooks/useViewport";
import { useBackHandler } from "@/hooks/useBackHandler";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { SearchInput, GlassTextField } from "@/components/ui/GlassTextField";
import { GlassButton } from "@/components/ui/GlassButton";
import { ModalDialog } from "@/components/ui/ModalDialog";
import { Snackbar } from "@/components/ui/Snackbar";
import {
  AddIcon,
  AutoStoriesIcon,
  DeleteIcon,
  EditIcon,
  InfoIcon,
  MoreVertIcon,
  PlayArrowIcon,
} from "@/components/icons";
import type { TFunction } from "@/lib/i18n";
import type { VocabularyEntry } from "@/lib/db/schema";

export default function VocabularyPage() {
  const {
    list,
    allVocabulary,
    searchQuery,
    setSearchQuery,
    ttsError,
    error,
    addVocabulary,
    deleteVocabulary,
    restoreVocabulary,
    updateVocabulary,
    exampleFor,
    speak,
  } = useVocabulary();

  const isDesktop = useHasSplitView();
  const isLibraryEmpty = allVocabulary.length === 0;
  const { t } = useI18n();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [isAdding, setIsAdding] = useState(false);
  const [sortMode, setSortMode] = useState<"newest" | "alpha">("newest");

  /**
   * Undo rather than a confirmation dialog: a confirmation taxes every deletion to
   * protect the rare mistaken one, where Undo costs nothing until it is needed.
   * The same pattern History already uses for transcripts.
   */
  const [deleted, setDeleted] = useState<VocabularyEntry | null>(null);
  const undoTimer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (undoTimer.current !== null) window.clearTimeout(undoTimer.current);
    },
    []
  );

  const onDeleteWithUndo = (item: VocabularyEntry) => {
    deleteVocabulary(item);
    // Closing the detail pane too, if the deleted word was the one open in it.
    if (selectedId === item.id) setSelectedId(null);
    setDeleted(item);
    if (undoTimer.current !== null) window.clearTimeout(undoTimer.current);
    undoTimer.current = window.setTimeout(() => setDeleted(null), 6_000);
  };

  const onUndoDelete = () => {
    if (deleted) restoreVocabulary(deleted);
    if (undoTimer.current !== null) window.clearTimeout(undoTimer.current);
    setDeleted(null);
  };

  const sortedList = useMemo(() => {
    if (sortMode === "alpha") {
      return [...list].sort((a, b) => a.germanText.localeCompare(b.germanText, "de"));
    }
    return list;
  }, [list, sortMode]);

  const selectedItem = sortedList.find((item) => item.id === selectedId) ?? null;
  const editingItem = list.find((item) => item.id === editingId) ?? null;

  const exampleSentence = useMemo(() => {
    if (!selectedItem) return "";
    return selectedItem.exampleSentence || exampleFor(selectedItem.germanText);
  }, [selectedItem?.germanText, selectedItem?.exampleSentence, exampleFor]);

  useBackHandler(!isDesktop && selectedItem != null, () => setSelectedId(null));

  const listProps = {
    searchQuery,
    onSearchChange: setSearchQuery,
    vocabularyList: sortedList,
    statsList: allVocabulary,
    sortMode,
    onSortChange: setSortMode,
    onItemClick: (item: VocabularyEntry) => setSelectedId(item.id ?? null),
    onEdit: (item: VocabularyEntry) => setEditingId(item.id ?? null),
    onDelete: onDeleteWithUndo,
    onSpeak: speak,
    onAdd: () => setIsAdding(true),
    t,
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* A failed write outranks a failed voice: one means the library did not
          change, the other means a word was not read aloud. */}
      <ErrorBanner message={error ? t(error) : ttsError} />

      {isDesktop && !isLibraryEmpty ? (
        <div className="grid min-h-0 flex-1 grid-cols-[minmax(22rem,0.9fr)_1px_minmax(0,1.1fr)]">
          <div className="min-w-0">
            <VocabularyListContent {...listProps} />
          </div>
          <div className="self-stretch bg-[rgba(0,229,255,0.15)]" />
          <div className="min-w-0">
            <VocabularyDetail
              item={selectedItem}
              exampleSentence={exampleSentence}
              onClose={() => setSelectedId(null)}
              onSpeak={speak}
              t={t}
            />
          </div>
        </div>
      ) : isLibraryEmpty ? (
        <VocabularyListContent {...listProps} />
      ) : selectedItem ? (
        <VocabularyDetail
          item={selectedItem}
          exampleSentence={exampleSentence}
          onClose={() => setSelectedId(null)}
          onSpeak={speak}
          t={t}
        />
      ) : (
        <VocabularyListContent {...listProps} />
      )}

      {editingItem && (
        <VocabularyEditorDialog
          title={t("library.dialogEditTitle")}
          confirmLabel={t("library.dialogEditConfirm")}
          initialGerman={editingItem.germanText}
          initialEnglish={editingItem.englishTranslation}
          onDismiss={() => setEditingId(null)}
          onSave={(german, english) => {
            updateVocabulary({ ...editingItem, germanText: german, englishTranslation: english });
            setEditingId(null);
          }}
          t={t}
        />
      )}

      {isAdding && (
        <VocabularyEditorDialog
          title={t("library.dialogAddTitle")}
          confirmLabel={t("library.dialogAddConfirm")}
          initialGerman=""
          initialEnglish=""
          onDismiss={() => setIsAdding(false)}
          onSave={(german, english) => {
            addVocabulary(german, english);
            setIsAdding(false);
          }}
          t={t}
        />
      )}

      <Snackbar
        message={deleted ? t("library.wordDeleted") : null}
        action={{ label: t("action.undo"), onClick: onUndoDelete }}
      />
    </div>
  );
}

function VocabularyListContent({
  searchQuery,
  onSearchChange,
  vocabularyList,
  statsList,
  sortMode,
  onSortChange,
  onItemClick,
  onEdit,
  onDelete,
  onSpeak,
  onAdd,
  t,
}: {
  searchQuery: string;
  onSearchChange: (value: string) => void;
  vocabularyList: VocabularyEntry[];
  statsList: VocabularyEntry[];
  sortMode: "newest" | "alpha";
  onSortChange: (mode: "newest" | "alpha") => void;
  onItemClick: (item: VocabularyEntry) => void;
  onEdit: (item: VocabularyEntry) => void;
  onDelete: (item: VocabularyEntry) => void;
  onSpeak: (text: string) => void;
  onAdd: () => void;
  t: TFunction;
}) {
  const words = statsList.length;
  const phrases = statsList.filter((item) => item.germanText.trim().includes(" ")).length;
  const withExample = statsList.filter((item) => item.exampleSentence.length > 0).length;

  return (
    <div className="relative flex h-full min-h-0 flex-col px-5 py-4">
      <div className="pt-2">
        <SearchInput
          value={searchQuery}
          onChange={onSearchChange}
          placeholder={t("library.searchHint")}
        />
      </div>

      <div className="glass-surface mt-3 grid grid-cols-3 px-4 py-2.5">
        <StatCell value={String(words)} label={t("library.statWords")} />
        <StatCell value={String(phrases)} label={t("library.statPhrases")} />
        <StatCell value={String(withExample)} label={t("library.statExamples")} />
      </div>

      <div className="mt-3 flex gap-2">
        {(
          [
            ["newest", t("library.sortNewest")],
            ["alpha", t("library.sortAlphabetical")],
          ] as const
        ).map(([mode, label]) => (
          <button
            key={mode}
            type="button"
            onClick={() => onSortChange(mode)}
            className={`press-scale flex min-h-11 items-center rounded-full border px-4 text-label-medium ${
              sortMode === mode
                ? "border-azure-glow/60 bg-secondary-container/60 text-on-secondary-container"
                : "border-outline-variant bg-glass-fill text-on-surface-variant"
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="mt-3 min-h-0 flex-1">
        {vocabularyList.length === 0 ? (
          <EmptyState
            icon={<AutoStoriesIcon className="size-full" />}
            message={t("library.emptyTitle")}
            description={t("library.emptyBody")}
          />
        ) : (
          <ul className="flex h-full flex-col gap-3 overflow-y-auto pb-24">
            {vocabularyList.map((item) => (
              <VocabularyItem
                key={item.id}
                item={item}
                onOpen={() => onItemClick(item)}
                onEdit={() => onEdit(item)}
                onDelete={() => onDelete(item)}
                onSpeak={() => onSpeak(item.germanText)}
                t={t}
              />
            ))}
          </ul>
        )}
      </div>

      <button
        type="button"
        onClick={onAdd}
        aria-label={t("library.addWord")}
        className="glass-button press-scale absolute bottom-6 right-6 flex size-14 items-center justify-center text-on-surface"
      >
        <AddIcon className="size-6" />
      </button>
    </div>
  );
}

function VocabularyItem({
  item,
  onOpen,
  onEdit,
  onDelete,
  onSpeak,
  t,
}: {
  item: VocabularyEntry;
  onOpen: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onSpeak: () => void;
  t: TFunction;
}) {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    // `list-row` carries content-visibility, which applies paint containment
    // whether or not the row is on screen - so it clipped this row's own overflow
    // menu (Delete fell outside the ~80px row) and trapped the menu's
    // `fixed inset-0` dismiss backdrop inside it. Containment lifts for the one
    // row whose menu is open; the other several hundred keep the saving.
    <li className={`glass-surface ${menuOpen ? "" : "list-row"}`}>
      <div className="flex items-start gap-1 p-2 pl-4">
        <button type="button" onClick={onOpen} className="min-w-0 flex-1 py-2 pr-2 text-left">
          <p lang="de" className="line-clamp-3 hyphens-auto break-words text-title-medium text-primary">{item.germanText}</p>
          <p className="mt-1 line-clamp-2 text-body-medium text-on-surface-variant">
            {item.englishTranslation}
          </p>
        </button>

        <button
          type="button"
          onClick={onSpeak}
          aria-label={t("action.speak")}
          className="press-scale shrink-0 rounded-full p-3 text-primary"
        >
          <PlayArrowIcon className="size-5" />
        </button>

        <div className="relative shrink-0">
          <button
            type="button"
            onClick={() => setMenuOpen(true)}
            aria-label={t("library.moreActions")}
            className="press-scale rounded-full p-3 text-on-surface-variant"
          >
            <MoreVertIcon className="size-5" />
          </button>

          {menuOpen && (
            <>
              <button
                type="button"
                aria-label={t("action.cancel")}
                className="fixed inset-0 z-40 cursor-default"
                onClick={() => setMenuOpen(false)}
              />
              <div className="glass-surface absolute right-0 top-12 z-50 w-40 p-1">
                <button
                  type="button"
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-body-medium text-on-surface hover:bg-on-surface/5"
                  onClick={() => {
                    setMenuOpen(false);
                    onEdit();
                  }}
                >
                  <EditIcon className="size-4.5" />
                  {t("action.edit")}
                </button>
                <button
                  type="button"
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-body-medium text-error hover:bg-on-surface/5"
                  onClick={() => {
                    setMenuOpen(false);
                    onDelete();
                  }}
                >
                  <DeleteIcon className="size-4.5" />
                  {t("action.delete")}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </li>
  );
}

function VocabularyDetail({
  item,
  exampleSentence,
  onClose,
  onSpeak,
  t,
}: {
  item: VocabularyEntry | null;
  exampleSentence: string;
  onClose: () => void;
  onSpeak: (text: string) => void;
  t: TFunction;
}) {
  if (!item) {
    return (
      <EmptyState
        icon={<InfoIcon className="size-full" />}
        message={t("detail.emptyTitle")}
        description={t("detail.emptyBody")}
      />
    );
  }

  const grammar = [item.article, item.plural, item.conjugation]
    .filter((f) => f && f !== "none")
    .join("  ·  ");

  return (
    <div className="flex h-full min-h-0 flex-col overflow-y-auto p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h2 lang="de" className="hyphens-auto break-words text-headline-medium text-primary">{item.germanText}</h2>
          {grammar && (
            <p className="mt-1 text-label-large text-on-surface-variant">{grammar}</p>
          )}
          <p className="mt-1 text-title-medium text-on-surface-variant">{item.englishTranslation}</p>
        </div>

        <button
          type="button"
          onClick={() => onSpeak(item.germanText)}
          aria-label={t("action.speak")}
          className="glass-button press-scale flex size-14 shrink-0 items-center justify-center text-primary"
        >
          <PlayArrowIcon className="size-7" />
        </button>
      </div>

      <div className="mt-8">
        <h3 className="text-label-small font-bold text-primary uppercase tracking-wider">Linguistic Connections</h3>
        <div className="mt-2 mb-4 h-px bg-surface-variant" />

        <div className="grid grid-cols-2 gap-4">
            <LinguisticBox title="SYNONYMS" content={item.synonyms} />
            <LinguisticBox title="ANTONYMS" content={item.antonyms} />
        </div>
      </div>

      <div className="mt-8">
        <h3 className="text-label-large font-bold text-primary">{t("detail.context")}</h3>
        <div className="mt-2 mb-2 h-px bg-surface-variant" />

        <div className="glass-surface p-6">
          <p className="text-label-medium font-bold text-primary">{t("detail.example")}</p>
          <p className="mt-2 text-body-large text-on-surface">{exampleSentence}</p>
        </div>
      </div>

      <div className="flex-1" />

      <GlassButton type="button" onClick={onClose} className="mt-6 w-full">
        <span className="text-label-large font-bold uppercase">{t("detail.back")}</span>
      </GlassButton>
    </div>
  );
}

function LinguisticBox({ title, content }: { title: string; content: string }) {
    return (
        <div className="glass-surface p-4 flex flex-col gap-1">
            <span className="text-[10px] font-bold text-primary tracking-widest">{title}</span>
            <p className={`text-sm ${content ? 'text-on-surface' : 'text-on-surface-variant opacity-50'}`}>
                {content || "—"}
            </p>
        </div>
    )
}

function VocabularyEditorDialog({
  title,
  confirmLabel,
  initialGerman,
  initialEnglish,
  onDismiss,
  onSave,
  t,
}: {
  title: string;
  confirmLabel: string;
  initialGerman: string;
  initialEnglish: string;
  onDismiss: () => void;
  onSave: (german: string, english: string) => void;
  t: TFunction;
}) {
  const [germanText, setGermanText] = useState(initialGerman);
  const [translation, setTranslation] = useState(initialEnglish);

  const isValid = germanText.trim().length > 0 && translation.trim().length > 0;

  return (
    <ModalDialog
      title={title}
      onDismiss={onDismiss}
      actions={
        <>
          <GlassButton
            type="button"
            onClick={onDismiss}
            className="px-4 text-label-large font-bold"
          >
            {t("action.cancel")}
          </GlassButton>
          <GlassButton
            type="button"
            disabled={!isValid}
            onClick={() => onSave(germanText.trim(), translation.trim())}
            className="px-4 text-label-large font-bold"
          >
            {confirmLabel}
          </GlassButton>
        </>
      }
    >
      <GlassTextField
        label={t("library.fieldGerman")}
        value={germanText}
        onChange={(event) => setGermanText(event.target.value)}
        placeholder="das Wort"
        autoFocus
      />
      <GlassTextField
        label={t("library.fieldTranslation")}
        value={translation}
        onChange={(event) => setTranslation(event.target.value)}
        placeholder="the word"
      />
    </ModalDialog>
  );
}

function StatCell({ value, label }: { value: string; label: string }) {
  return (
    <div className="flex flex-col items-center">
      <p className="text-title-medium text-on-surface">{value}</p>
      <p className="max-w-full truncate text-label-small text-on-surface-variant">
        {label}
      </p>
    </div>
  );
}
