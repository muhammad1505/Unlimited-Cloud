package com.unlimitedcloud.platform

import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

private const val THUMBNAIL_EDGE = 320
private const val THUMBNAIL_QUALITY = 80

/** Covers older than this are leftovers from files no longer being browsed. */
private const val THUMBNAIL_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000

actual fun createUploadThumbnail(path: String): UploadThumbnail? = runCatching {
    val source = File(path)
    if (!source.isFile) return@runCatching null
    if (source.extension.lowercase() !in uploadThumbnailExtensions) return@runCatching null

    val image = Image.makeFromEncoded(source.readBytes())
    val scale = THUMBNAIL_EDGE.toFloat() / maxOf(image.width, image.height).coerceAtLeast(1)
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)

    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
    )
    val encoded = surface.makeImageSnapshot()
        .encodeToData(EncodedImageFormat.JPEG, THUMBNAIL_QUALITY)
        ?: return@runCatching null

    // Cache lives with the rest of the app data instead of the system temp
    // directory, where nothing ever cleaned these files up.
    val directory = File(PlatformStorage.appDataDirectory, "cache/thumbs").apply { mkdirs() }
    pruneThumbnails(directory)
    // Stable name per source path: a regenerated cover overwrites its own file
    // instead of adding another JPEG for every scroll or preview.
    val target = File(directory, "thumb-${path.stableId()}.jpg")
    target.writeBytes(encoded.bytes)

    UploadThumbnail(target.absolutePath, width, height)
}.getOrNull()

private fun String.stableId(): String = (hashCode().toLong() and 0xffffffffL).toString(16)

private fun pruneThumbnails(directory: File) {
    val cutoff = System.currentTimeMillis() - THUMBNAIL_CACHE_MILLIS
    directory.listFiles()?.forEach { file -> if (file.lastModified() < cutoff) file.delete() }
}
