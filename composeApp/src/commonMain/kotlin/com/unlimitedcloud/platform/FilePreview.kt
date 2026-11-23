package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

/** Video containers the preview dialog can hand to a platform media surface. */
internal val previewVideoExtensions = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")

/** Audio containers Android's MediaPlayer can decode. */
internal val previewAudioExtensions = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus")

/** True when the file needs a media player or a PDF renderer instead of an image decoder. */
fun isMediaPreview(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in previewVideoExtensions ||
        extension in previewAudioExtensions ||
        extension == "pdf"
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
 * Shows a video, audio or PDF from TDLib's cached path. Android renders video in
 * a `VideoView`, audio through `MediaPlayer` and PDF pages through `PdfRenderer`;
 * desktop plays WAV through the JDK mixer and decodes MP3/FLAC in pure Java, and
 * explains the limitation for video and PDF while keeping the download buttons
 * available.
 */
@Composable
expect fun PlatformMediaPreview(path: String, fileName: String, modifier: Modifier)
