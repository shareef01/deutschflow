"use client";

import { useMemo, useState } from "react";
import { useVocabulary } from "@/hooks/useVocabulary";
import { useIsDesktop } from "@/hooks/useViewport";
import { useBackHandler } from "@/hooks/useBackHandler";
import { useI18n } from "@/hooks/useI18n";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { SearchInput, GlassTextField } from "@/components/ui/GlassTextField";
import { GlassButton } from "@/components/ui/GlassButton";
import { ModalDialog } from "@/components/ui/ModalDialog";
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

/**
 * VocabularyScreen — ui/screens/VocabularyScreen.kt + VocabularyDetailScreen.kt
 * port.
 *
 * Below 768px the detail view is a state swap inside this destination (with the
 * browser back gesture closing it — the BackHandler). At 768px and above the
 * list and detail sit side by side, separated by the same azure hairline the
 * navigation bar uses.
 */
export default function VocabularyPage() {
  const {
    list,
    searchQuery,
    setSearchQuery,
    ttsError,
    addVocabulary,
    deleteVocabulary,
    updateVocabulary,
    exampleFor,
    speak,
  } = useVocabulary();

  const isDesktop = useIsDesktop();
  const { t } = useI18n();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [isAdding, setIsAdding] = useState(false);

  const selectedItem = list.find((item) => item.id === selectedId) ?? null;
  const editingItem = list.find((item) => item.id === editingId) ?? null;

  // The model's own example when the word came from a translation, and a
  // generated one only for words typed in by hand. Keyed on the resolved word
  // rather than the id: the generator picks at random, so composing inline
  // would reshuffle the sentence on every render.
  const exampleSentence = useMemo(() => {
    if (!selectedItem) return "";
    return selectedItem.exampleSentence || exampleFor(selectedItem.germanText);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedItem?.germanText, selectedItem?.exampleSentence]);

  // On a compact width the detail view is a state swap, so the back gesture
  // must close the word instead of leaving the library altogether.
  useBackHandler(!isDesktop && selectedItem != null, () => setSelectedId(null));

  const listProps = {
    searchQuery,
    onSearchChange: setSearchQuery,
    vocabularyList: list,
    onItemClick: (item: VocabularyEntry) => setSelectedId(item.id ?? null),
    onEdit: (item: VocabularyEntry) => setEditingId(item.id ?? null),
    onDelete: deleteVocabulary,
    onSpeak: speak,
    onAdd: () => setIsAdding(true),
    t,
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ErrorBanner message={ttsError} />

      {isDesktop ? (
        <div className="flex min-h-0 flex-1">
          <div className="min-w-0 flex-1">
            <VocabularyListContent {...listProps} />
          </div>
          {/* The same azure hairline the navigation bar uses for its divider:
              the one divider language in the app. */}
          <div className="w-px shrink-0 self-stretch bg-[rgba(0,229,255,0.15)]" />
          <div className="min-w-0 flex-1">
            <VocabularyDetail
              item={selectedItem}
              exampleSentence={exampleSentence}
              onClose={() => setSelectedId(null)}
              onSpeak={speak}
              t={t}
            />
          </div>
        </div>
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
    </div>
  );
}

function VocabularyListContent({
  searchQuery,
  onSearchChange,
  vocabularyList,
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
  onItemClick: (item: VocabularyEntry) => void;
  onEdit: (item: VocabularyEntry) => void;
  onDelete: (item: VocabularyEntry) => void;
  onSpeak: (text: string) => void;
  onAdd: () => void;
  t: TFunction;
}) {
  return (
    <div className="relative flex h-full min-h-0 flex-col px-5 py-4">
      <div className="pt-2">
        <SearchInput
          value={searchQuery}
          onChange={onSearchChange}
          placeholder={t("library.searchHint")}
        />
      </div>

      <div className="mt-5 min-h-0 flex-1">
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

      {/* The only way into the library that never touches the network. A glass
          disc, not a solid block — the app has no solid primary buttons. */}
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
    <li className="glass-surface">
      {/* Top, not centre: entries are whole sentences, so the text block is
          often three lines tall and the controls used to sit stranded in the
          middle of it. */}
      <div className="flex items-start gap-1 p-2 pl-4">
        <button type="button" onClick={onOpen} className="min-w-0 flex-1 py-2 pr-2 text-left">
          <p className="line-clamp-3 text-title-medium text-primary">{item.germanText}</p>
          <p className="mt-1 line-clamp-2 text-body-medium text-on-surface-variant">
            {item.englishTranslation}
          </p>
        </button>

        <button
          type="button"
          onClick={onSpeak}
          aria-label={t("action.speak")}
          className="press-scale shrink-0 rounded-full p-2.5 text-primary"
        >
          <PlayArrowIcon className="size-5" />
        </button>

        <div className="relative shrink-0">
          <button
            type="button"
            onClick={() => setMenuOpen(true)}
            aria-label={t("library.moreActions")}
            className="press-scale rounded-full p-2.5 text-on-surface-variant"
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
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-body-medium text-on-surface hover:bg-white/5"
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
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-body-medium text-error hover:bg-white/5"
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
          {/* headlineMedium, not display: entries are whole sentences here. */}
          <h2 className="text-headline-medium text-primary">{item.germanText}</h2>
          {grammar && (
            <p className="mt-1 text-label-large text-on-surface-variant">{grammar}</p>
          )}
          <p className="mt-1 text-title-medium text-on-surface-variant">{item.englishTranslation}</p>
        </div>

        {/* The same control that speaks everywhere: one size, one colour pair
            for "hear this aloud". */}
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
        <h3 className="text-label-large font-bold text-primary">{t("detail.context")}</h3>
        <div className="mt-2 mb-2 h-px bg-surface-variant" />

        <div className="glass-surface p-6">
          <p className="text-label-medium font-bold text-primary">{t("detail.example")}</p>
          <p className="mt-2 text-body-large text-on-surface">{exampleSentence}</p>
        </div>
      </div>

      <div className="flex-1" />

      <GlassButton type="button" onClick={onClose} className="mt-6 w-full">
        <span className="text-label-large font-bold">{t("detail.back")}</span>
      </GlassButton>
    </div>
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
  onSave: (german: string, english: string) => void;
  t: TFunction;
}) {
  const [germanText, setGermanText] = useState(initialGerman);
  const [translation, setTranslation] = useState(initialEnglish);

  // Saving blank fields used to be allowed, which wrote two empty strings over
  // a real entry and left an unreachable row in the library.
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
