"use client";

import { useEffect, useState, useRef } from "react";
import { usePractice } from "@/hooks/usePractice";
import { useRoleplay } from "@/hooks/useRoleplay";
import { useI18n } from "@/hooks/useI18n";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { GlassButton } from "@/components/ui/GlassButton";
import { AudioWaveform } from "@/components/ui/AudioWaveform";
import { MicIcon, NavigateNextIcon, StopIcon, VolumeUpIcon } from "@/components/icons";
import { PRACTICE_FEEDBACK_KEYS } from "@/lib/scoring";

export default function PracticePage() {
  const [selectedTab, setSelectedTab] = useState<"repetition" | "roleplay">("repetition");
  const { t } = useI18n();

  // Mounted at page level, not inside RoleplayMode: the hook holds the
  // conversation, and unmounting it on every tab switch would silently reset the
  // chat and re-greet the user. Its utterance subscription is gated by `active`,
  // so exactly one mode listens at a time.
  const roleplay = useRoleplay({ active: selectedTab === "roleplay" });

  return (
    <div className="flex h-full flex-col">
        {/* Tab Selector */}
        <div className="flex w-full justify-center gap-8 border-b border-white/5 bg-background/50 backdrop-blur-md">
            {(["repetition", "roleplay"] as const).map((tab) => (
                <button
                    key={tab}
                    onClick={() => setSelectedTab(tab)}
                    className={`px-6 py-4 text-sm font-bold uppercase tracking-widest transition-all ${
                        selectedTab === tab
                        ? "text-primary border-b-2 border-primary"
                        : "text-on-surface-variant hover:text-on-surface"
                    }`}
                >
                    {tab === "repetition" ? t("practice.tab") : t("roleplay.tab")}
                </button>
            ))}
        </div>

        <div className="flex-1 min-h-0">
            {selectedTab === "repetition" ? <RepetitionMode /> : <RoleplayMode roleplay={roleplay} />}
        </div>
    </div>
  );
}

function RepetitionMode() {
  const { t } = useI18n();
  const {
    targetSentence,
    feedback,
    wordResults,
    partialText,
    spokenText,
    isListening,
    isProcessing,
    rmsLevel,
    errorState,
    startPractice,
    stopPractice,
    cancelListening,
    speak,
    nextSentence,
  } = usePractice();

  useEffect(() => () => cancelListening(), [cancelListening]);

  // Android treats GOOD as a positive result too; this used to colour it as a
  // failure, so the same attempt came back green on the phone and red here.
  const isPositive = feedback === "PERFECT" || feedback === "GOOD";
  // The banner reports the count it actually has. It used to say "Perfect
  // pronunciation", which the screen has no way to know: the Web Speech API
  // hands back a transcript, not a per-phoneme score, and its language model
  // will happily resolve poor audio into the word you meant.
  const heardCount = wordResults.filter((result) => result.isCorrect).length;
  const feedbackText =
    feedback === "NONE"
      ? null
      : t(PRACTICE_FEEDBACK_KEYS[feedback], [heardCount, wordResults.length]);

  return (
    <div className="mx-auto flex h-full w-full max-w-[var(--container-workspace)] flex-col overflow-y-auto px-[var(--gutter)] py-[var(--space-6)]">
      <p className="mb-2 w-full pl-1 text-label-large text-on-surface-variant">
        {t("practice.listenRepeat")}
      </p>

      <div className="glass-surface shadow-lg shadow-azure-glow/5">
        <div className="flex items-center gap-6 p-8">
          <p lang="de" className="min-w-0 flex-1 hyphens-auto break-words text-3xl font-bold leading-tight text-on-surface">
            {wordResults.length === 0
              ? targetSentence
              : wordResults.map((result, i) => (
                  <span
                    key={`${result.word}-${i}`}
                    className={
                      result.isCorrect ? "font-bold text-azure-glow" : "font-bold text-error"
                    }
                  >
                    {result.word}{" "}
                  </span>
                ))}
          </p>
          <button
            type="button"
            onClick={() => speak(targetSentence)}
            className="glass-button press-scale flex size-14 shrink-0 items-center justify-center text-azure-glow"
          >
            <VolumeUpIcon className="size-7" />
          </button>
        </div>
      </div>

      <ErrorBanner message={errorState} />

      <div className="w-full flex-1 pt-6">
        <div className="glass-surface p-8 shadow-lg shadow-azure-glow/5">
          {isListening ? (
            <div>
              {partialText && (
                <p className="line-clamp-3 w-full text-center text-base text-on-surface">
                  {partialText}
                </p>
              )}
              <AudioWaveform
                getLevel={() => rmsLevel}
                isActive
                className="mt-4 h-16 w-full"
              />
            </div>
          ) : isProcessing ? (
            <div className="flex min-h-[96px] w-full items-center justify-center">
              <span className="size-9 animate-spin rounded-full border-[3px] border-azure-glow border-t-transparent" />
            </div>
          ) : spokenText.length === 0 ? (
            <p className="mx-auto max-w-[60ch] w-full px-4 py-8 text-center text-sm text-on-surface-variant">
              {t("practice.intro")}
            </p>
          ) : (
            <div className="flex w-full flex-col items-center">
              {feedbackText && (
                <span
                  className={`rounded-full px-5 py-2 text-center text-sm font-medium ${
                    isPositive
                      ? "bg-tertiary-container text-on-tertiary-container"
                      : "bg-error-container text-on-error-container"
                  }`}
                >
                  {feedbackText}
                </span>
              )}

              {wordResults.length > 0 && (
                <p className="mt-3 max-w-[46ch] text-center text-label-medium text-on-surface-variant">
                  {t("practice.feedbackCaption")}
                </p>
              )}

              <p className="mt-4 w-full text-center text-base text-on-surface">
                {spokenText}
              </p>
            </div>
          )}
        </div>
      </div>

      <div className="mt-6 flex w-full flex-col gap-3 pb-5 xs:flex-row xs:gap-4">
        <GlassButton
          type="button"
          disabled={isProcessing}
          glow={isListening ? "error" : "azure"}
          onClick={isListening ? stopPractice : () => void startPractice()}
          className="w-full xs:flex-1 h-14"
        >
          <span className="flex items-center justify-center gap-2">
            {isListening ? <StopIcon className="size-5" /> : <MicIcon className="size-5" />}
            <span className="text-sm font-bold">
              {isListening ? t("practice.evaluate") : t("practice.speak")}
            </span>
          </span>
        </GlassButton>
        <GlassButton type="button" onClick={nextSentence} className="w-full xs:flex-1 h-14">
          <span className="flex items-center justify-center gap-2">
            <NavigateNextIcon className="size-5" />
            <span className="text-sm font-bold">{t("practice.next")}</span>
          </span>
        </GlassButton>
      </div>
    </div>
  );
}

