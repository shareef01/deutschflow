package com.aus.deutschflow.service

import com.aus.deutschflow.service.readBounded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader

/**
 * The response reader is bounded.
 *
 * Both call sites used `readText()`, which sizes its buffer from the server's
 * `Content-Length` when one is sent and otherwise grows without limit. The endpoint is
 * a third-party API reached by URL, and `readTimeout` bounds how long a *stall* takes,
 * not how much arrives - a server that keeps sending slowly never trips it. So the
 * read could allocate an unbounded string in a phone process.
 *
 * The tests below pin the two properties that matter: a large body is truncated at the
 * cap, and a body at or under the cap is returned complete and unmodified. Truncation
 * is safe by design - the truncated JSON then fails to parse and surfaces through the
 * existing malformed-response error rather than being silently accepted.
 */
class GroqBoundedReadTest {

    /** A Reader that reports [total] characters but never ends on its own. */
    private class EndlessReader(private val total: Int, private val chunk: Int = 64) : Reader() {
        var charsServed = 0
            private set

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (charsServed >= total) return -1
            val n = minOf(len, chunk, total - charsServed)
            for (i in 0 until n) cbuf[off + i] = 'x'
            charsServed += n
            return n
        }

        override fun close() = Unit
    }

    @Test
    fun readsAShortBodyWhole() {
        val body = """{"translation":"Hallo"}"""
        assertEquals(body, body.reader().readBounded(1_000_000))
    }

    @Test
    fun readsExactlyTheCap() {
        val body = "x".repeat(50)
        assertEquals(body, body.reader().readBounded(50))
    }

    @Test
    fun truncatesABodyLargerThanTheCap() {
        val cap = 1_000
        val body = "x".repeat(50_000)
        val read = body.reader().readBounded(cap)

        assertEquals(cap, read.length)
        // Not merely short - it is the leading slice, so a truncated response still
        // fails the JSON parse rather than parsing as something valid.
        assertEquals("x".repeat(cap), read)
    }

    @Test
    fun stopsPullingFromTheStreamOnceTheCapIsReached() {
        // The point of the cap: the reader is not drained. A body far larger than the
        // cap must not be pulled through the network at all.
        val reader = EndlessReader(total = 10_000_000, chunk = 4096)
        val read = reader.readBounded(8_000)

        assertEquals(8_000, read.length)
        assertTrue(
            "should stop near the cap, not drain the stream: served ${reader.charsServed}",
            reader.charsServed <= 8_000 + 4096
        )
    }

    @Test
    fun handlesABodyEndingMidChunk() {
        val body = "abcde"
        assertEquals(body, body.reader().readBounded(100))
    }

    @Test
    fun assemblesABodySpanningSeveralChunksInOrder() {
        // The read is chunked at 8 KiB now, so a body longer than one chunk must be
        // reassembled exactly - not truncated at the first chunk, and not reordered.
        val body = (0 until 20_000).joinToString("") { (it % 10).toString() }
        val read = body.reader().readBounded(1_000_000)
        assertEquals(body.length, read.length)
        assertEquals(body, read)
    }

    @Test
    fun aBodyExactlyOneChunkLongIsReadWhole() {
        val body = "x".repeat(8_192)
        assertEquals(body, body.reader().readBounded(8_192))
    }

    @Test
    fun handlesAnEmptyBody() {
        assertEquals("", "".reader().readBounded(1_000))
    }

    @Test
    fun preservesMultiByteCharactersAtTheBoundary() {
        // The cap is in chars, not bytes, so a truncation must never split a surrogate
        // pair or leave a partial character behind.
        val body = "ü".repeat(10)
        val read = body.reader().readBounded(4)
        assertEquals(4, read.length)
        assertEquals("üüüü", read)
    }
}