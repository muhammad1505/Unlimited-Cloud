package com.unlimitedcloud.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image

actual fun decodeImageFile(path: String): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}.getOrNull()

@Composable
actual fun PlatformMediaPreview(path: String, fileName: String, modifier: Modifier) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    when {
        extension in previewAudioExtensions ->
            DesktopAudioPreview(path = path, fileName = fileName, modifier = modifier)
        extension == "pdf" -> DesktopLimitation(
            what = "PDF",
            modifier = modifier,
        )
        else -> DesktopLimitation(
            what = "Video",
            modifier = modifier,
        )
    }
}

@Composable
private fun DesktopLimitation(what: String, modifier: Modifier) {
    Text(
        text = "Pratinjau $what belum tersedia di aplikasi desktop. " +
            "Unduh atau bagikan berkas untuk membukanya di aplikasi lain.",
        modifier = modifier.padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * Desktop audio uses the JDK's own mixer, so no extra native dependency is
 * bundled. That covers WAV/AIFF; other containers stay a download.
 */
@Composable
private fun DesktopAudioPreview(path: String, fileName: String, modifier: Modifier) {
    val clip = remember(path) {
        runCatching {
            AudioSystem.getAudioInputStream(File(path)).use { stream ->
                AudioSystem.getClip().apply { open(stream) }
            }
        }.getOrNull()
    }

    DisposableEffect(clip) {
        onDispose { clip?.close() }
    }

    if (clip == null) {
        Text(
            text = "Format audio ini belum bisa diputar di desktop. " +
                "Unduh atau bagikan berkas untuk membukanya di aplikasi lain.",
            modifier = modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }

    var playing by remember(path) { mutableStateOf(false) }
    var positionMs by remember(path) { mutableIntStateOf(0) }
    val durationMs = remember(clip) { (clip.microsecondLength / 1000L).toInt().coerceAtLeast(0) }

    // A Clip does not report progress, so the position is polled while playing.
    LaunchedEffect(playing, clip) {
        while (playing) {
            positionMs = (clip.microsecondPosition / 1000L).toInt()
            delay(400)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction ->
                val target = (fraction * clip.microsecondLength).toLong()
                clip.microsecondPosition = target
                positionMs = (target / 1000L).toInt()
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (playing) {
                        clip.stop()
                    } else {
                        if (clip.microsecondPosition >= clip.microsecondLength) {
                            clip.microsecondPosition = 0
                        }
                        clip.start()
                    }
                    playing = !playing
                },
            ) {
                Text(if (playing) "Jeda" else "Putar")
            }
            Text(
                text = "${clockOf(positionMs)} / ${clockOf(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun clockOf(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
