package com.unlimitedcloud.platform

/** Raster formats both thumbnail generators can decode without extra libraries. */
internal val uploadThumbnailExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif",
)

/**
 * A small JPEG generated next to an upload so the sent document carries a
 * cover image. [width] and [height] describe the generated file, which TDLib
 * needs for `inputThumbnail`.
 */
data class UploadThumbnail(val path: String, val width: Int, val height: Int)

/**
 * Builds a ~320 px preview for an image file. Returns null for non-images and
 * for bytes the platform cannot decode, so the upload keeps working unchanged.
 */
expect fun createUploadThumbnail(path: String): UploadThumbnail?
