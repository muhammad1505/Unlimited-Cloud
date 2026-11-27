package com.unlimitedcloud.platform

import java.awt.Desktop
import java.io.File

private val desktop: Desktop?
    get() = runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null }.getOrNull()

actual val sourceFileDeletionSupported: Boolean =
    runCatching {
        desktop?.isSupported(Desktop.Action.MOVE_TO_TRASH) == true
    }.getOrDefault(false)

actual fun deleteSourceFile(localPath: String, documentUri: String?): Boolean = runCatching {
    // Never unlink: the system trash keeps the file recoverable, which is the
    // whole point of only deleting after the upload was confirmed.
    desktop?.moveToTrash(File(localPath)) == true
}.getOrDefault(false)
