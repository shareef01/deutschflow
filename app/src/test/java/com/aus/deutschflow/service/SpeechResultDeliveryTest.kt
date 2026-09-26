package com.aus.deutschflow.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the utterance-delivery contract of [UtteranceDeliveryBus]:
 *
 * - a completed utterance is never silently lost, even when the flow's buffer
 *   slot is taken and the collector is still busy with the previous one
 *   (the old `tryEmit`-and-forget path dropped exactly that utterance)
 * - delivery is exactly-once and in publish order, including bursts larger
 *   than the buffer
 * - [UtteranceDeliveryBus.publish] never blocks the calling thread (it is
 *   invoked from a RecognitionListener on the main thread)
 * - the drainer falling behind is reported through the overflow callback,
 *   never silent
 * - [UtteranceDeliveryBus.close] cancels parked deliveries (destroy() path)
 *
 * Collectors are started on [UnconfinedTestDispatcher] so they are subscribed
 * before the first publish - on the default StandardTestDispatcher the launch
 * would not run until the test body suspends, and every publish would land on
 * a flow with zero subscribers. The bus itself gets the same dispatcher so the
 * drainer's work is deterministic rather than racing a real thread pool.
 *
 * Pure JVM: the bus holds no Android types, same seam pattern as
 * [SpeechCapabilityDeciderTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechResultDeliveryTest {

    private fun TestScope.newBus(onOverflow: (String) -> Unit = {}) =
        UtteranceDeliveryBus(onOverflow, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `second utterance completing while collector is busy is delivered, not dropped`() = runTest {
        val bus = newBus()
        val received = mutableListOf<String>()
        // Gate the collector: it holds the first utterance until we let go,
        // which is exactly the "busy translating over the network" window in
        // which the old tryEmit path lost the second utterance.
        val firstReceived = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.results.collect { text ->
                received.add(text)
                if (text == "eins") {
                    firstReceived.complete(Unit)
                    releaseFirst.await()
                }
            }
        }

        bus.publish("eins")
        firstReceived.await()
        // The collector is parked on "eins": "zwei" fills the buffer slot and
        // "drei" parks the drainer mid-emit - the old code dropped "drei" here.
        bus.publish("zwei")
        bus.publish("drei")

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("eins", "zwei", "drei"), received)
        collector.cancelAndJoin()
        bus.close()
    }

    @Test
    fun `burst of completions is all delivered in order exactly once`() = runTest {
        val bus = newBus()
        val received = mutableListOf<String>()
        val utterances = (1..20).map { "satz-$it" }

        // A collector that is consistently slower than the producer, so the
        // buffer is under contention for most of the burst.
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.results.collect { text ->
                delay(1)
                received.add(text)
            }
        }

        utterances.forEach { bus.publish(it) }
        advanceUntilIdle()

        assertEquals("every utterance delivered exactly once, in order", utterances, received)
        assertEquals("no duplicates", received.size, received.toSet().size)
        collector.cancelAndJoin()
        bus.close()
    }

    @Test
    fun `publish never blocks the calling thread under contention`() = runTest {
        val bus = newBus()
        val received = ConcurrentLinkedQueue<String>()
        // Collector that stays busy long enough to force parking.
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.results.collect { text ->
                delay(50)
                received.add(text)
            }
        }

        // Publish from a separate thread to prove the caller (main thread in
        // production) is back immediately even when the buffer is full.
        val done = CountDownLatch(1)
        val publisher = Thread {
            repeat(5) { bus.publish("wort-$it") }
            done.countDown()
        }
        publisher.start()

        assertTrue(
            "publish must return immediately even with a full buffer",
            done.await(1, TimeUnit.SECONDS)
        )
        publisher.join(1_000)

        advanceUntilIdle()
        assertEquals(5, received.size)
        collector.cancelAndJoin()
        bus.close()
    }

    @Test
    fun `the drainer falling behind is reported loudly, never silent`() = runTest {
        val overflows = mutableListOf<String>()
        val bus = newBus(onOverflow = { overflows.add(it) })
        val received = mutableListOf<String>()
        val holdCollector = CompletableDeferred<Unit>()

        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.results.collect { text ->
                received.add(text)
                holdCollector.await()
            }
        }

        // "eins" reaches the parked collector, "zwei" fills the buffer, "drei"
        // parks the drainer mid-emit, "vier" waits in the queue - so "funf" is
        // the first publish that can see the backlog and must raise the alarm.
        listOf("eins", "zwei", "drei", "vier", "funf").forEach { bus.publish(it) }

        assertEquals(listOf("funf"), overflows)

        holdCollector.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("eins", "zwei", "drei", "vier", "funf"), received)
        collector.cancelAndJoin()
        bus.close()
    }

    @Test
    fun `close cancels parked deliveries so a destroyed session leaks nothing`() = runTest {
        val bus = newBus()
        val received = mutableListOf<String>()
        val holdCollector = CompletableDeferred<Unit>()

        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.results.collect { text ->
                received.add(text)
                holdCollector.await()
            }
        }

        bus.publish("eins")  // delivered; collector parks on the gate
        bus.publish("zwei")  // buffered
        bus.publish("drei")  // drainer parked mid-emit

        // destroy() path: the owning helper is gone before the collector drains.
        bus.close()
        holdCollector.complete(Unit)
        advanceUntilIdle()

        // "zwei" was already safely buffered; the parked "drei" died with the
        // session and must not arrive after close.
        assertEquals(listOf("eins", "zwei"), received)
        collector.cancelAndJoin()
    }
}
