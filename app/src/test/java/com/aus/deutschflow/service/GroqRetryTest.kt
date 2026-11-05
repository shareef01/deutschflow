package com.aus.deutschflow.service

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP responses exercise the classification above HttpURLConnection. */
class GroqRetryTest {
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private val responses = ConcurrentLinkedQueue<Int>()
    private val requests = AtomicInteger()
    private val waits = mutableListOf<Long>()
    private val endpoint get() = "http://127.0.0.1:${server.address.port}/chat"

    @Before fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/chat") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val status = responses.poll() ?: 503
            val content = if (status == 200) "success" else "provider failure"
            val bytes = content.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After fun teardown() {
        server.stop(0)
        executor.shutdownNow()
    }

    private suspend fun post(): String = withContext(Dispatchers.IO) {
        GroqHelper.postTo(endpoint, "{}", "synthetic-key", { status, _ -> "HTTP $status" },
            waitBeforeRetry = { waits.add(it) })
    }

    @Test fun transientServerStatusesRecoverWithOneRetry() = runBlocking {
        for (status in listOf(500, 502, 503, 504)) {
            requests.set(0)
            waits.clear()
            responses.addAll(listOf(status, 200))
            assertEquals("success", post())
            assertEquals(2, requests.get())
            assertEquals(listOf(300L), waits)
        }
    }

    @Test fun repeatedServerFailureStopsAfterThreeAttempts() = runBlocking {
        responses.addAll(listOf(503, 502, 504, 200))
        try {
            post()
            fail("must stop at the retry limit")
        } catch (e: IllegalStateException) {
            assertEquals("HTTP 504", e.message)
        }
        assertEquals(3, requests.get())
        assertEquals(listOf(300L, 600L), waits)
    }

    @Test fun providerRejectionsFailWithoutRetry() = runBlocking {
        for (status in listOf(400, 401, 403, 404, 429)) {
            requests.set(0)
            waits.clear()
            responses.clear()
            responses.add(status)
            try { post(); fail("must reject HTTP $status") }
            catch (e: IllegalStateException) { assertEquals("HTTP $status", e.message) }
            assertEquals(1, requests.get())
            assertTrue(waits.isEmpty())
        }
    }

    @Test fun transientNetworkErrorsRecoverWithBoundedBackoff() = runBlocking {
        var attempts = 0
        val result = GroqHelper.retryPost(waitBeforeRetry = { waits.add(it) }) {
            if (++attempts < 3) throw SocketException("synthetic connection reset")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, attempts)
        assertEquals(listOf(300L, 600L), waits)
    }

    @Test fun cancellationDuringBackoffDoesNotSendAnotherRequest() = runBlocking {
        responses.add(503)
        val backingOff = CompletableDeferred<Unit>()
        val request = async(Dispatchers.IO) {
            GroqHelper.postTo(endpoint, "{}", "synthetic-key", { status, _ -> "HTTP $status" },
                waitBeforeRetry = { backingOff.complete(Unit); awaitCancellation() })
        }
        withTimeout(5_000) { backingOff.await() }
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertEquals(1, requests.get())
    }

    @Test fun timeoutDoesNotWaitOrRetry() = runBlocking {
        var attempts = 0
        try {
            GroqHelper.retryPost(waitBeforeRetry = { waits.add(it) }) {
                attempts++
                throw SocketTimeoutException("synthetic read timeout")
            }
            fail("must time out")
        } catch (_: SocketTimeoutException) {
            assertEquals(1, attempts)
            assertTrue(waits.isEmpty())
        }
    }
}
