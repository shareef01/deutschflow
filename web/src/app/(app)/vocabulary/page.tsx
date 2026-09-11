"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
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
  }, [selectedItem, exampleFor]);

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
          <div className="self-stretch bg-outline-variant/30" />
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
          onSave={async (german, english) => {
            const ok = await updateVocabulary({ ...editingItem, germanText: german, englishTranslation: english });
            if (ok) setEditingId(null);
            return ok;
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
          onSave={async (german, english) => {
            const ok = await addVocabulary(german, english);
            if (ok) setIsAdding(false);
            return ok;
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

      <fieldset className="mt-3 border-none p-0 m-0">
        <legend className="sr-only">{t("library.sortBy")}</legend>
        <div className="flex gap-2">
          {(
            [
              ["newest", t("library.sortNewest")],
              ["alpha", t("library.sortAlphabetical")],
            ] as const
          ).map(([mode, label]) => {
            const isSelected = sortMode === mode;
            return (
              <label
                key={mode}
                className={`press-scale flex min-h-11 cursor-pointer items-center rounded-full border px-4 text-label-medium focus-within:outline-2 focus-within:outline-azure-glow ${
                  isSelected
                    ? "border-azure-glow/60 bg-secondary-container/60 text-on-secondary-container font-semibold"
                    : "border-outline-variant bg-glass-fill text-on-surface-variant"
                }`}
              >
                <input
                  type="radio"
                  name="vocab-sort"
                  value={mode}
                  checked={isSelected}
                  onChange={() => onSortChange(mode)}
                  className="sr-only"
                />
                <span>{label}</span>
              </label>
            );
          })}
        </div>
      </fieldset>

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
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const editItemRef = useRef<HTMLButtonElement>(null);
  const deleteItemRef = useRef<HTMLButtonElement>(null);
  const baseId = useId();
  const triggerId = `vocab-menu-btn-${baseId}`;
  const menuId = `vocab-menu-${baseId}`;

  useEffect(() => {
    if (!menuOpen) return;
    const handleOutsideClick = (e: MouseEvent | TouchEvent) => {
      if (
        menuRef.current &&
        !menuRef.current.contains(e.target as Node) &&
        triggerRef.current &&
        !triggerRef.current.contains(e.target as Node)
      ) {
        setMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handleOutsideClick);
    document.addEventListener("touchstart", handleOutsideClick);
    return () => {
      document.removeEventListener("mousedown", handleOutsideClick);
      document.removeEventListener("touchstart", handleOutsideClick);
    };
  }, [menuOpen]);

  useEffect(() => {
    if (menuOpen) {
      editItemRef.current?.focus();
    }
  }, [menuOpen]);

  const closeMenuAndFocusTrigger = () => {
    setMenuOpen(false);
    triggerRef.current?.focus();
  };

  const handleMenuKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      e.preventDefault();
      closeMenuAndFocusTrigger();
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (document.activeElement === editItemRef.current) {
        deleteItemRef.current?.focus();
      } else {
        editItemRef.current?.focus();
      }
    } else if (e.key === "Home") {
      e.preventDefault();
      editItemRef.current?.focus();
    } else if (e.key === "End") {
      e.preventDefault();
      deleteItemRef.current?.focus();
    } else if (e.key === "Tab") {
      setMenuOpen(false);
    }
  };

  return (
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
            ref={triggerRef}
            id={triggerId}
            type="button"
            onClick={() => setMenuOpen((prev) => !prev)}
            aria-label={t("library.moreActions")}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            aria-controls={menuId}
            className="press-scale rounded-full p-3 text-on-surface-variant"
          >
            <MoreVertIcon className="size-5" />
          </button>

          {menuOpen && (
            <div
              ref={menuRef}
              id={menuId}
              role="menu"
              aria-labelledby={triggerId}
              onKeyDown={handleMenuKeyDown}
              className="glass-surface absolute right-0 top-12 z-50 w-40 p-1"
            >
              <button
                ref={editItemRef}
                type="button"
                role="menuitem"
                tabIndex={0}
                className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-body-medium text-on-surface hover:bg-on-surface/5 focus-visible:bg-on-surface/10 focus-visible:outline-none"
                onClick={() => {
                  closeMenuAndFocusTrigger();
                  onEdit();
                }}
              >
                <EditIcon className="size-4.5" />
                {t("action.edit")}
              </button>
              <button
                ref={deleteItemRef}
                type="button"
                role="menuitem"
                tabIndex={-1}
                className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-body-medium text-error hover:bg-on-surface/5 focus-visible:bg-on-surface/10 focus-visible:outline-none"
                onClick={() => {
                  closeMenuAndFocusTrigger();
                  onDelete();
                }}
              >
                <DeleteIcon className="size-4.5" />
                {t("action.delete")}
              </button>
            </div>
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
        <h3 className="text-label-small font-bold text-primary uppercase tracking-wider">{t("detail.linguisticConnections")}</h3>
        <div className="mt-2 mb-4 h-px bg-surface-variant" />

        <div className="grid grid-cols-2 gap-4">
            <LinguisticBox title={t("detail.synonyms")} content={item.synonyms} />
            <LinguisticBox title={t("detail.antonyms")} content={item.antonyms} />
        </div>
      </div>

      <div className="mt-8">
        <h3 className="text-label-large font-bold text-primary">{t("detail.context")}</h3>
        <div className="mt-2 mb-2 h-px bg-surface-variant" />

        <div className="glass-surface p-6">
          <p className="text-label-medium font-bold text-primary">{t("detail.example")}</p>
          <p className="mt-2 text-body-large text-on-surface hyphens-auto break-words">{exampleSentence}</p>
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
        <div className="glass-surface p-4 flex flex-col gap-1 min-w-0">
            <span className="text-xs font-bold uppercase tracking-wider text-primary">{title}</span>
            <p className={`text-sm hyphens-auto break-words ${content ? 'text-on-surface' : 'text-on-surface-variant opacity-70'}`}>
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
  onSave: (german: string, english: string) => Promise<boolean>;
  t: TFunction;
}) {
  const [germanText, setGermanText] = useState(initialGerman);
  const [translation, setTranslation] = useState(initialEnglish);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const isValid = germanText.trim().length > 0 && translation.trim().length > 0;

  const handleSave = async () => {
    if (!isValid || isSaving) return;
    setIsSaving(true);
    setSaveError(null);
    try {
      const ok = await onSave(germanText.trim(), translation.trim());
      if (!ok) {
        setSaveError(t("library.saveFailed"));
      }
    } catch {
      setSaveError(t("library.saveFailed"));
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <ModalDialog
      title={title}
      onDismiss={isSaving ? () => {} : onDismiss}
      actions={
        <>
          <GlassButton
            type="button"
            disabled={isSaving}
            onClick={onDismiss}
            className="px-4 text-label-large font-bold"
          >
            {t("action.cancel")}
          </GlassButton>
          <GlassButton
            type="button"
            disabled={!isValid || isSaving}
            onClick={handleSave}
            className="px-4 text-label-large font-bold"
          >
            {isSaving ? t("action.saving") : confirmLabel}
          </GlassButton>
        </>
      }
    >
      {saveError && (
        <div role="alert" className="mb-3 rounded-lg bg-error/15 p-3 text-body-medium text-error">
          {saveError}
        </div>
      )}
      <GlassTextField
        label={t("library.fieldGerman")}
        value={germanText}
        onChange={(event) => setGermanText(event.target.value)}
        placeholder="das Wort"
        disabled={isSaving}
        autoFocus
      />
      <GlassTextField
        label={t("library.fieldTranslation")}
        value={translation}
        onChange={(event) => setTranslation(event.target.value)}
        placeholder="the word"
        disabled={isSaving}
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
