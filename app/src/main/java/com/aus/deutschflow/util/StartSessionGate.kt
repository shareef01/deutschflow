package com.aus.deutschflow.util

/**
 * Single-flight gate for microphone-started sessions (Practice / Roleplay).
 *
 * The recogniser's isListening flag flips true only when its handler thread reports
 * onReadyForSpeech - after startListening() returns, and possibly after a suspending
 * dialect read (preferenceManager.selectedDialect.first()). Between tryStart() and the
 * recogniser committing, a second startPractice()/startListening() would hand the
 * recogniser a second session it cannot serve. This gate is consumed by the first
 * start() and only re-armed on a terminal path: the recogniser committed (isListening
 * or isProcessing went true), it errored, or the screen was left/abandoned.
 */
class StartSessionGate {
    private var pending = false

    /** true if a start was consumed and not yet terminal; false lets a start through. */
    fun tryStart(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    /** Re-arms the gate on a terminal path (committed, errored, or abandoned). */
    fun release() { pending = false }
}
