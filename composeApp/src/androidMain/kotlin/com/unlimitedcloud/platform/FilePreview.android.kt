package com.unlimitedcloud.platform

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Keeps the decoded bitmap small enough to preview large camera photos. */
private const val MAX_PREVIEW_EDGE = 2048

actual fun decodeImageFile(path: String): ImageBitmap? = runCatching {
    if (!File(path).isFile) return@runCatching null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (
        bounds.outWidth / sample > MAX_PREVIEW_EDGE ||
        bounds.outHeight / sample > MAX_PREVIEW_EDGE
    ) {
        sample *= 2
    }

    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrNull()
