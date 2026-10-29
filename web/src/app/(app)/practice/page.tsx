"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { usePractice } from "@/hooks/usePractice";
import { useRoleplay } from "@/hooks/useRoleplay";
import { useI18n } from "@/hooks/useI18n";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { GlassButton } from "@/components/ui/GlassButton";
import { AudioWaveform } from "@/components/ui/AudioWaveform";
import { MicIcon, NavigateNextIcon, RefreshIcon, StopIcon, VolumeUpIcon } from "@/components/icons";
import { PRACTICE_FEEDBACK_KEYS } from "@/lib/scoring";

import { SegmentedTabs } from "@/components/ui/SegmentedTabs";
import { ROLEPLAY_SCENARIOS } from "@/lib/ai/scenarios";
import { settleScrollToEnd, shouldFollowConversation } from "@/lib/ui/scroll-follow";

export default function PracticePage() {
  const [selectedTab, setSelectedTab] = useState<"repetition" | "roleplay">("repetition");
  const { t } = useI18n();

  // Mounted at page level, not inside RoleplayMode: the hook holds the
  // conversation, and unmounting it on every tab switch would silently reset the
  // chat and re-greet the user. Its utterance subscription is gated by `active`,
  // and Repetition gates its own the same way, so exactly one mode listens at a
  // time — both drive the same recogniser singleton.
  const roleplay = useRoleplay({ active: selectedTab === "roleplay" });

  return (
    <div className="flex h-full flex-col">
      {/* Tab Selector */}
      <div className="flex w-full justify-center p-4 border-b border-on-surface/5 bg-background/50 backdrop-blur-md">
        <SegmentedTabs
          value={selectedTab}
          onValueChange={(val) => setSelectedTab(val as "repetition" | "roleplay")}
          ariaLabel={t("nav.practice")}
          tabs={[
            { id: "repetition", label: t("practice.tab"), panelId: "practice-panel-repetition" },
            { id: "roleplay", label: t("roleplay.tab"), panelId: "practice-panel-roleplay" },
          ]}
        />
      </div>

      <div className="flex-1 min-h-0">
        <div
          id="practice-panel-repetition"
          role="tabpanel"
          aria-labelledby="tab-repetition"
          hidden={selectedTab !== "repetition"}
          className={`h-full ${selectedTab === "repetition" ? "" : "hidden"}`}
        >
          <RepetitionMode active={selectedTab === "repetition"} />
        </div>
        <div
          id="practice-panel-roleplay"
          role="tabpanel"
          aria-labelledby="tab-roleplay"
          hidden={selectedTab !== "roleplay"}
          className={`h-full ${selectedTab === "roleplay" ? "" : "hidden"}`}
        >
          <RoleplayMode roleplay={roleplay} />
        </div>
      </div>
    </div>
  );
}

