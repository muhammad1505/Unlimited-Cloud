package com.unlimitedcloud.platform

import java.io.File

actual fun storageUsage(): StorageUsage = StorageUsage(
    tdlibDatabaseBytes = File(PlatformStorage.tdlibDatabaseDirectory).sizeBytes(),
    tdlibFilesBytes = File(PlatformStorage.tdlibFilesDirectory).sizeBytes(),
    // Desktop never copies picks, so this stays 0.
    uploadCacheBytes = uploadCacheUsage().bytes,
)

private fun File.sizeBytes(): Long =
    if (!exists()) 0L else walkBottomUp().filter { it.isFile }.sumOf { it.length() }
