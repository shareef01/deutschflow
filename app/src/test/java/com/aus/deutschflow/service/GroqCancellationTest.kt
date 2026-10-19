package com.aus.deutschflow.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cancelling a request stops it, rather than leaving it to time out.
 *
 * The connection used to be closed from `Job.invokeOnCompletion`, which only fires
 * once the job has *completed* - and a job blocked in a socket read cannot complete.
 * So cancellation waited out the read timeout (30 s in production) with the socket,
 * the IO thread and the user's quota all still committed.
 *
 * These run against a real loopback socket, because the bug lived in how a blocking
 * HttpURLConnection meets coroutine cancellation and nothing short of a real blocked
 * read exercises that.
 */
class GroqCancellationTest {

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val accepted = mutableListOf<Socket>()
    private val endpoint get() = "http://127.0.0.1:${server.localPort}/v1/chat/completions"

    @After
    fun teardown() {
        accepted.forEach { runCatching { it.close() } }
        server.close()
    }

    private val noWording: (Int, String?) -> String = { status, _ -> "status $status" }

    @Test
    fun cancellingAStalledRequestReturnsPromptly() = runBlocking {
        val connected = CountDownLatch(1)
        // Accepts and then says nothing: the client sits in its response read.
        thread(isDaemon = true) {
            val socket = server.accept()
            synchronized(accepted) { accepted += socket }
            connected.countDown()
        }

        val request = launch(Dispatchers.IO) {
            GroqHelper.postTo(endpoint, "{}", "key", noWording, timeoutMs = STALL_TIMEOUT_MS)
        }
        assertTrue("the client never connected", connected.await(5, TimeUnit.SECONDS))
        // Long enough for the body to be written and the client to be blocked reading.
        Thread.sleep(300)

        val started = System.nanoTime()
        request.cancelAndJoin()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(
            "cancellation took ${tookMs}ms; it should abort the read, not wait out the " +
                "${STALL_TIMEOUT_MS}ms timeout",
            tookMs < PROMPT_MS
        )
    }

    /**
     * A server that never answers ends in a timeout the callers can recognise, not a
     * cancellation and not a generic failure: GroqHelper maps exactly this to
     * ai_timeout instead of the JVM's English "Read timed out".
     */
    @Test
    fun aStalledRequestTimesOutAsATimeout() = runBlocking {
        thread(isDaemon = true) {
            val socket = server.accept()
            synchronized(accepted) { accepted += socket }
        }

        try {
            withTimeout(PROMPT_MS) {
                async(Dispatchers.IO) {
                    GroqHelper.postTo(endpoint, "{}", "key", noWording, timeoutMs = SHORT_TIMEOUT_MS)
                }.await()
            }
            fail("a request nobody answers should time out")
        } catch (e: SocketTimeoutException) {
            assertTrue(GroqHelper.isTimeout(e))
        }
    }

    @Test
    fun onlyATimeoutCountsAsOne() {
        assertTrue(GroqHelper.isTimeout(SocketTimeoutException("connect timed out")))
        assertFalse(GroqHelper.isTimeout(java.io.IOException("connection reset")))
        assertFalse(GroqHelper.isTimeout(IllegalStateException("status 503")))
    }

    /** The abort hook must not hold up a request that answers normally. */
    @Test
    fun anAnsweredRequestStillReturnsItsBody() = runBlocking {
        serveOnce(status = "200 OK", body = """{"ok":true}""")

        val body = withTimeout(PROMPT_MS) {
            async(Dispatchers.IO) { GroqHelper.postTo(endpoint, "{}", "key", noWording) }.await()
        }

        assertEquals("""{"ok":true}""", body)
    }

    @Test
    fun anErrorStatusIsReportedThroughTheCallersWording() = runBlocking {
        serveOnce(status = "401 Unauthorized", body = """{"error":{"message":"bad key"}}""")

        try {
            withTimeout(PROMPT_MS) {
                async(Dispatchers.IO) { GroqHelper.postTo(endpoint, "{}", "key", noWording) }.await()
            }
            fail("a 401 should throw")
        } catch (e: IllegalStateException) {
            assertEquals("status 401", e.message)
        }
    }

    /** Reads one request, answers it, and closes. */
    private fun serveOnce(status: String, body: String) {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                repeat(contentLength) { input.read() }

                val bytes = body.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.UTF_8)
                    )
                    write(bytes)
                    flush()
                }
            }
        }
    }

    private companion object {
        /** Long enough that waiting it out would be unmistakable. */
        const val STALL_TIMEOUT_MS = 20_000

        /** Well under the stall timeout, generous for a loaded CI machine. */
        const val PROMPT_MS = 3_000L

        /** Short enough to wait out inside [PROMPT_MS]. */
        const val SHORT_TIMEOUT_MS = 300
    }
}