function RepetitionMode({ active }: { active: boolean }) {
  const { t } = useI18n();
  const {
    speechSupported,
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
  } = usePractice({ active });

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
            aria-label={t("action.speak")}
            className="glass-button press-scale flex size-14 shrink-0 items-center justify-center text-azure-glow"
          >
            <VolumeUpIcon className="size-7" />
          </button>
        </div>
      </div>

      {!speechSupported && (
        <div role="status" className="mt-4 rounded-lg bg-surface-variant/40 p-4 text-center text-body-medium text-on-surface-variant">
          {t("practice.speechUnavailable")}
        </div>
      )}

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
          disabled={isProcessing || !speechSupported}
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
        messages, isProcessing, isListening, partialText, speechSupported,
        error, retry, scenario,
        openScenarioIfEmpty, startSession, startListening, stopAndSend, speak, sendMessage
    } = roleplay;

    const [typedText, setTypedText] = useState("");
    const [isSending, setIsSending] = useState(false);
    const scrollRef = useRef<HTMLDivElement>(null);

    /**
     * Whether the reader is at the bottom of the conversation, kept in a ref
     * because the effect below needs its *current* value without re-subscribing.
     */
    const pinnedToBottom = useRef(true);
    /** Only for the button's visibility. */
    const [showJumpToLatest, setShowJumpToLatest] = useState(false);

    /**
     * True while this component itself is moving the viewport.
     *
     * `scroll` events do not say who caused them, so the follow-scroll's own
     * assignment echoed back as a "the user scrolled" event: it fired while the
     * list was between two heights, measured the reader as away from the bottom,
     * cleared the pin, and stopped following. Held for the whole settle sequence
     * and released two frames after it lands — long enough for the echo, far too
     * short for a real scroll of the user's own to be missed.
     */
    const suppressPinUpdate = useRef(false);

    // Open a scene only if there is nothing to come back to.
    useEffect(() => {
        void openScenarioIfEmpty();
    }, [openScenarioIfEmpty]);

    const metricsOf = (el: HTMLDivElement) => ({
        scrollTop: el.scrollTop,
        scrollHeight: el.scrollHeight,
        clientHeight: el.clientHeight,
    });

    const releaseSuppressionAfterEcho = useCallback(() => {
        requestAnimationFrame(() =>
            requestAnimationFrame(() => {
                suppressPinUpdate.current = false;
            })
        );
    }, []);

    /** Brings the conversation's end into view, once the pane has a layout box. */
    const followToEnd = useCallback(() => {
        const el = scrollRef.current;
        if (!el) return;
        suppressPinUpdate.current = true;
        const applied = settleScrollToEnd(el, requestAnimationFrame, releaseSuppressionAfterEcho);
        // If it could not apply within the retry budget the pane is not
        // scrollable at all, so there is no echo to suppress.
        if (!applied) releaseSuppressionAfterEcho();
    }, [releaseSuppressionAfterEcho]);

    const handleScroll = () => {
        const el = scrollRef.current;
        if (!el) return;
        if (suppressPinUpdate.current) return;
        const atBottom = shouldFollowConversation(metricsOf(el));
        pinnedToBottom.current = atBottom;
        if (atBottom) setShowJumpToLatest(false);
    };

    const scrollToLatest = () => {
        pinnedToBottom.current = true;
        setShowJumpToLatest(false);
        followToEnd();
    };

    /**
     * Follow the conversation — but only for a reader who is already at the end
     * of it.
     *
     * This used to pin the list to the bottom whenever the message count, the
     * pending state OR the live transcript changed. The transcript half is what
     * made it unusable: merely opening the microphone scrolled the conversation
     * away from whoever was re-reading an earlier exchange, several times a
     * second, mid-word. Now a scroll only happens for a reader who has not
     * scrolled away, and one who has gets a "jump to latest" affordance instead of
     * losing their place.
     *
     * The decision comes from `pinnedToBottom`, which `handleScroll` keeps up to
     * date from the position the reader *chose*. Re-measuring here would be wrong,
     * not merely redundant: by the time this runs the new message has already grown
     * the list, so a reader who was at the bottom is now 200px away from it and
     * would never be followed again.
     */
    useEffect(() => {
        if (!pinnedToBottom.current) {
            setShowJumpToLatest(true);
            return;
        }
        followToEnd();
        // Deliberately not depending on `partialText`: interim recognition results
        // must never move the viewport.
    }, [messages.length, isProcessing, followToEnd]);

    const handleSendTyped = async (e: React.FormEvent) => {
        e.preventDefault();
        const trimmed = typedText.trim();
        if (!trimmed || isProcessing || isSending) return;
        setTypedText("");
        setIsSending(true);
        try {
            await sendMessage(trimmed);
        } finally {
            setIsSending(false);
        }
    };

    return (
        <div className="flex h-full flex-col p-4">
            <div className="mb-3 flex items-center gap-3">
                <div className="min-w-0 flex-1">
                    <label htmlFor="roleplay-scenario-select" className="text-label-small uppercase tracking-wider text-on-surface-variant block mb-1">
                        {t("roleplay.tab")}
                    </label>
                    <select
                        id="roleplay-scenario-select"
                        value={scenario}
                        onChange={(e) => void startSession(e.target.value)}
                        disabled={isProcessing || isSending}
                        className="w-full bg-surface-variant/50 text-on-surface text-label-large font-bold rounded-lg px-2 py-1 border border-outline-variant/30 focus:outline-none focus:border-azure-glow"
                    >
                        {ROLEPLAY_SCENARIOS.map((s) => (
                            <option key={s.id} value={s.title} className="bg-background text-on-surface">
                                {s.title} ({s.cefrLevel})
                            </option>
                        ))}
                    </select>
                </div>
                <button
                    type="button"
                    onClick={() => void startSession()}
                    disabled={isProcessing || isSending}
                    aria-label={t("roleplay.restart")}
                    title={t("roleplay.restart")}
                    className="glass-button press-scale flex size-11 shrink-0 items-center justify-center text-on-surface-variant transition-colors hover:text-azure-glow disabled:opacity-40 self-end"
                >
                    <RefreshIcon className="size-5" />
                </button>
            </div>

            {error && (
                <div className="mb-2">
                    <ErrorBanner message={error} />
                    <button
                        type="button"
                        onClick={retry}
                        disabled={isProcessing || isSending}
                        className="text-xs text-primary underline hover:text-azure-glow ml-1"
                    >
                        {t("action.retry") || "Retry"}
                    </button>
                </div>
            )}
            <div
                ref={scrollRef}
                onScroll={handleScroll}
                role="log"
                aria-live="polite"
                aria-label={t("roleplay.tab")}
                className="flex-1 overflow-y-auto space-y-4 pb-4"
            >
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
                                <button
                                    type="button"
                                    onClick={() => speak(msg.content)}
                                    aria-label={t("action.speak")}
                                    className="mt-2 text-primary press-scale"
                                >
                                    <VolumeUpIcon className="size-4" />
                                </button>
                            )}
                        </div>
                    </div>
                ))}
                {(isProcessing || isSending) && (
                    <div className="flex justify-start">
                        <div className="glass-surface p-4 opacity-50 animate-pulse">{t("roleplay.thinking")}</div>
                    </div>
                )}
            </div>

            {/* Offered only when the reader has scrolled away from the end — the
                same condition that stops the list from following on its own. */}
            {showJumpToLatest && (
                <button
                    type="button"
                    onClick={scrollToLatest}
                    className="glass-button press-scale mx-auto flex min-h-11 w-fit items-center gap-2 px-4 text-xs font-bold text-azure-glow shadow-lg"
                >
                    {t("roleplay.jumpToLatest")}
                </button>
            )}

            <div className="glass-surface p-4 sm:p-6 flex flex-col items-center gap-3 shadow-xl">
                {!speechSupported && (
                    <div role="status" className="w-full rounded-lg bg-surface-variant/40 p-2 text-center text-xs text-on-surface-variant">
                        {t("roleplay.voiceUnavailable")}
                    </div>
                )}

                {speechSupported && isListening && partialText && (
                    <p className="text-sm text-on-surface text-center animate-in fade-in slide-in-from-bottom-2">
                        {partialText}
                    </p>
                )}

                {speechSupported && (
                    <div className="flex flex-col items-center gap-2">
                        <div className="relative group">
                            {isListening && (
                                <div className="absolute inset-0 bg-primary/20 rounded-full animate-ping scale-150" />
                            )}
                            <button
                                type="button"
                                onClick={isListening ? stopAndSend : startListening}
                                disabled={isProcessing || isSending}
                                aria-label={isListening ? t("roleplay.stopSend") : t("roleplay.speakReply")}
                                className={`relative z-10 size-16 rounded-full flex items-center justify-center transition-all ${
                                    isListening ? 'bg-error scale-110 shadow-error/20' : 'bg-primary shadow-primary/20'
                                } shadow-2xl hover:scale-105 active:scale-95 disabled:opacity-50`}
                            >
                                {isListening ? <StopIcon className="size-8 text-white" /> : <MicIcon className="size-8 text-white" />}
                            </button>
                        </div>
                        <span className="text-xs font-bold uppercase tracking-wider text-on-surface-variant/80">
                            {isListening ? t("roleplay.stopSend") : t("roleplay.speakReply")}
                        </span>
                    </div>
                )}

                {/* Typed interaction fallback & complement */}
                <form onSubmit={handleSendTyped} className="flex w-full max-w-lg items-center gap-2 mt-1">
                    <input
                        type="text"
                        value={typedText}
                        onChange={(e) => setTypedText(e.target.value)}
                        placeholder={t("roleplay.typePlaceholder")}
                        disabled={isProcessing || isSending}
                        className="flex-1 rounded-full border border-outline-variant bg-glass-fill px-4 py-2.5 text-body-medium text-on-surface placeholder:text-on-surface-variant/50 focus:outline-none focus:border-azure-glow"
                    />
                    <GlassButton
                        type="submit"
                        disabled={!typedText.trim() || isProcessing || isSending}
                        className="px-5 py-2 text-label-large font-bold shrink-0"
                    >
                        {t("roleplay.send")}
                    </GlassButton>
                </form>
            </div>
        </div>
    );
}