type Roleplay = ReturnType<typeof useRoleplay>;

function RoleplayMode({ roleplay }: { roleplay: Roleplay }) {
    const { t } = useI18n();
    const {
        messages, isProcessing, isListening, partialText,
        startSession, startListening, stopAndSend, speak
    } = roleplay;

    const scrollRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (messages.length === 0) void startSession();
    }, [messages.length, startSession]);

    useEffect(() => {
        if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages.length, isProcessing, partialText]);

    return (
        <div className="flex h-full flex-col p-4">
            <div ref={scrollRef} className="flex-1 overflow-y-auto space-y-4 pb-4">
                {messages.map((msg, i) => (
                    <div key={i} className={`flex w-full ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                        <div className={`max-w-[85%] rounded-2xl p-4 glass-surface ${
                            msg.role === 'user' ? 'bg-primary/20 border-primary/20' : 'bg-surface-variant/40'
                        }`}>
                            <p className="text-base text-on-surface">{msg.content}</p>
                            {msg.translation && (
                                <p className="mt-2 text-xs text-on-surface-variant opacity-60 italic">{msg.translation}</p>
                            )}
                            {msg.role === 'assistant' && (
                                <button onClick={() => speak(msg.content)} className="mt-2 text-primary press-scale">
                                    <VolumeUpIcon className="size-4" />
                                </button>
                            )}
                        </div>
                    </div>
                ))}
                {isProcessing && (
                    <div className="flex justify-start">
                        <div className="glass-surface p-4 opacity-50 animate-pulse">{t("roleplay.thinking")}</div>
                    </div>
                )}
            </div>

            <div className="glass-surface p-6 flex flex-col items-center gap-4 shadow-xl">
                {isListening && partialText && (
                    <p className="text-sm text-on-surface text-center animate-in fade-in slide-in-from-bottom-2">
                        {partialText}
                    </p>
                )}

                <div className="relative group">
                    {isListening && (
                        <div className="absolute inset-0 bg-primary/20 rounded-full animate-ping scale-150" />
                    )}
                    <button
                        onClick={isListening ? stopAndSend : startListening}
                        disabled={isProcessing}
                        className={`relative z-10 size-16 rounded-full flex items-center justify-center transition-all ${
                            isListening ? 'bg-error scale-110 shadow-error/20' : 'bg-primary shadow-primary/20'
                        } shadow-2xl hover:scale-105 active:scale-95 disabled:opacity-50`}
                    >
                        {isListening ? <StopIcon className="size-8 text-white" /> : <MicIcon className="size-8 text-white" />}
                    </button>
                </div>
                <span className="text-[10px] font-black uppercase tracking-[0.2em] text-on-surface-variant/50">
                    {isListening ? t("roleplay.stopSend") : t("roleplay.speakReply")}
                </span>
            </div>
        </div>
    )
}
