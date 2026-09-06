"use client";

import { useRef, useState } from "react";
import { useSettings } from "@/hooks/useSettings";
import { useI18n } from "@/hooks/useI18n";
import { GlassTextField } from "@/components/ui/GlassTextField";
import { GlassButton } from "@/components/ui/GlassButton";
import { GlassSwitch } from "@/components/ui/GlassSwitch";
import { ModalDialog } from "@/components/ui/ModalDialog";
import {
  CheckIcon,
  DeleteForeverIcon,
  VisibilityIcon,
  VisibilityOffIcon,
  WarningIcon
} from "@/components/icons";
import type { Lang } from "@/lib/i18n";

export default function SettingsPage() {
  const {
    totalVocabulary,
    totalTranscripts,
    xp,
    streak,
    hasApiKey,
    selectedDialect,
    isAutoPlayEnabled,
    saveApiKey,
    saveDialect,
    setAutoPlayEnabled,
    clearAllProgress,
    downloadBackup,
    restoreBackup,
    isPersisted,
    isPersistenceSupported,
    requestPersistence,
    lastBackupTime,
  } = useSettings();

  const { t, lang, changeLang } = useI18n();

  const [typedKey, setTypedKey] = useState("");
  const [isKeyVisible, setIsKeyVisible] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const restoreInput = useRef<HTMLInputElement>(null);

  const onRestore = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (file) void restoreBackup(file).then((result) => setMessage(t(result)));
  };

  const onSaveKey = () => {
    void saveApiKey(typedKey).then((result) => {
      setMessage(t(result));
      setTypedKey("");
      setIsKeyVisible(false);
    });
  };

  const onClearAll = () => {
    void clearAllProgress().then((result) => {
      setShowDeleteConfirm(false);
      setMessage(t(result));
    });
  };

  const dialects: { label: string; code: "de-DE" | "de-AT" | "de-CH" }[] = [
    { label: t("settings.dialectDe"), code: "de-DE" },
    { label: t("settings.dialectAt"), code: "de-AT" },
    { label: t("settings.dialectCh"), code: "de-CH" },
  ];

  const languages: { label: string; code: Lang }[] = [
    { label: t("language.english"), code: "en" },
    { label: t("language.german"), code: "de" },
  ];

  const streakLabel = streak === 1 ? t("streak.day", [streak]) : t("streak.days", [streak]);

  // Remind user to back up if they have 5+ vocabulary items and no backup in the last 7 days
  const needsBackupReminder =
    totalVocabulary >= 5 &&
    (!lastBackupTime || Date.now() - lastBackupTime > 7 * 86_400_000);

  return (
    <div className="mx-auto flex h-full min-h-0 w-full max-w-[var(--container-reading)] flex-col overflow-y-auto px-[var(--gutter)] pb-12">

      {/* Backup Freshness Reminder Banner */}
      {needsBackupReminder && (
        <div className="glass-surface mt-6 flex items-start gap-3.5 border-l-4 border-amber-400 p-4">
          <WarningIcon className="mt-0.5 size-5 shrink-0 text-amber-400" />
          <div className="flex-1">
            <p className="text-body-small text-on-surface">
              {t("settings.backupReminder", [totalVocabulary])}
            </p>
          </div>
        </div>
      )}

      {/* ---- Backup --------------------------------------------------------- */}
      <SectionHeader title={t("settings.backupHeader")} />

      <div className="glass-surface border border-on-surface/5 p-6">
        <p className="text-body-medium text-on-surface-variant">{t("settings.backupBody")}</p>
        <div className="mt-4 flex flex-col gap-2 sm:flex-row">
          <GlassButton
            className="h-12 flex-1"
            onClick={() => void downloadBackup().then((result) => setMessage(t(result)))}
          >
            <span className="font-bold">{t("settings.backupDownload")}</span>
          </GlassButton>
          <GlassButton className="h-12 flex-1" onClick={() => restoreInput.current?.click()}>
            <span className="font-bold">{t("settings.backupRestore")}</span>
          </GlassButton>
        </div>
        <p className="mt-3 text-label-small text-on-surface-variant">
          {lastBackupTime
            ? t("settings.backupLast", [new Date(lastBackupTime).toLocaleDateString()])
            : t("settings.backupNever")}
        </p>
        <input
          ref={restoreInput}
          type="file"
          accept="application/json,.json"
          className="hidden"
          onChange={onRestore}
        />
      </div>

      {/* ---- AI translation ------------------------------------------------- */}
      <SectionHeader title={t("settings.aiHeader")} />

      <div className="relative">
        <GlassTextField
          value={typedKey}
          onChange={(event) => setTypedKey(event.target.value)}
          label={t("settings.apiKeyLabel")}
          placeholder={
            hasApiKey ? t("settings.apiKeyReplace") : t("settings.apiKeyHint")
          }
          type={isKeyVisible ? "text" : "password"}
          autoComplete="off"
          autoCorrect="off"
          spellCheck={false}
          trailingIcon={
            <span className="flex shrink-0 items-center">
              <button
                type="button"
                onClick={() => setIsKeyVisible((visible) => !visible)}
                aria-label={isKeyVisible ? t("settings.hideKey") : t("settings.showKey")}
                className="press-scale rounded-full p-3 text-on-surface-variant"
              >
                {isKeyVisible ? <VisibilityOffIcon className="size-5" /> : <VisibilityIcon className="size-5" />}
              </button>
              <button
                type="button"
                onClick={onSaveKey}
                disabled={typedKey.trim().length === 0}
                aria-label={t("action.save")}
                className={`press-scale rounded-full p-3 ${
                  typedKey.trim().length > 0 ? "text-primary" : "text-on-surface-variant/40"
                }`}
              >
                <CheckIcon className="size-5" />
              </button>
            </span>
          }
        />
      </div>

      <p className={`mt-2 pl-1 text-label-medium ${hasApiKey ? "text-on-surface-variant" : "text-error"}`}>
        {hasApiKey ? t("settings.apiKeySavedState") : t("settings.apiKeyNone")}
      </p>

      {/* ---- Learning progress (2x2 Grid) ----------------------------------- */}
      <SectionHeader title={t("settings.progressHeader")} />

      <div className="glass-surface p-6 border border-on-surface/5">
        <div className="grid grid-cols-2 gap-6">
          <StatGridItem label={t("settings.statVocabulary")} value={String(totalVocabulary)} />
          <StatGridItem label={t("settings.statSessions")} value={String(totalTranscripts)} />
          <StatGridItem label={t("settings.statXp")} value={String(xp)} />
          <StatGridItem label={t("settings.statStreak")} value={streakLabel} />
        </div>
      </div>

      {/* ---- Audio preferences ---------------------------------------------- */}
      <SectionHeader title={t("settings.audioHeader")} />
      <div className="glass-surface">
        <div className="flex items-center justify-between p-4">
          <span className="text-body-large font-medium">{t("settings.autoplay")}</span>
          <GlassSwitch checked={isAutoPlayEnabled} onChange={setAutoPlayEnabled} label={t("settings.autoplay")} />
        </div>
      </div>

      {/* ---- Recognition dialect --------------------------------------------- */}
      <SectionHeader title={t("settings.dialectHeader")} />
      <RadioGroup name={t("settings.dialectHeader")} options={dialects} selected={selectedDialect} onSelect={saveDialect} />
      <p className="mt-3 px-1 text-label-medium text-on-surface-variant">
        {t("settings.speechPrivacy")}
      </p>

      {/* ---- Language (web parity for Android 13+ per-app language) ---------- */}
      <SectionHeader title={t("settings.languageHeader")} />
      <RadioGroup name={t("settings.languageHeader")} options={languages} selected={lang} onSelect={changeLang} />

      {/* ---- Storage Durability ----------------------------------------------- */}
      <SectionHeader title={t("settings.storageHeader")} />
      <div className="glass-surface border border-on-surface/5 p-6">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className={`text-body-medium font-semibold ${isPersisted ? "text-emerald-400" : "text-on-surface"}`}>
              {isPersisted ? t("settings.storagePersisted") : t("settings.storageBestEffort")}
            </p>
            {!isPersistenceSupported && (
              <p className="mt-1 text-label-small text-on-surface-variant">
                {t("settings.storageUnsupported")}
              </p>
            )}
          </div>
          {isPersistenceSupported && !isPersisted && (
            <GlassButton
              type="button"
              onClick={() => void requestPersistence()}
              className="shrink-0"
            >
              <span className="text-label-medium font-bold">{t("settings.storageRequest")}</span>
            </GlassButton>
          )}
        </div>
      </div>

      {/* ---- Data ------------------------------------------------------------- */}
      <SectionHeader title={t("settings.dataHeader")} />
      <button
        type="button"
        onClick={() => setShowDeleteConfirm(true)}
        className="glass-surface flex w-full items-center gap-3 px-6 py-4 text-left border-l-4 border-error/40"
      >
        <DeleteForeverIcon className="size-5 shrink-0 text-error" />
        <span className="text-body-large font-bold text-error">{t("settings.clear")}</span>
      </button>

      <div className="mt-12 text-center">
        <p className="text-xs font-medium uppercase tracking-[0.15em] text-on-surface-variant/60">
          DeutschFlow v1.3.0 Obsidian
        </p>
      </div>

      {/* ---- Modals ----------------------------------------------------------- */}
      {showDeleteConfirm && (
        <ModalDialog
          title={t("settings.wipeTitle")}
          onDismiss={() => setShowDeleteConfirm(false)}
          actions={
            <>
              <GlassButton onClick={() => setShowDeleteConfirm(false)}>{t("action.cancel")}</GlassButton>
              <GlassButton type="button" glow="error" onClick={onClearAll}>{t("settings.wipeConfirm")}</GlassButton>
            </>
          }
        >
          <p className="text-body-medium text-on-surface-variant pt-2">{t("settings.wipeBody")}</p>
        </ModalDialog>
      )}

      {message != null && (
        <ModalDialog onDismiss={() => setMessage(null)} actions={<GlassButton onClick={() => setMessage(null)}>OK</GlassButton>}>
          <p className="text-body-medium text-on-surface pt-2">{message}</p>
        </ModalDialog>
      )}
    </div>
  );
}

