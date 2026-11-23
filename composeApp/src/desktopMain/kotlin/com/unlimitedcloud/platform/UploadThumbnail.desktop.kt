package com.unlimitedcloud.platform

import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

private const val THUMBNAIL_EDGE = 320
private const val THUMBNAIL_QUALITY = 80

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

    val target = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "thumb-${System.currentTimeMillis()}.jpg",
    )
    target.writeBytes(encoded.bytes)

    UploadThumbnail(target.absolutePath, width, height)
}.getOrNull()
