import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { db } from "@/lib/db";
import { getApiKey, getDialect } from "@/lib/db/settings";
import { processRoleplay } from "@/lib/ai/groq";
import { t } from "@/lib/i18n";
import { recognizer, type RecognizerState } from "@/lib/speech/recognizer";
import { tts } from "@/lib/speech/tts";

export interface ChatMessage {
    role: "user" | "assistant";
    content: string;
    translation?: string;
}

const DEFAULT_SCENARIO = "Ordering at a Berlin Bakery";

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
export function useRoleplay(options?: { active?: boolean }) {
    const active = options?.active ?? true;
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

    /** The scenario the next turn belongs to, for callbacks that outlive a render. */
    const scenarioRef = useRef(scenario);
    scenarioRef.current = scenario;

    /**
     * The conversation as the model should see it. Kept in a ref as well as in
     * state because the utterance subscription is registered once and would
     * otherwise send an empty history on every turn after the first.
     */
    const historyRef = useRef<ChatMessage[]>([]);

    const runTurn = useCallback(async (userInput: string, appendUser = true) => {
        if (inFlight.current) return;
        inFlight.current = true;
        setIsProcessing(true);
        setError(null);

        // Taken before the optimistic append, so the model is not shown the turn
        // it is being asked to answer. On a retry the message is already in the
        // history, so it is excluded here instead.
        const sent = appendUser ? historyRef.current : historyRef.current.slice(0, -1);
        const history = sent.map((m) => ({ role: m.role, content: m.content }));

        if (appendUser && userInput.trim()) {
            const userMessage: ChatMessage = { role: "user", content: userInput };
            historyRef.current = [...historyRef.current, userMessage];
            setMessages(historyRef.current);
        }

        try {
            const apiKey = (await getApiKey(db)) ?? "";
            const result = await processRoleplay(userInput, history, scenarioRef.current, apiKey);

            if (result.kind === "success") {
                const reply: ChatMessage = {
                    role: "assistant",
                    content: result.aiResponse,
                    translation: result.englishContext,
                };
                historyRef.current = [...historyRef.current, reply];
                setMessages(historyRef.current);
                tts.speak(result.aiResponse);
            } else {
                // Shown in the chat rather than swallowed: a failed opening turn
                // used to leave the screen blank forever, with nothing to retry.
                setError(result.message);
            }
        } catch {
            // The AI layer converts its own fetch failures into results, but a
            // throw from the vault reading the key — or from the TTS engine —
            // used to escape as an unhandled rejection and reset the spinner
            // with no word of explanation.
            setError(t("ai.noKey"));
        } finally {
            inFlight.current = false;
            setIsProcessing(false);
        }
    }, []);

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
        if (!active) recognizer.cancel();
    }, [active]);
    useEffect(() => () => recognizer.cancel(), []);

    const startSession = useCallback(
        async (newScenario?: string) => {
            const activeScenario = newScenario ?? scenario;
            setScenario(activeScenario);
            scenarioRef.current = activeScenario;
            historyRef.current = [];
            setMessages([]);
            // An empty input is the trigger for the model's opening line.
            await runTurn("");
        },
        [runTurn, scenario]
    );

    const startListening = useCallback(async () => {
        setError(null);
        const granted = await recognizer.requestMicrophonePermission();
        if (!granted) {
            recognizer.reportPermissionDenied();
            return;
        }
        const dialect = await getDialect(db);
        recognizer.startListening(dialect);
    }, []);

    /** Ends the utterance; `onUtterance` above delivers it and sends the turn. */
    const stopAndSend = useCallback(() => recognizer.stopListening(), []);

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
            void runTurn("");
        }
    }, [runTurn]);

    return {
        messages,
        isProcessing,
        error,
        scenario,
        isListening: recognizerState.isListening,
        partialText: recognizerState.partialText,
        errorState: recognizerState.errorState ?? error,
        startSession,
        startListening,
        stopAndSend,
        retry,
        speak: (text: string) => tts.speak(text),
    };
}
