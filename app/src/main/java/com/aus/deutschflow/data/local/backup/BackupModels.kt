package com.aus.deutschflow.data.local.backup

const val BACKUP_FORMAT = "deutschflow-library"
const val CURRENT_BACKUP_VERSION = 2
const val MAX_BACKUP_FILE_BYTES = 10 * 1024 * 1024 // 10 MB
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
