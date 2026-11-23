package com.unlimitedcloud.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import org.jetbrains.skia.Image

actual fun decodeImageFile(path: String): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}.getOrNull()
