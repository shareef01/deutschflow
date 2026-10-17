package com.aus.deutschflow.data.local.backup

const val BACKUP_FORMAT = "deutschflow-library"
const val CURRENT_BACKUP_VERSION = 2

/**
 * Maximum supported size of unencrypted plaintext JSON backup library data.
 * 50 MiB (52,428,800 bytes) accommodates hundreds of thousands of vocabulary items,
 * review events, transcripts, roleplay conversations, and user stats.
 */
const val MAX_PLAINTEXT_BACKUP_BYTES = 50 * 1024 * 1024 // 50 MiB

/**
 * Backward-compatibility alias for the maximum plaintext library size.
 */
const val MAX_BACKUP_FILE_BYTES = MAX_PLAINTEXT_BACKUP_BYTES

/**
 * Maximum raw ciphertext bytes resulting from AES-256-GCM encryption of a
 * 50 MiB plaintext payload (plaintext bytes + 16 bytes authentication tag).
 * 52,428,816 bytes.
 */
const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BACKUP_BYTES + 16

/**
 * Maximum character length of the Base64-encoded ciphertext string:
 * ceil(52,428,816 / 3) * 4 = 69,905,092 chars (~66.67 MiB).
 */
const val MAX_BASE64_CIPHERTEXT_LENGTH = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4

/**
 * Maximum supported size of an encrypted backup file envelope (JSON wrapper).
 * Accommodates up to 69.9 MiB of Base64 ciphertext, Base64 salt, Base64 IV,
 * PBKDF2 iterations, format, version, and formatting whitespace with bounded overhead.
 * 70 MiB (73,400,320 bytes).
 */
const val MAX_ENCRYPTED_BACKUP_FILE_BYTES = 70 * 1024 * 1024 // 70 MiB

/**
 * Bounded maximum bytes read by importFromStream() to avoid OOM while
 * accommodating the largest officially supported encrypted backup envelope.
 */
const val MAX_STREAM_READ_BYTES = MAX_ENCRYPTED_BACKUP_FILE_BYTES

const val MAX_USER_STATS_ROWS = 1
const val MAX_FUTURE_TIMESTAMP = 4_102_444_800_000L // 2100-01-01T00:00:00Z

val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

data class BackupImportResult(
    val vocabularyAdded: Int,
    val vocabularyMerged: Int,
    val transcriptsAdded: Int,
    val reviewEventsAdded: Int
)

sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidFormat(message: String) : BackupException(message)
    class UnsupportedVersion(message: String) : BackupException(message)
    class CorruptedData(message: String, cause: Throwable? = null) : BackupException(message, cause)
    class PassphraseRequired(message: String) : BackupException(message)
    class WrongPassphrase(message: String, cause: Throwable? = null) : BackupException(message, cause)
    class CapacityExceeded(message: String) : BackupException(message)
    class StorageError(message: String, cause: Throwable? = null) : BackupException(message, cause)
}

fun utf8ByteLength(s: CharSequence): Long {
    var count = 0L
    var i = 0
    val len = s.length
    while (i < len) {
        val c = s[i].code
        when {
            c <= 0x7F -> {
                count += 1
                i++
            }
            c <= 0x7FF -> {
                count += 2
                i++
            }
            c in 0xD800..0xDBFF -> {
                if (i + 1 < len && s[i + 1].code in 0xDC00..0xDFFF) {
                    count += 4
                    i += 2
                } else {
                    count += 3
                    i++
                }
            }
            c in 0xDC00..0xDFFF -> {
                count += 3
                i++
            }
            else -> {
                count += 3
                i++
            }
        }
    }
    return count
}
