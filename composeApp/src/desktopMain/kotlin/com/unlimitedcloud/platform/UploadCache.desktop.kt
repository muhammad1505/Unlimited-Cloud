package com.unlimitedcloud.platform

/** Desktop uploads from the picked path, so it never makes a cache copy. */
actual val uploadCacheSupported: Boolean = false

actual fun uploadCacheUsage(): UploadCacheUsage = UploadCacheUsage()

actual fun clearUploadCache(): Long = 0L
