package com.unlimitedcloud.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

private const val THUMBNAIL_EDGE = 320
private const val THUMBNAIL_QUALITY = 80

actual fun createUploadThumbnail(path: String): UploadThumbnail? = runCatching {
    val source = File(path)
    if (!source.isFile) return@runCatching null
    if (source.extension.lowercase() !in uploadThumbnailExtensions) return@runCatching null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sample = 1
    while (
        bounds.outWidth / sample > THUMBNAIL_EDGE ||
        bounds.outHeight / sample > THUMBNAIL_EDGE
    ) {
        sample *= 2
    }

    val decoded = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return@runCatching null

    val scale = THUMBNAIL_EDGE.toFloat() / maxOf(decoded.width, decoded.height).coerceAtLeast(1)
    val width = (decoded.width * scale).toInt().coerceAtLeast(1)
    val height = (decoded.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (width == decoded.width && height == decoded.height) {
        decoded
    } else {
        Bitmap.createScaledBitmap(decoded, width, height, true)
    }

    val target = File(
        AndroidPlatform.requireContext().cacheDir,
        "thumb-${System.currentTimeMillis()}.jpg",
    )
    target.outputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)
    }
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()

    UploadThumbnail(target.absolutePath, width, height)
}.getOrNull()
