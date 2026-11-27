package com.unlimitedcloud.platform

/**
 * Space the temporary upload copies occupy.
 *
 * Only Android has these copies: every pick is written into the app cache so
 * TDLib can upload from a real path. Desktop uploads from where the file lives,
 * so [uploadCacheSupported] is false there and no number is shown.
 */
data class UploadCacheUsage(
    val bytes: Long = 0L,
)

/** Whether this platform keeps temporary upload copies at all. */
expect val uploadCacheSupported: Boolean

/** Measures the cache directories the upload pickers fill. */
expect fun uploadCacheUsage(): UploadCacheUsage

/** Deletes every temporary upload copy and returns the bytes that freed. */
expect fun clearUploadCache(): Long
