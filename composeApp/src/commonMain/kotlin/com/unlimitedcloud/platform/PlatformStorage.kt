package com.unlimitedcloud.platform

/** Directories TDLib is allowed to write to on each platform. */
expect object PlatformStorage {
    /** Root for local preferences and other non-TDLib app data. */
    val appDataDirectory: String

    val tdlibDatabaseDirectory: String

    val tdlibFilesDirectory: String

    fun ensureDirectories()
}
