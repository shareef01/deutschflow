import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { clearConversation, db, loadConversation, saveConversationTurn } from "@/lib/db";
import { getApiKey, getAutoPlay, getCefrLevel } from "@/lib/db/settings";
import { startRoleplay, continueRoleplay } from "@/lib/ai/groq";
import { DEFAULT_SCENARIO_TITLE } from "@/lib/ai/scenarios";
import { t } from "@/lib/i18n";
import { recognizer, isRecognitionSupported, type RecognizerState } from "@/lib/speech/recognizer";
import { resolveRecognitionDialect } from "@/lib/speech/dialect";
import { tts } from "@/lib/speech/tts";

export interface ChatMessage {
    role: "user" | "assistant";
    content: string;
    translation?: string;
}

const DEFAULT_SCENARIO = DEFAULT_SCENARIO_TITLE;

/**
 * How long after "stop" a new recording is refused, so the finished utterance can
 * still be delivered by the engine's `onend`. Comfortably longer than a healthy
 * engine needs, and short enough that a throttled background tab cannot strand the
 * microphone. See [useRoleplay]'s startListening.
 */
const STOP_GRACE_MS = 2_000;

const SERVER_RECOGNIZER_STATE: RecognizerState = {
    partialText: "",
    finalText: "",
    isListening: false,
    isProcessing: false,
    errorState: null,
    rmsLevel: 0,
};

/**
 * useRoleplay — RoleplayViewModel port.
 *
 * Turns arrive as completed utterances through `recognizer.onUtterance`, the
 * same subscription the Transcript screen uses. Reading `finalText` off the
 * state instead would replay the previous turn whenever a recognition failed,
 * because that field keeps the last utterance until a new session starts.
 *
 * `active` gates the utterance subscription. The Practice page keeps this hook
 * mounted across its two tabs so the conversation survives a tab switch (the
 * Android ViewModel does), but Repetition subscribes to the same singleton —
 * with both subscribed, one spoken sentence would be scored *and* sent as a
 * roleplay turn. Only the active mode may listen.
 */
