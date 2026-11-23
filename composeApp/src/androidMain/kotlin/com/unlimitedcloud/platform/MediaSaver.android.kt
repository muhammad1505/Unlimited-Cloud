package com.unlimitedcloud.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createMediaSaver(): MediaSaver = AndroidMediaSaver(AndroidPlatform.requireContext())

/**
 * Writes through MediaStore instead of a raw file path. On Android 10+ this is
 * allowed without READ/WRITE_EXTERNAL_STORAGE because the app only creates its
 * own entries; on older releases the feature is hidden.
 */
private class AndroidMediaSaver(
    private val context: Context,
) : MediaSaver {

    override val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override suspend fun saveImage(
        localPath: String,
        displayName: String,
        mimeType: String,
    ): Boolean = withContext(Dispatchers.IO) {
        // Direct version check (not only through `supported`) so Android lint can
        // prove the API 29 MediaStore columns below are guarded.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Unlimited Cloud",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return@withContext false

        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                File(localPath).inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)

        if (copied) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            runCatching { resolver.update(uri, done, null, null) }
            true
        } else {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
