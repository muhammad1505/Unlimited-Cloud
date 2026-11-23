package com.unlimitedcloud.platform

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createMediaSaver(): MediaSaver = DesktopMediaSaver

/**
 * Desktop has no photo library, so downloads are copied into the user's
 * Downloads folder. [supported] stays false because there is no gallery to
 * insert into, which keeps the Android-only auto-save card hidden.
 */
private object DesktopMediaSaver : MediaSaver {
    override val supported: Boolean = false

    override suspend fun saveImage(
        localPath: String,
        displayName: String,
        mimeType: String,
    ): Boolean = saveFile(localPath, displayName, mimeType) != null

    override suspend fun saveFile(
        localPath: String,
        displayName: String,
        mimeType: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(localPath)
            if (!source.isFile) return@runCatching null
            val downloads = File(System.getProperty("user.home") ?: ".", "Downloads")
            val folder = File(downloads, "Unlimited Cloud").apply { mkdirs() }
            val target = uniqueTarget(folder, displayName)
            source.copyTo(target, overwrite = false)
            target.absolutePath
        }.getOrNull()
    }

    /** Keeps an existing download instead of silently overwriting it. */
    private fun uniqueTarget(folder: File, displayName: String): File {
        val candidate = File(folder, displayName)
        if (!candidate.exists()) return candidate

        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var index = 2
        while (true) {
            val next = File(folder, "$base ($index)$suffix")
            if (!next.exists()) return next
            index++
        }
    }
}
