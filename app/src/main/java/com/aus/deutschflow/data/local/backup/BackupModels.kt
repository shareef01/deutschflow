package com.aus.deutschflow.data.local.backup

const val BACKUP_FORMAT = "deutschflow-library"
const val CURRENT_BACKUP_VERSION = 2

/**
 * Maximum supported size of unencrypted plaintext JSON backup library data.
 * 10 MiB (10,485,760 bytes) accommodates tens of thousands of vocabulary items,
 * review events, transcripts, roleplay conversations, and user stats.
 */
const val MAX_PLAINTEXT_BACKUP_BYTES = 10 * 1024 * 1024 // 10 MiB

/**
 * Backward-compatibility alias for the maximum plaintext library size.
 */
const val MAX_BACKUP_FILE_BYTES = MAX_PLAINTEXT_BACKUP_BYTES

/**
 * Maximum raw ciphertext bytes resulting from AES-256-GCM encryption of a
 * 10 MiB plaintext payload (plaintext bytes + 16 bytes authentication tag).
 * 10,485,776 bytes.
 */
const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BACKUP_BYTES + 16

/**
 * Maximum character length of the Base64-encoded ciphertext string:
 * ceil(10,485,776 / 3) * 4 = 13,981,040 chars (~13.33 MiB).
 */
const val MAX_BASE64_CIPHERTEXT_LENGTH = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4

/**
 * Maximum supported size of an encrypted backup file envelope (JSON wrapper).
 * Accommodates up to 13.98 MiB of Base64 ciphertext, Base64 salt, Base64 IV,
 * PBKDF2 iterations, format, version, and formatting whitespace with bounded overhead.
 * 15 MiB (15,728,640 bytes).
 */
const val MAX_ENCRYPTED_BACKUP_FILE_BYTES = 15 * 1024 * 1024 // 15 MiB

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
