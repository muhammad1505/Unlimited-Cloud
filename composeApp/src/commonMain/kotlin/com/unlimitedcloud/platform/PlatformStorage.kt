package com.unlimitedcloud.platform

/** Directories TDLib is allowed to write to on each platform. */
expect object PlatformStorage {
    val tdlibDatabaseDirectory: String

    val tdlibFilesDirectory: String

    fun ensureDirectories()
}
