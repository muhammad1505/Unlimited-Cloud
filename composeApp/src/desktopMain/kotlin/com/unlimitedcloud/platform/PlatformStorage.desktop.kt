package com.unlimitedcloud.platform

import java.io.File

actual object PlatformStorage {
    private val root: String
        get() = System.getProperty("user.home") + "/.unlimitedcloud"

    actual val appDataDirectory: String
        get() = "$root/app"

    actual val tdlibDatabaseDirectory: String
        get() = "$root/tdlib/db"

    actual val tdlibFilesDirectory: String
        get() = "$root/tdlib/files"

    actual fun ensureDirectories() {
        File(appDataDirectory).mkdirs()
        File(tdlibDatabaseDirectory).mkdirs()
        File(tdlibFilesDirectory).mkdirs()
    }
}
