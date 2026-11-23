package com.unlimitedcloud.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes an image that already sits in TDLib's local cache so the file can be
 * shown inside the app. Returns null when the bytes cannot be decoded.
 *
 * Implemented per platform because decoding is not part of Compose common code:
 * Android uses `BitmapFactory`, desktop uses Skia.
 */
expect fun decodeImageFile(path: String): ImageBitmap?