function SectionHeader({ title }: { title: string }) {
  return (
    <h2 className="mt-12 mb-3 w-full pl-1 text-xs font-bold uppercase tracking-[0.15em] text-primary">{title}</h2>
  );
}

function RadioGroup<T extends string>({
  options,
  selected,
  onSelect,
  name,
}: {
  options: { label: string; code: T }[];
  selected: T;
  onSelect: (code: T) => void;
  name?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);

  const handleKeyDown = (event: React.KeyboardEvent, currentIndex: number) => {
    let nextIndex = -1;
    if (event.key === "ArrowDown" || event.key === "ArrowRight") {
      event.preventDefault();
      nextIndex = (currentIndex + 1) % options.length;
    } else if (event.key === "ArrowUp" || event.key === "ArrowLeft") {
      event.preventDefault();
      nextIndex = (currentIndex - 1 + options.length) % options.length;
    }

    if (nextIndex >= 0) {
      onSelect(options[nextIndex].code);
      const buttons = containerRef.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]');
      buttons?.[nextIndex]?.focus();
    }
  };

  return (
    <div ref={containerRef} role="radiogroup" aria-label={name} className="glass-surface p-2">
      {options.map((option, index) => {
        const isSelected = selected === option.code;
        return (
          <button
            key={option.code}
            type="button"
            role="radio"
            aria-checked={isSelected}
            tabIndex={isSelected ? 0 : -1}
            onClick={() => onSelect(option.code)}
            onKeyDown={(e) => handleKeyDown(e, index)}
            className="flex w-full items-center gap-4 rounded-xl px-4 py-4 text-left transition-colors hover:bg-on-surface/5 focus-visible:outline-2 focus-visible:outline-azure-glow"
          >
            <span
              aria-hidden="true"
              className={`flex size-6 items-center justify-center rounded-full border-2 transition-all ${
                isSelected ? "scale-110 border-azure-glow" : "border-on-surface-variant/40"
              }`}
            >
              {isSelected && <span className="size-3 rounded-full bg-azure-glow" />}
            </span>
            <span
              className={`text-body-large font-bold ${
                isSelected ? "text-on-surface" : "text-on-surface-variant"
              }`}
            >
              {option.label}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function StatGridItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1">
      <p className="text-3xl font-black text-on-surface tracking-tight">
        {value}
      </p>
      <p className="text-xs font-semibold text-on-surface-variant uppercase tracking-wider">
        {label}
      </p>
    </div>
  );
}
