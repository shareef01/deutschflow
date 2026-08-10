package com.aus.deutschflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for [condition], on real time, and reports whether it came true.
 *
 * Instrumented tests cannot borrow the JVM trick of swapping the Main dispatcher
 * for a test one: kotlinx-coroutines-android resolves Dispatchers.Main through its
 * own fast service loader, which never sees TestMainDispatcherFactory, so
 * Dispatchers.setMain throws "TestMainDispatcher is not set as main dispatcher".
 * Three test classes here were written in the JVM idiom and every one of them
 * failed in @Before for that reason, with the teardown error on top hiding it.
 *
 * So the work is left on the real main looper - which is what the ViewModel does in
 * the app anyway - and the test waits for it. Returns rather than throws on timeout,
 * so the assertion that follows can report the actual value instead of a stack trace
 * about waiting.
 */
suspend fun awaitCondition(
    timeoutMs: Long = 5_000,
    pollMs: Long = 25,
    condition: suspend () -> Boolean
): Boolean = withTimeoutOrNull(timeoutMs) {
    while (!condition()) delay(pollMs)
    true
} ?: false
