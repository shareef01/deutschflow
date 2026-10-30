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
  SearchIcon,
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
    selectedId: isDesktop ? selectedId : null,
    t,
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* A failed write outranks a failed voice: one means the library did not
          change, the other means a word was not read aloud. */}
      <ErrorBanner message={error ? t(error) : ttsError} />

      {isDesktop && !isLibraryEmpty ? (
        // Two columns that scroll on their own, held to the shell's one row, so
        // the details stay in view however far down the list the word was.
        <div className="grid min-h-0 flex-1 grid-cols-[minmax(22rem,0.9fr)_1px_minmax(0,1.1fr)] grid-rows-[minmax(0,1fr)]">
          <div className="min-h-0 min-w-0">
            <VocabularyListContent {...listProps} />
          </div>
          <div className="self-stretch bg-outline-variant/60" />
          <div className="min-h-0 min-w-0">
            <VocabularyDetail
              item={selectedItem}
              exampleSentence={exampleSentence}
              onClose={() => setSelectedId(null)}
              onSpeak={speak}
              onEdit={(item) => setEditingId(item.id ?? null)}
              onDelete={onDeleteWithUndo}
              showBack={false}
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
          onEdit={(item) => setEditingId(item.id ?? null)}
          onDelete={onDeleteWithUndo}
          showBack
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
  selectedId,
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
  selectedId: number | null;
  t: TFunction;
}) {
  const words = statsList.length;
  const phrases = statsList.filter((item) => item.germanText.trim().includes(" ")).length;
  const withExample = statsList.filter((item) => item.exampleSentence.length > 0).length;

  // An empty library is a first step, not a list with nothing in it: no search
  // box, no three zeros, no sort order for nothing - one sentence and the action.
  if (words === 0) {
    return (
      <EmptyState
        icon={<AutoStoriesIcon className="size-full" />}
        message={t("library.emptyTitle")}
        description={t("library.emptyBody")}
        action={
          <GlassButton type="button" glow="solid" onClick={onAdd} className="px-6">
            <span className="flex items-center gap-2 text-label-large font-bold">
              <AddIcon className="size-5" />
              {t("library.addWord")}
            </span>
          </GlassButton>
        }
      />
    );
  }

  const isSearching = searchQuery.trim().length > 0;

  return (
    <div className="relative flex h-full min-h-0 flex-col px-[var(--gutter)] pt-[var(--space-4)] lg:px-[var(--space-5)]">
      <SearchInput
        value={searchQuery}
        onChange={onSearchChange}
        placeholder={t("library.searchHint")}
      />

      <div className="mt-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        {/* The library's shape in one quiet line rather than a card of numbers. */}
        <p className="text-label-medium text-on-surface-variant" aria-live="polite">
          {isSearching ? (
            t("library.resultCount", [vocabularyList.length, words])
          ) : (
            <>
              <span className="text-on-surface">{words}</span> {t("library.statWords")}
              {" · "}
              <span className="text-on-surface">{phrases}</span> {t("library.statPhrases")}
              {" · "}
              <span className="text-on-surface">{withExample}</span> {t("library.statExamples")}
            </>
          )}
        </p>

        <fieldset className="m-0 border-none p-0">
          <legend className="sr-only">{t("library.sortBy")}</legend>
          <div className="flex rounded-full border border-outline-variant p-0.5">
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
                  className={`flex min-h-10 cursor-pointer items-center rounded-full px-4 text-label-medium transition-colors focus-within:outline-2 focus-within:outline-azure-glow ${
                    isSelected
                      ? "bg-secondary-container font-semibold text-on-secondary-container"
                      : "text-on-surface-variant hover:text-on-surface"
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
      </div>

      <div className="mt-3 min-h-0 flex-1">
        {vocabularyList.length === 0 ? (
          // An empty library returned early above, so this is a search with no hits.
          <EmptyState
            icon={<SearchIcon className="size-full" />}
            message={t("library.noResults")}
            description={t("library.noResultsBody")}
            action={
              <GlassButton onClick={() => onSearchChange("")}>
                {t("action.clearFilter")}
              </GlassButton>
            }
          />
        ) : (
          <ul className="flex h-full flex-col gap-2 overflow-y-auto pb-24">
            {vocabularyList.map((item) => (
              <VocabularyItem
                key={item.id}
                item={item}
                isSelected={item.id === selectedId}
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
        title={t("library.addWord")}
        className="glass-button edge-solid press-scale absolute bottom-6 right-6 flex size-14 items-center justify-center shadow-[var(--shadow-raised)]"
      >
        <AddIcon className="size-6" />
      </button>
    </div>
  );
}

/** "der", "die", "das" - or nothing for a verb, a phrase, or the AI's "none". */
function articleOf(item: VocabularyEntry) {
  return item.article && item.article !== "none" ? item.article : "";
}

function VocabularyItem({
  item,
  isSelected,
  onOpen,
  onEdit,
  onDelete,
  onSpeak,
  t,
}: {
  item: VocabularyEntry;
  isSelected: boolean;
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
    <li
      className={`glass-surface transition-colors ${menuOpen ? "" : "list-row"} ${
        isSelected ? "!border-primary bg-primary-container/40" : "hover:border-outline"
      }`}
    >
      <div className="flex items-center gap-1 py-1.5 pl-4 pr-1.5">
        <button
          type="button"
          onClick={onOpen}
          aria-current={isSelected ? "true" : undefined}
          className="min-w-0 flex-1 py-2 pr-2 text-left"
        >
          {/* The German first and loudest, its article as quiet grammar beside it,
              the meaning underneath: three things, three weights. */}
          <p className="flex min-w-0 items-baseline gap-2">
            {articleOf(item) && (
              <span lang="de" className="shrink-0 text-label-large text-primary">{articleOf(item)}</span>
            )}
            <span lang="de" className="line-clamp-2 hyphens-auto break-words text-title-medium text-on-surface">
              {item.germanText}
            </span>
          </p>
          <p className="mt-0.5 line-clamp-2 text-body-medium text-on-surface-variant">
            {item.englishTranslation}
            {item.plural && <span className="text-on-surface-muted"> · {t("wordSheet.plural", [item.plural])}</span>}
          </p>
        </button>

        <button
          type="button"
          onClick={onSpeak}
          aria-label={t("action.speak")}
          title={t("action.speak")}
          className="press-scale grid size-11 shrink-0 place-items-center rounded-full text-primary transition-colors hover:bg-primary/10"
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
            className="press-scale grid size-11 place-items-center rounded-full text-on-surface-variant transition-colors hover:bg-on-surface/5"
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
  onEdit,
  onDelete,
  showBack,
  t,
}: {
  item: VocabularyEntry | null;
  exampleSentence: string;
  onClose: () => void;
  onSpeak: (text: string) => void;
  onEdit: (item: VocabularyEntry) => void;
  onDelete: (item: VocabularyEntry) => void;
  showBack: boolean;
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

  const article = articleOf(item);
  // The article leads the headline, so it is not repeated as a fact here.
  const grammar = [
    item.plural && { label: t("detail.plural"), value: item.plural },
    item.conjugation && { label: t("detail.forms"), value: item.conjugation },
  ].filter((fact): fact is { label: string; value: string } => Boolean(fact));
  const relations = [
    item.synonyms && { label: t("detail.synonyms"), value: item.synonyms },
    item.antonyms && { label: t("detail.antonyms"), value: item.antonyms },
  ].filter((fact): fact is { label: string; value: string } => Boolean(fact));

  return (
    <article aria-labelledby="word-detail-title" className="flex h-full min-h-0 flex-col overflow-y-auto px-[var(--gutter)] py-[var(--space-5)] lg:px-[var(--space-6)]">
      {showBack && (
        <button
          type="button"
          onClick={onClose}
          className="-ml-2 mb-3 inline-flex min-h-11 items-center gap-1 self-start rounded-full px-2 text-label-large text-primary"
        >
          <span aria-hidden="true">←</span> {t("detail.back")}
        </button>
      )}

      <header className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <h2 id="word-detail-title" lang="de" className="hyphens-auto break-words text-headline-medium text-on-surface">
            {article && <span className="mr-2 font-semibold text-primary">{article}</span>}
            {item.germanText}
          </h2>
          <p className="mt-1 text-title-medium font-normal text-on-surface-variant">{item.englishTranslation}</p>
        </div>

        <button
          type="button"
          onClick={() => onSpeak(item.germanText)}
          aria-label={t("action.speak")}
          title={t("action.speak")}
          className="glass-button press-scale flex size-12 shrink-0 items-center justify-center text-primary"
        >
          <PlayArrowIcon className="size-6" />
        </button>
      </header>

      {grammar.length > 0 && (
        <section className="mt-6">
          <h3 className="eyebrow">{t("detail.grammar")}</h3>
          <dl className="mt-2 grid gap-2 sm:grid-cols-[repeat(auto-fit,minmax(9rem,1fr))]">
            {grammar.map((fact) => (
              <div key={fact.label} className="glass-raised px-4 py-3">
                <dt className="text-label-small text-on-surface-variant">{fact.label}</dt>
                <dd lang="de" className="mt-0.5 break-words text-body-large text-on-surface">{fact.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}

      <section className="mt-6">
        <h3 className="eyebrow">{t("detail.context")}</h3>
        <blockquote lang="de" className="mt-2 border-l-2 border-primary/60 py-1 pl-4 text-body-large text-on-surface hyphens-auto break-words">
          {exampleSentence}
        </blockquote>
      </section>

      <section className="mt-6">
        <h3 className="eyebrow">{t("detail.linguisticConnections")}</h3>
        {relations.length > 0 ? (
          <dl className="mt-2 grid gap-2 sm:grid-cols-2">
            {relations.map((fact) => (
              <div key={fact.label} className="glass-raised px-4 py-3">
                <dt className="text-label-small text-on-surface-variant">{fact.label}</dt>
                <dd lang="de" className="mt-0.5 break-words text-body-large text-on-surface">{fact.value}</dd>
              </div>
            ))}
          </dl>
        ) : (
          // A sentence, not two boxes of em dashes.
          <p className="mt-2 text-body-medium text-on-surface-variant">{t("detail.noRelations")}</p>
        )}
      </section>

      <div className="mt-auto flex flex-wrap gap-2 pt-8">
        <GlassButton type="button" onClick={() => onEdit(item)} className="px-5">
          <span className="flex items-center gap-2 text-label-large font-bold">
            <EditIcon className="size-4.5" />
            {t("action.edit")}
          </span>
        </GlassButton>
        <GlassButton type="button" glow="error" onClick={() => onDelete(item)} className="px-5">
          <span className="flex items-center gap-2 text-label-large font-bold">
            <DeleteIcon className="size-4.5" />
            {t("action.delete")}
          </span>
        </GlassButton>
      </div>
    </article>
  );
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
