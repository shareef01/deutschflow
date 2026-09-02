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
  } = useSettings();

  const { t, lang, changeLang } = useI18n();

  const [typedKey, setTypedKey] = useState("");
  const [isKeyVisible, setIsKeyVisible] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const restoreInput = useRef<HTMLInputElement>(null);

  const onRestore = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // Cleared either way, so choosing the same file twice fires change again.
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

  return (
    <div className="mx-auto flex h-full min-h-0 w-full max-w-[var(--container-reading)] flex-col overflow-y-auto px-[var(--gutter)] pb-12">

      {/* ---- Backup ---------------------------------------------------------
           The library lives only in this browser and the browser may reclaim it,
           so this is the one control standing between the user and losing
           everything. The Android app has Room on the filesystem and does not
           need an equivalent. */}
      <SectionHeader title={t("settings.backupHeader")} />

      <div className="glass-surface border border-on-surface/5 p-6">
        {/* This already says what the removed "Cloud Sync" card's one true line
            said - the library lives only in this browser - so nothing was lost
            with it. What went was a sign-in that authenticated against a stub,
            two fields that discarded whatever was typed into them, and a sync
            button that uploaded nothing. */}
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
      <RadioGroup options={dialects} selected={selectedDialect} onSelect={saveDialect} />
      {/* Said here rather than only in the docs: it is the one privacy property
          where this app differs from the Android one, and the difference is not
          something a user could infer from the screen. */}
      <p className="mt-3 px-1 text-label-medium text-on-surface-variant">
        {t("settings.speechPrivacy")}
      </p>

      {/* ---- Language (web parity for Android 13+ per-app language) ---------- */}
      <SectionHeader title={t("settings.languageHeader")} />
      <RadioGroup options={languages} selected={lang} onSelect={changeLang} />

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
        <p className="text-[10px] font-black uppercase tracking-[0.2em] text-on-surface-variant/30">
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
    <h2 className="mt-12 mb-3 w-full pl-1 text-[10px] font-black uppercase tracking-[0.2em] text-primary">{title}</h2>
  );
}

function RadioGroup<T extends string>({ options, selected, onSelect }: { options: { label: string; code: T }[]; selected: T; onSelect: (code: T) => void }) {
  return (
    <div className="glass-surface p-2">
      {options.map((option) => {
        const isSelected = selected === option.code;
        return (
          <button key={option.code} onClick={() => onSelect(option.code)} className="flex w-full items-center gap-4 rounded-xl px-4 py-4 text-left hover:bg-on-surface/5 transition-colors">
            <span className={`flex size-6 items-center justify-center rounded-full border-2 transition-all ${isSelected ? "border-azure-glow scale-110" : "border-on-surface-variant/40"}`}>
              {isSelected && <span className="size-3 rounded-full bg-azure-glow" />}
            </span>
            <span className={`text-body-large font-bold ${isSelected ? "text-on-surface" : "text-on-surface-variant"}`}>{option.label}</span>
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
      <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest opacity-60">
        {label}
      </p>
    </div>
  );
}