export function useRoleplay({ active = true }: { active?: boolean } = {}) {
    const speechSupported = useSyncExternalStore(
        () => () => {},
        isRecognitionSupported,
        () => false
    );

    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [isProcessing, setIsProcessing] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [scenario, setScenario] = useState(DEFAULT_SCENARIO);

    const recognizerState = useSyncExternalStore(
        recognizer.subscribe,
        recognizer.getSnapshot,
        () => SERVER_RECOGNIZER_STATE
    );

    /**
     * The turn in flight, read and written synchronously.
     *
     * `isProcessing` alone cannot guard re-entry: two taps in the same tick
     * both see the old state before either re-render lands.
     */
    const inFlight = useRef(false);

    /** The turn in flight, so a replaced scene or a closed screen can cancel it. */
    const turnAbort = useRef<AbortController | null>(null);

    /** The scenario the next turn belongs to, for callbacks that outlive a render. */
    const scenarioRef = useRef(scenario);
    scenarioRef.current = scenario;

    /**
     * The conversation as the model should see it. Kept in a ref as well as in
     * state because the utterance subscription is registered once and would
     * otherwise send an empty history on every turn after the first.
     */
    const historyRef = useRef<ChatMessage[]>([]);

    /**
     * Saves one turn, at the index it occupies in the conversation.
     *
     * The chat used to live in React state alone, so a reload lost it - on the one
     * screen where the user stops to compose a German sentence, and where what is
     * lost is the model's half of a conversation rather than anything they could
     * retype. Mirrors RoleplayViewModel.persist.
     */
    const persist = useCallback((message: ChatMessage, position: number) => {
        void saveConversationTurn(db, {
            position,
            scenario: scenarioRef.current,
            role: message.role,
            content: message.content,
            translation: message.translation,
            timestamp: Date.now(),
        });
    }, []);

    const runOpening = useCallback(async () => {
        if (inFlight.current) return;
        inFlight.current = true;
        setIsProcessing(true);
        setError(null);

        // The turn's cancellation. A conversation that has been replaced, or a screen
        // the user has left, must not keep a request alive - and must not persist or
        // speak the answer it eventually gets. That is how a reply used to arrive
        // thirty seconds later, be read aloud over whatever was on screen by then, and
        // be written into a conversation that had already been cleared.
        turnAbort.current?.abort();
        const controller = new AbortController();
        turnAbort.current = controller;

        try {
            const apiKey = (await getApiKey(db)) ?? "";
            const cefr = await getCefrLevel(db);
            const result = await startRoleplay(scenarioRef.current, [], apiKey, cefr, controller.signal);

            if (controller.signal.aborted) return;
            if (result.kind === "success") {
                const reply: ChatMessage = {
                    role: "assistant",
                    content: result.aiResponse,
                    translation: result.englishContext,
                };
                historyRef.current = [reply];
                setMessages(historyRef.current);
                persist(reply, 0);
                if (await getAutoPlay(db)) tts.speak(result.aiResponse);
            } else {
                setError(result.message);
            }
        } catch {
            setError(t("ai.noKey"));
        } finally {
            inFlight.current = false;
            setIsProcessing(false);
        }
    }, [persist]);

    const runTurn = useCallback(async (userInput: string, appendUser = true) => {
        if (inFlight.current) return;
        const trimmed = userInput.trim();
        if (!trimmed && appendUser) {
            return;
        }
        inFlight.current = true;
        setIsProcessing(true);
        setError(null);

        // See runOpening: one turn in flight, and superseding or leaving cancels it.
        turnAbort.current?.abort();
        const controller = new AbortController();
        turnAbort.current = controller;

        // Taken before the optimistic append, so the model is not shown the turn
        // it is being asked to answer. On a retry the message is already in the
        // history, so it is excluded here instead.
        const sent = appendUser ? historyRef.current : historyRef.current.slice(0, -1);
        const history = sent.map((m) => ({ role: m.role, content: m.content }));

        if (appendUser) {
            const userMessage: ChatMessage = { role: "user", content: trimmed };
            historyRef.current = [...historyRef.current, userMessage];
            setMessages(historyRef.current);
            persist(userMessage, historyRef.current.length - 1);
        }

        try {
            const apiKey = (await getApiKey(db)) ?? "";
            const cefr = await getCefrLevel(db);
            const inputToSend = appendUser
                ? trimmed
                : (historyRef.current[historyRef.current.length - 1]?.content ?? trimmed);
            const result = await continueRoleplay(inputToSend, history, scenarioRef.current, apiKey, cefr, controller.signal);

            if (controller.signal.aborted) return;

            if (result.kind === "success") {
                const reply: ChatMessage = {
                    role: "assistant",
                    content: result.aiResponse,
                    translation: result.englishContext,
                };
                historyRef.current = [...historyRef.current, reply];
                setMessages(historyRef.current);
                persist(reply, historyRef.current.length - 1);
                if (await getAutoPlay(db)) tts.speak(result.aiResponse);
            } else {
                setError(result.message);
            }
        } catch {
            setError(t("ai.noKey"));
        } finally {
            inFlight.current = false;
            setIsProcessing(false);
        }
    }, [persist]);

    /** Subscribes only while this mode is on screen; see `active` above. */
    useEffect(() => {
        if (!active) return;
        return recognizer.onUtterance((text) => {
            void runTurn(text);
        });
    }, [active, runTurn]);

    /**
     * The microphone belongs to the recogniser singleton. Losing the foreground —
     * tab switch away from the active mode, or Practice unmounting entirely — used
     * to leave it open, so the next spoken sentence went to whatever screen was
     * listening now. RepetitionMode applies the same discipline on its own unmount.
     */
    useEffect(() => {
        if (!active) {
            recognizer.cancel();
            // The mode is off screen: an answer that arrives now would be spoken and
            // persisted under a conversation nobody is looking at.
            turnAbort.current?.abort();
        }
    }, [active]);
    useEffect(
        () => () => {
            recognizer.cancel();
            turnAbort.current?.abort();
        },
        []
    );

    const startSession = useCallback(
        async (newScenario?: string) => {
            const activeScenario = newScenario ?? scenarioRef.current;
            // A new scene cancels the old one's turn before the rows are cleared, so a
            // late answer cannot be persisted into a conversation that no longer exists.
            turnAbort.current?.abort();
            setScenario(activeScenario);
            scenarioRef.current = activeScenario;
            historyRef.current = [];
            setMessages([]);
            await clearConversation(db);
            await runOpening();
        },
        [runOpening]
    );

    /**
     * Reads the saved conversation back, once per mount.
     *
     * Memoised as a promise rather than a boolean: `openScenarioIfEmpty` has to
     * *wait* for it, and a flag would leave the caller reading `messages` and
     * "have we restored yet" as two independently-timed pieces of state.
     */
    const restored = useRef<Promise<void> | null>(null);
    const restore = useCallback(() => {
        restored.current ??= (async () => {
            const saved = await loadConversation(db);
            if (saved.length === 0 || historyRef.current.length > 0) return;
            historyRef.current = saved.map((m) => ({
                role: m.role,
                content: m.content,
                translation: m.translation,
            }));
            setMessages(historyRef.current);
            setScenario(saved[0].scenario);
            scenarioRef.current = saved[0].scenario;
        })();
        return restored.current;
    }, []);

    useEffect(() => {
        void restore();
    }, [restore]);

    /**
     * Opens [scenario] only if there is nothing to come back to.
     *
     * The component cannot make this call itself: it mounts before the restore
     * has finished, sees an empty list, and would start a new scene over the one
     * the user left. Mirrors RoleplayViewModel.openScenarioIfEmpty.
     */
    const openScenarioIfEmpty = useCallback(
        async (newScenario?: string) => {
            await restore();
            if (historyRef.current.length === 0 && !inFlight.current) {
                await startSession(newScenario);
            }
        },
        [restore, startSession]
    );

    const isStarting = useRef(false);

    /**
     * When "stop" was last tapped, so the utterance-delivery window can be bounded.
     *
     * See startListening: the guard there must not last longer than the engine needs
     * to close the session, or a browser that never fires `onend` (a backgrounded
     * tab throttles it indefinitely) would leave the mic un-armable.
     */
    const stopRequestedAt = useRef(0);

    const startListening = useCallback(async () => {
        // A tap between "stop" and the engine's `onend` must not start a new
        // session: starting one detaches the old engine (recognizer.detach), and
        // `onend` is where the finished utterance is delivered — so the sentence
        // the user just spoke would be dropped with no error and no turn sent.
        // `isProcessing` on the recogniser is exactly that window; the button is
        // only disabled while a *network* turn is out, which is a different thing.
        // Bounded, because the window is the engine's to close: after
        // STOP_GRACE_MS the mic works again even if `onend` never came.
        const state = recognizer.getSnapshot();
        const closingUtterance =
            state.isProcessing &&
            stopRequestedAt.current > 0 &&
            Date.now() - stopRequestedAt.current < STOP_GRACE_MS;
        if (isStarting.current || state.isListening || closingUtterance) return;
        isStarting.current = true;
        setError(null);
        try {
            const granted = await recognizer.requestMicrophonePermission();
            if (!granted) {
                recognizer.reportPermissionDenied();
                return;
            }
            const dialect = await resolveRecognitionDialect();
            recognizer.startListening(dialect);
        } catch {
            recognizer.reportPermissionDenied();
        } finally {
            isStarting.current = false;
        }
    }, []);

    /** Ends the utterance; `onUtterance` above delivers it and sends the turn. */
    const stopAndSend = useCallback(() => {
        stopRequestedAt.current = Date.now();
        recognizer.stopListening();
    }, []);

    /**
     * Re-sends the turn that failed. The opening line has no user message, so an
     * empty history retries the greeting — which is the case worth recovering,
     * since a failed greeting otherwise leaves the screen permanently blank.
     */
    const retry = useCallback(() => {
        const last = historyRef.current[historyRef.current.length - 1];
        if (last?.role === "user") {
            void runTurn(last.content, false);
        } else {
            void runOpening();
        }
    }, [runTurn, runOpening]);

    return {
        speechSupported,
        messages,
        isProcessing,
        error,
        scenario,
        isListening: recognizerState.isListening,
        partialText: recognizerState.partialText,
        errorState: recognizerState.errorState ?? error,
        startSession,
        openScenarioIfEmpty,
        startListening,
        stopAndSend,
        retry,
        sendMessage: async (text: string) => {
            const trimmed = text.trim();
            if (trimmed) await runTurn(trimmed);
        },
        speak: (text: string) => tts.speak(text),
    };
}
