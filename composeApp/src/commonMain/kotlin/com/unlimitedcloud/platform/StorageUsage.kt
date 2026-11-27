package com.unlimitedcloud.platform

/**
 * How the app's own bytes on disk split up.
 *
 * Reported for information only: the TDLib directories belong to the Telegram
 * session and are in use while the app runs, so Settings never clears them.
 */
data class StorageUsage(
    /** TDLib session database: message index, chats, update state. */
    val tdlibDatabaseBytes: Long = 0L,
    /** TDLib's own storage: downloads, thumbnails and uploads in flight. */
    val tdlibFilesBytes: Long = 0L,
    /** Temporary copies the pickers make for an upload. */
    val uploadCacheBytes: Long = 0L,
) {
    val totalBytes: Long get() = tdlibDatabaseBytes + tdlibFilesBytes + uploadCacheBytes
}

/** Measures the app's directories; both platforms report real numbers. */
expect fun storageUsage(): StorageUsage
