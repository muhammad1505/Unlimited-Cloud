package com.unlimitedcloud.platform

import java.io.File

actual fun storageUsage(): StorageUsage = StorageUsage(
    tdlibDatabaseBytes = File(PlatformStorage.tdlibDatabaseDirectory).treeSizeBytes(),
    tdlibFilesBytes = File(PlatformStorage.tdlibFilesDirectory).treeSizeBytes(),
    uploadCacheBytes = uploadCacheUsage().bytes,
)
