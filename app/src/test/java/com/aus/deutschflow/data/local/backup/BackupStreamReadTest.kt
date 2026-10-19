package com.aus.deutschflow.data.local.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Size-check ordering for BackupManager.readBackupText. Small limits stand in for the
 * real 70 MiB / 50 MiB caps so the same branches run without allocating tens of MB;
 * the full-size cases live in BackupManagerIntegrationTest.
 */
class BackupStreamReadTest {

    private val maxBytes = 200
    private val maxPlaintextBytes = 100
    private val encryptedMarker = "\"${BackupCrypto.ENCRYPTED_FORMAT}\""

    private fun read(bytes: ByteArray): String =
        BackupManager.readBackupText(ByteArrayInputStream(bytes), maxBytes, maxPlaintextBytes)

    private fun padded(prefix: String, totalBytes: Int): ByteArray {
        val head = prefix.toByteArray(Charsets.UTF_8)
        return ByteArray(totalBytes) { i -> if (i < head.size) head[i] else ' '.code.toByte() }
    }

    /** Serves [content], hides its length from available(), and fails if read past [readLimit] bytes. */
    private class GuardedStream(
        private val content: ByteArray,
        private val readLimit: Long = Long.MAX_VALUE,
        private val chunk: Int = 7
    ) : InputStream() {
        var bytesServed = 0L
            private set

        override fun available(): Int = 0

        override fun read(): Int {
            if (bytesServed >= content.size) return -1
            check(++bytesServed <= readLimit) { "read past $readLimit bytes" }
            return content[(bytesServed - 1).toInt()].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesServed >= content.size) return -1
            val n = minOf(len, chunk, (content.size - bytesServed).toInt())
            check(bytesServed + n <= readLimit) { "read past $readLimit bytes" }
            System.arraycopy(content, bytesServed.toInt(), b, off, n)
            bytesServed += n
            return n
        }
    }

    @Test
    fun `plaintext at the plaintext cap is decoded`() {
        val bytes = padded("{\"format\":\"deutschflow-library\"}", maxPlaintextBytes)
        assertEquals(String(bytes, Charsets.UTF_8), read(bytes))
    }

    @Test
    fun `plaintext one byte over the plaintext cap fails on the byte count`() {
        val bytes = padded("{\"format\":\"deutschflow-library\"}", maxPlaintextBytes + 1)
        assertThrows(BackupException.CapacityExceeded::class.java) { read(bytes) }
    }

    @Test
    fun `oversize plaintext with malformed UTF-8 reports capacity before decoding`() {
        val bytes = padded("{\"format\":", maxPlaintextBytes + 1)
        bytes[12] = 0xFE.toByte()
        assertThrows(BackupException.CapacityExceeded::class.java) { read(bytes) }
    }

    @Test
    fun `envelope over the plaintext cap but within the encoded cap is decoded`() {
        val bytes = padded("{\"format\":$encryptedMarker}", maxBytes)
        assertEquals(String(bytes, Charsets.UTF_8), read(bytes))
    }

    @Test
    fun `input over the encoded cap fails after reading at most one extra byte`() {
        val stream = GuardedStream(
            padded("{\"format\":$encryptedMarker}", maxBytes * 4),
            readLimit = maxBytes + 1L
        )
        assertThrows(BackupException.CapacityExceeded::class.java) {
            BackupManager.readBackupText(stream, maxBytes, maxPlaintextBytes)
        }
        assertEquals(maxBytes + 1L, stream.bytesServed)
    }

    @Test
    fun `empty input is invalid`() {
        assertThrows(BackupException.InvalidFormat::class.java) { read(ByteArray(0)) }
    }

    @Test
    fun `malformed UTF-8 within the caps is invalid`() {
        val bytes = byteArrayOf(0x7B, 0x22, 0x61, 0x22, 0x3A, 0xFE.toByte(), 0xFF.toByte(), 0x7D)
        assertThrows(BackupException.InvalidFormat::class.java) { read(bytes) }
    }

    @Test
    fun `truncated multi-byte sequence at end of input is invalid`() {
        val bytes = "{\"a\":\"ü".toByteArray(Charsets.UTF_8).let { it.copyOf(it.size - 1) }
        assertThrows(BackupException.InvalidFormat::class.java) { read(bytes) }
    }

    @Test
    fun `non-ASCII text round-trips across validation chunks and buffer growth`() {
        // Larger than the 64 KiB starting buffer and the 8K-char validation chunk, read
        // in 7-byte pieces with no size hint, so growth and chunk boundaries both split
        // multi-byte sequences.
        val text = buildString {
            append("{\"t\":\"")
            repeat(20_000) { append("Grüße 🇩🇪 ") }
            append("\"}")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > 64 * 1024)
        val result = BackupManager.readBackupText(GuardedStream(bytes), bytes.size, bytes.size)
        assertEquals(text, result)
    }

    @Test
    fun `available hint larger than the content still reads exactly the content`() {
        val bytes = "{\"format\":\"deutschflow-library\"}".toByteArray(Charsets.UTF_8)
        val stream = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun available(): Int = Int.MAX_VALUE
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        }
        assertEquals(String(bytes, Charsets.UTF_8), BackupManager.readBackupText(stream, maxBytes, maxPlaintextBytes))
    }
}
