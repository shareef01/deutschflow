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
      <SegmentedTabs
        value={selectedTab}
        onValueChange={(val) => setSelectedTab(val as "repetition" | "roleplay")}
        ariaLabel={t("nav.practice")}
        tabs={[
          { id: "repetition", label: t("practice.tab"), panelId: "practice-panel-repetition" },
          { id: "roleplay", label: t("roleplay.tab"), panelId: "practice-panel-roleplay" },
        ]}
      />

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
    <div className="mx-auto flex h-full w-full max-w-[var(--container-workspace)] flex-col overflow-y-auto px-[var(--gutter)] py-[var(--space-5)]">
      {/* Step 1: listen. The sentence is the material, so it gets the size. */}
      <section aria-labelledby="practice-listen" className="glass-surface p-5 sm:p-6">
        <div className="flex items-center justify-between gap-3">
          <h2 id="practice-listen" className="eyebrow">
            <span aria-hidden="true" className="mr-2 text-primary">1</span>
            {t("practice.listenRepeat")}
          </h2>
          <GlassButton
            type="button"
            onClick={() => speak(targetSentence)}
            className="h-11 shrink-0 px-4"
          >
            <span className="flex items-center gap-2 text-label-large font-bold text-primary">
              <VolumeUpIcon className="size-5" />
              {t("practice.listen")}
            </span>
          </GlassButton>
        </div>
        <p lang="de" className="mt-4 break-words text-headline-small text-on-surface sm:text-headline-medium">
          {wordResults.length === 0
            ? targetSentence
            : wordResults.map((result, i) => (
                // Colour plus an underline, so a missed word does not depend on
                // telling red from cyan.
                <span
                  key={`${result.word}-${i}`}
                  className={
                    result.isCorrect
                      ? "text-tertiary"
                      : "text-error underline decoration-2 underline-offset-4"
                  }
                >
                  {result.word}{" "}
                </span>
              ))}
        </p>
      </section>

      {!speechSupported && (
        <div role="status" className="mt-4 rounded-[var(--radius-sm)] bg-surface-container-high p-4 text-body-medium text-on-surface-variant">
          {t("practice.speechUnavailable")}
        </div>
      )}

      {errorState && (
        <div className="mt-4">
          <ErrorBanner message={errorState} />
        </div>
      )}

      {/* Step 2: speak, and what the recogniser made of it. */}
      <section aria-labelledby="practice-speak" className="glass-surface mt-4 min-h-[9rem] p-5 sm:p-6" aria-live="polite">
        <h2 id="practice-speak" className="eyebrow">
          <span aria-hidden="true" className="mr-2 text-primary">2</span>
          {t("practice.speak")}
        </h2>
        {isListening ? (
          <div className="mt-3">
            <p className="min-h-6 text-body-large text-on-surface">{partialText || t("transcript.listening")}</p>
            <AudioWaveform getLevel={() => rmsLevel} isActive className="mt-4 h-14 w-full" />
          </div>
        ) : isProcessing ? (
          <div className="flex min-h-[96px] w-full items-center justify-center">
            <span className="size-8 animate-spin rounded-full border-[3px] border-azure-glow border-t-transparent" />
          </div>
        ) : spokenText.length === 0 ? (
          <p className="mt-3 max-w-[60ch] text-body-medium text-on-surface-variant">{t("practice.intro")}</p>
        ) : (
          <div className="mt-3 flex flex-col items-start gap-3">
            {feedbackText && (
              <span
                className={`rounded-full px-4 py-1.5 text-label-large ${
                  isPositive
                    ? "bg-tertiary-container text-on-tertiary-container"
                    : "bg-warning-container text-on-warning-container"
                }`}
              >
                {feedbackText}
              </span>
            )}
            <p lang="de" className="text-body-large text-on-surface">{spokenText}</p>
            {/* Recognition, not pronunciation: the browser returns a transcript,
                and its language model will happily resolve poor audio into the
                word that was meant. Said every time a result is shown. */}
            {wordResults.length > 0 && (
              <p className="text-label-medium text-on-surface-variant">{t("practice.feedbackCaption")}</p>
            )}
          </div>
        )}
      </section>

      <div className="mt-5 flex w-full flex-col gap-3 pb-2 xs:flex-row">
        <GlassButton
          type="button"
          disabled={isProcessing || !speechSupported}
          glow={isListening ? "error" : "solid"}
          onClick={isListening ? stopPractice : () => void startPractice()}
          className="h-14 w-full xs:flex-[2]"
        >
          <span className="flex items-center justify-center gap-2">
            {isListening ? <StopIcon className="size-5" /> : <MicIcon className="size-5" />}
            <span className="text-label-large font-bold">
              {isListening ? t("practice.evaluate") : t("practice.speak")}
            </span>
          </span>
        </GlassButton>
        <GlassButton type="button" onClick={nextSentence} className="h-14 w-full xs:flex-1">
          <span className="flex items-center justify-center gap-2">
            <NavigateNextIcon className="size-5" />
            <span className="text-label-large font-bold">{t("practice.next")}</span>
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

    const current = ROLEPLAY_SCENARIOS.find((s) => s.title === scenario) ?? ROLEPLAY_SCENARIOS[0];
    const busy = isProcessing || isSending;

    return (
        <div className="mx-auto flex h-full w-full max-w-[var(--container-workspace)] flex-col px-[var(--gutter)] pt-[var(--space-4)]">
            {/* The scene: which one, where you are, and what you are trying to do. */}
            <div className="flex items-end gap-2">
                <div className="min-w-0 flex-1">
                    <label htmlFor="roleplay-scenario-select" className="eyebrow mb-1.5 block">
                        {t("roleplay.scenario")}
                    </label>
                    <select
                        id="roleplay-scenario-select"
                        value={scenario}
                        onChange={(e) => void startSession(e.target.value)}
                        disabled={busy}
                        className="h-11 w-full rounded-[var(--radius-sm)] border border-outline-variant bg-glass-fill px-3 text-label-large text-on-surface focus:border-azure-glow focus:outline-none"
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
                    disabled={busy}
                    aria-label={t("roleplay.restart")}
                    title={t("roleplay.restart")}
                    className="glass-button press-scale flex size-11 shrink-0 items-center justify-center text-on-surface-variant transition-colors hover:text-primary disabled:opacity-40"
                >
                    <RefreshIcon className="size-5" />
                </button>
            </div>
            <details className="group mt-2 rounded-[var(--radius-sm)] border border-outline-variant/70 px-3 py-2">
                <summary className="flex min-h-7 cursor-pointer list-none items-center justify-between gap-2 text-body-medium text-on-surface-variant">
                    <span className="min-w-0 truncate">{current.context}</span>
                    <span className="shrink-0 text-label-medium text-primary">{t("roleplay.yourGoals")}</span>
                </summary>
                <ul className="mt-2 list-disc space-y-1 pl-5 text-body-medium text-on-surface">
                    {current.learnerGoals.map((goal) => (
                        <li key={goal}>{goal}</li>
                    ))}
                </ul>
            </details>

            <div
                ref={scrollRef}
                onScroll={handleScroll}
                role="log"
                aria-live="polite"
                aria-label={t("roleplay.tab")}
                className="mt-3 flex-1 space-y-3 overflow-y-auto pb-4"
            >
                {messages.map((msg, i) => (
                    <div key={i} className={`flex w-full ${msg.role === "user" ? "justify-end" : "justify-start"}`}>
                        <div
                            className={`max-w-[85%] rounded-[var(--radius-lg)] px-4 py-3 sm:max-w-[70%] ${
                                msg.role === "user"
                                    ? "rounded-br-[var(--radius-xs)] bg-primary-container text-on-primary-container"
                                    : "glass-surface rounded-bl-[var(--radius-xs)]"
                            }`}
                        >
                            <p lang="de" className={`text-body-large ${msg.role === "user" ? "" : "text-on-surface"}`}>
                                {msg.content}
                            </p>
                            {msg.translation && (
                                <p className="mt-1.5 text-body-medium text-on-surface-variant">{msg.translation}</p>
                            )}
                            {msg.role === "assistant" && (
                                <button
                                    type="button"
                                    onClick={() => speak(msg.content)}
                                    aria-label={t("action.speak")}
                                    title={t("action.speak")}
                                    className="press-scale -mb-1 -ml-2 mt-1 grid size-9 place-items-center rounded-full text-primary hover:bg-primary/10"
                                >
                                    <VolumeUpIcon className="size-5" />
                                </button>
                            )}
                        </div>
                    </div>
                ))}
                {busy && (
                    <div className="flex justify-start" role="status">
                        <div className="glass-surface flex items-center gap-2 rounded-bl-[var(--radius-xs)] px-4 py-3 text-body-medium text-on-surface-variant">
                            <span aria-hidden="true" className="flex gap-1">
                                <span className="size-1.5 animate-pulse rounded-full bg-on-surface-variant" />
                                <span className="size-1.5 animate-pulse rounded-full bg-on-surface-variant [animation-delay:150ms]" />
                                <span className="size-1.5 animate-pulse rounded-full bg-on-surface-variant [animation-delay:300ms]" />
                            </span>
                            {t("roleplay.thinking")}
                        </div>
                    </div>
                )}
            </div>

            {/* Offered only when the reader has scrolled away from the end — the
                same condition that stops the list from following on its own. */}
            {showJumpToLatest && (
                <button
                    type="button"
                    onClick={scrollToLatest}
                    className="glass-button press-scale mx-auto mb-2 flex min-h-11 w-fit items-center gap-2 px-4 text-label-large font-bold text-primary shadow-[var(--shadow-raised)]"
                >
                    {t("roleplay.jumpToLatest")}
                </button>
            )}

            {error && (
                <div className="mb-2 flex flex-wrap items-start gap-2">
                    <div className="min-w-0 flex-1">
                        <ErrorBanner message={error} />
                    </div>
                    <GlassButton type="button" onClick={retry} disabled={busy} className="h-11 px-4">
                        <span className="flex items-center gap-2 text-label-large font-bold">
                            <RefreshIcon className="size-4" />
                            {t("action.retry")}
                        </span>
                    </GlassButton>
                </div>
            )}

            {/* The composer: one row, mic first. It used to be a card a third of
                the phone's height, with a 64px solid button that pinged. */}
            <div className="border-t border-outline-variant/60 pb-[max(var(--space-4),env(safe-area-inset-bottom))] pt-3">
                {!speechSupported && (
                    <p role="status" className="mb-2 text-label-medium text-on-surface-variant">
                        {t("roleplay.voiceUnavailable")}
                    </p>
                )}
                {speechSupported && isListening && (
                    <p className="mb-2 flex items-center gap-2 text-body-medium text-on-surface" aria-live="polite">
                        <span aria-hidden="true" className="size-2 shrink-0 animate-pulse rounded-full bg-error" />
                        {partialText || t("transcript.listening")}
                    </p>
                )}
                <form onSubmit={handleSendTyped} className="flex items-center gap-2">
                    {speechSupported && (
                        <button
                            type="button"
                            onClick={isListening ? stopAndSend : startListening}
                            disabled={busy}
                            aria-label={isListening ? t("roleplay.stopSend") : t("roleplay.speakReply")}
                            title={isListening ? t("roleplay.stopSend") : t("roleplay.speakReply")}
                            className={`press-scale grid size-12 shrink-0 place-items-center rounded-full transition-colors disabled:opacity-50 ${
                                isListening
                                    ? "bg-error-container text-on-error-container ring-2 ring-error"
                                    : "bg-primary text-background hover:brightness-110"
                            }`}
                        >
                            {isListening ? <StopIcon className="size-6" /> : <MicIcon className="size-6" />}
                        </button>
                    )}
                    <input
                        type="text"
                        lang="de"
                        value={typedText}
                        onChange={(e) => setTypedText(e.target.value)}
                        placeholder={t("roleplay.typePlaceholder")}
                        aria-label={t("roleplay.typePlaceholder")}
                        disabled={busy}
                        className="h-12 min-w-0 flex-1 rounded-full border border-outline-variant bg-glass-fill px-4 text-body-large text-on-surface placeholder:text-on-surface-variant focus:border-azure-glow focus:outline-none"
                    />
                    <GlassButton
                        type="submit"
                        glow="solid"
                        disabled={!typedText.trim() || busy}
                        className="h-12 shrink-0 px-5"
                    >
                        <span className="text-label-large font-bold">{t("roleplay.send")}</span>
                    </GlassButton>
                </form>
            </div>
        </div>
    );
}
