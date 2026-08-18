"use client";

import { useState } from "react";
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
  WarningIcon,
} from "@/components/icons";
import type { Lang } from "@/lib/i18n";

/**
 * SettingsScreen — ui/screens/SettingsScreen.kt port.
 *
 * The API key is write-only from here: the field starts empty and stays empty
 * even when a key is stored (seeding it with the decrypted key would hand a
 * filled password field to every password manager on the device). The screen
 * says whether one is saved, and typing replaces it.
 *
 * The web adds one Android parity surface: the app language (Android 13+
 * per-app language), as Deutsch/English here.
 */
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
  } = useSettings();

  const { t, lang, changeLang } = useI18n();

  // remember, not persisted: a half-typed key must not survive in session state.
  const [typedKey, setTypedKey] = useState("");
  const [isKeyVisible, setIsKeyVisible] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const onSaveKey = () => {
    void saveApiKey(typedKey).then((result) => {
      // The hook hands back an i18n key; the message is resolved here, in the
      // language the UI is currently showing.
      setMessage(t(result));
      // The plaintext must not outlive the save.
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
    <div className="mx-auto flex h-full min-h-0 w-full max-w-[var(--container-reading)] flex-col overflow-y-auto px-[var(--gutter)]">
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
                {isKeyVisible ? (
                  <VisibilityOffIcon className="size-5" />
                ) : (
                  <VisibilityIcon className="size-5" />
                )}
              </button>
              {/* Nothing typed is nothing to save — an empty save would silently
                  wipe a working key. */}
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

      {/* Says whether a key is stored without ever showing it. */}
      <p
        className={`mt-2 pl-1 text-label-medium ${
          hasApiKey ? "text-on-surface-variant" : "text-error"
        }`}
      >
        {hasApiKey ? t("settings.apiKeySavedState") : t("settings.apiKeyNone")}
      </p>
      <p className="max-w-[60ch] mt-2 pl-1 text-body-medium text-on-surface-variant">
        {t("settings.apiKeyHelp")}
      </p>

      {/* ---- Audio preferences ---------------------------------------------- */}
      <SectionHeader title={t("settings.audioHeader")} />

      <div className="glass-surface">
        <div className="flex items-center justify-between p-4">
          <span className="text-body-large font-medium">{t("settings.autoplay")}</span>
          <GlassSwitch
            checked={isAutoPlayEnabled}
            onChange={setAutoPlayEnabled}
            label={t("settings.autoplay")}
          />
        </div>
      </div>

      {/* ---- Recognition dialect --------------------------------------------- */}
      <SectionHeader title={t("settings.dialectHeader")} />

      <RadioGroup options={dialects} selected={selectedDialect} onSelect={saveDialect} />

      {/* ---- Language (web parity for Android 13+ per-app language) ---------- */}
      <SectionHeader title={t("settings.languageHeader")} />

      <RadioGroup options={languages} selected={lang} onSelect={changeLang} />

      {/* ---- Learning progress ---------------------------------------------- */}
      <SectionHeader title={t("settings.progressHeader")} />

      <div className="glass-surface p-4">
        <div className="grid grid-cols-2 gap-4">
          <StatGridItem label={t("settings.statVocabulary")} value={String(totalVocabulary)} />
          <StatGridItem label={t("settings.statSessions")} value={String(totalTranscripts)} />
          <StatGridItem label={t("settings.statXp")} value={String(xp)} />
          <StatGridItem label={t("settings.statStreak")} value={streakLabel} />
        </div>
      </div>

      <div className="mt-6 h-px bg-surface-variant" />

            {/* ---- Data ------------------------------------------------------------- */}
      <SectionHeader title={t("settings.dataHeader")} />

      <button
        type="button"
        onClick={() => setShowDeleteConfirm(true)}
        className="glass-surface flex w-full items-center gap-3 px-4 py-3 text-left"
      >
        <DeleteForeverIcon className="size-5 shrink-0 text-error" />
        <span className="text-body-large text-error">{t("settings.clear")}</span>
      </button>
      <div className="h-6" />

{/* ---- Wipe confirmation ------------------------------------------------ */}
      {showDeleteConfirm && (
        <ModalDialog
          title={t("settings.wipeTitle")}
          onDismiss={() => setShowDeleteConfirm(false)}
          actions={
            <>
              <GlassButton
                type="button"
                onClick={() => setShowDeleteConfirm(false)}
                className="px-4 text-label-large font-bold"
              >
                {t("settings.wipeCancel")}
              </GlassButton>
              <GlassButton type="button" glow="error" onClick={onClearAll} className="px-4">
                <span className="text-label-large font-bold text-error">
                  {t("settings.wipeConfirm")}
                </span>
              </GlassButton>
            </>
          }
        >
          <div className="flex items-start gap-3">
            <WarningIcon className="mt-0.5 size-5 shrink-0 text-error" />
            <p className="text-body-medium text-on-surface-variant">{t("settings.wipeBody")}</p>
          </div>
        </ModalDialog>
      )}

      {/* ---- Result message ---------------------------------------------------- */}
      {message != null && (
        <ModalDialog
          onDismiss={() => setMessage(null)}
          actions={
            <GlassButton type="button" onClick={() => setMessage(null)} className="px-4">
              <span className="text-label-large font-bold">{t("action.ok")}</span>
            </GlassButton>
          }
        >
          <p className="text-body-medium text-on-surface">{message}</p>
        </ModalDialog>
      )}
    </div>
  );
}

function SectionHeader({ title }: { title: string }) {
  return (
    <h2 className="mt-8 mb-2 w-full pl-1 text-label-large text-on-surface-variant">{title}</h2>
  );
}

function RadioGroup<T extends string>({
  options,
  selected,
  onSelect,
}: {
  options: { label: string; code: T }[];
  selected: T;
  onSelect: (code: T) => void;
}) {
  return (
    <div className="glass-surface px-2 py-1">
      {options.map((option) => {
        const isSelected = selected === option.code;
        return (
          <button
            key={option.code}
            type="button"
            onClick={() => onSelect(option.code)}
            className="flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left"
          >
            <span
              className={`flex size-5 items-center justify-center rounded-full border-2 ${
                isSelected ? "border-azure-glow" : "border-on-surface-variant"
              }`}
            >
              {isSelected && <span className="size-2.5 rounded-full bg-azure-glow" />}
            </span>
            <span className={`text-body-large ${isSelected ? "text-on-surface" : "text-on-surface-variant"}`}>
              {option.label}
            </span>
          </button>
        );
      })}
    </div>
  );
}

/**
 * One cell of the telemetry matrix. The number is painted with the azure ramp
 * running through the glyphs themselves — the only text in the app treated
 * that way, so the four figures read as instrument output rather than copy.
 */
function StatGridItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="glass-raised p-4">
      <p className="truncate text-headline-large font-bold text-on-surface">
        {value}
      </p>
      <p className="mt-1 text-label-small text-on-surface-variant">
        {label}
      </p>
    </div>
  );
}
