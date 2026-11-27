package com.unlimitedcloud.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Copies the packaged `icon.png` out of the jar: TDLib uploads from a real file
 * path, and a classpath resource inside the package is not one.
 *
 * The PNG is already the 512 px brand mark used by the window and the tray.
 */
actual suspend fun appIconFilePath(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/icon.png")
            ?: return@withContext null
        val bytes = stream.use { it.readBytes() }
        val target = APP_DATA_ICON_PATH.toPath()
        FileSystem.SYSTEM.createDirectories(target.parent ?: return@withContext null)
        writeIcon(target, bytes)
        target.toString()
    }.getOrNull()
}

private val APP_DATA_ICON_PATH = "${PlatformStorage.appDataDirectory}/app-icon.png"

private fun writeIcon(target: Path, bytes: ByteArray) {
    FileSystem.SYSTEM.write(target) { write(bytes) }
}
