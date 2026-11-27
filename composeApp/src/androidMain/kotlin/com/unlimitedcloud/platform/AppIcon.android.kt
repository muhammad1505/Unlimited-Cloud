package com.unlimitedcloud.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders the launcher icon into a 512 px PNG.
 *
 * The icon is an adaptive drawable on modern Android, so reading one of the
 * `ic_launcher.png` mipmaps would return the old square bitmap or nothing at
 * all; drawing the resolved drawable keeps whatever the launcher shows. 512 px
 * is the size Telegram keeps for a chat photo (TDLib rejects anything above
 * 640 px).
 */
actual suspend fun appIconFilePath(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val context = AndroidPlatform.requireContext()
        val size = ICON_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))

        val directory = File(context.filesDir, ICON_DIRECTORY).apply { mkdirs() }
        val file = File(directory, ICON_FILE_NAME)
        val encoded = file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        if (!encoded) error("ikon aplikasi gagal dikodekan")
        file.absolutePath
    }.getOrNull()
}

/** Square size of the rendered icon; Telegram stores at most 640 px. */
private const val ICON_SIZE = 512
private const val ICON_DIRECTORY = "storage-group"
private const val ICON_FILE_NAME = "app-icon.png"
