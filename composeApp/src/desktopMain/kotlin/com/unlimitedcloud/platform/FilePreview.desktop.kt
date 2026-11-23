package com.unlimitedcloud.platform

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import org.jetbrains.skia.Image

actual fun decodeImageFile(path: String): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}.getOrNull()

@Composable
actual fun PlatformMediaPreview(path: String, fileName: String, modifier: Modifier) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val what = when {
        extension == "pdf" -> "PDF"
        extension in previewAudioExtensions -> "Audio"
        else -> "Video"
    }
    Text(
        text = "Pratinjau $what belum tersedia di aplikasi desktop. " +
            "Unduh atau bagikan berkas untuk membukanya di aplikasi lain.",
        modifier = modifier.padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
