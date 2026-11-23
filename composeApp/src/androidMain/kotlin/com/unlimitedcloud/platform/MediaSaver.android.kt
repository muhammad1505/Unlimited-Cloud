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

    /**
     * Non-image downloads land in Download/Unlimited Cloud. Without this every
     * "Unduh" only filled TDLib's cache, which no other app can open.
     */
    override suspend fun saveFile(
        localPath: String,
        displayName: String,
        mimeType: String,
    ): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null

        val resolver = context.contentResolver
        val folder = "${Environment.DIRECTORY_DOWNLOADS}/Unlimited Cloud"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return@withContext null

        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                File(localPath).inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)

        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }

        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        runCatching { resolver.update(uri, done, null, null) }
        "Download/Unlimited Cloud/$displayName"
    }
}
