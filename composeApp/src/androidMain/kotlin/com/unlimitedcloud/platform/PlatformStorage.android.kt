package com.unlimitedcloud.platform

actual object PlatformStorage {
    private val root: String
        get() = AndroidPlatform.requireContext().filesDir.absolutePath + "/unlimitedcloud"

    actual val tdlibDatabaseDirectory: String
        get() = "$root/tdlib/db"

    actual val tdlibFilesDirectory: String
        get() = "$root/tdlib/files"

    actual fun ensureDirectories() {
        java.io.File(tdlibDatabaseDirectory).mkdirs()
        java.io.File(tdlibFilesDirectory).mkdirs()
    }
}
