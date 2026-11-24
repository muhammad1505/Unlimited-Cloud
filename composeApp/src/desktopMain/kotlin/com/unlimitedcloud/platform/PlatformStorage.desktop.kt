package com.unlimitedcloud.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

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
        listOf(root, appDataDirectory, tdlibDatabaseDirectory, tdlibFilesDirectory).forEach { path ->
            val directory = File(path).apply { mkdirs() }
            // TDLib's session database is not encrypted, so it must stay readable
            // by this user only. POSIX only: Windows ignores the failure.
            runCatching {
                Files.setPosixFilePermissions(
                    directory.toPath(),
                    PosixFilePermissions.fromString("rwx------"),
                )
            }
        }
    }
}
