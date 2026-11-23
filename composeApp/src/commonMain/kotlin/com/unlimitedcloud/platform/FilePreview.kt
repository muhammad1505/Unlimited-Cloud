package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

/** Video containers the preview dialog can hand to a platform media surface. */
internal val previewVideoExtensions = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")

/** True when the file needs a video player or a PDF renderer instead of an image decoder. */
fun isMediaPreview(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in previewVideoExtensions || extension == "pdf"
}

/**
 * Decodes an image that already sits in TDLib's local cache so the file can be
 * shown inside the app. Returns null when the bytes cannot be decoded.
 *
 * Implemented per platform because decoding is not part of Compose common code:
 * Android uses `BitmapFactory`, desktop uses Skia.
 */
expect fun decodeImageFile(path: String): ImageBitmap?

/**
 * Shows a video or PDF from TDLib's cached path. Android renders video in a
 * `VideoView` and PDF pages through `PdfRenderer`; desktop has no bundled media
 * stack, so its actual explains the limitation and keeps the download buttons.
 */
@Composable
expect fun PlatformMediaPreview(path: String, fileName: String, modifier: Modifier)
