package com.unlimitedcloud.platform

import java.io.File

actual val uploadCacheSupported: Boolean = true

actual fun uploadCacheUsage(): UploadCacheUsage {
    val context = AndroidPlatform.requireContext()
    val bytes = UPLOAD_CACHE_DIRECTORIES.sumOf { name ->
        File(context.cacheDir, name).sizeBytes()
    }
    return UploadCacheUsage(bytes = bytes)
}

actual fun clearUploadCache(): Long {
    val context = AndroidPlatform.requireContext()
    var freed = 0L
    UPLOAD_CACHE_DIRECTORIES.forEach { name ->
        val directory = File(context.cacheDir, name)
        freed += directory.sizeBytes()
        directory.deleteRecursively()
    }
    return freed
}

/** Total size of the files under this directory; 0 when it does not exist. */
private fun File.sizeBytes(): Long =
    if (!exists()) 0L else walkBottomUp().filter { it.isFile }.sumOf { it.length() }

/**
 * Only the directories the pickers write into. Wiping the whole cache would
 * also drop copies a running upload still needs, and other caches the app did
 * not create.
 */
private val UPLOAD_CACHE_DIRECTORIES = listOf("uploads", "gallery-sync", "group-photo")
