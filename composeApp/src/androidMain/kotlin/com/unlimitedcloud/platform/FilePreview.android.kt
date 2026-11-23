package com.unlimitedcloud.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.CloudFileKind
import com.unlimitedcloud.ui.components.FileKindIcon
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Keeps the decoded bitmap small enough to preview large camera photos. */
private const val MAX_PREVIEW_EDGE = 2048

/** PDFs can be hundreds of pages; the preview stops early instead of filling RAM. */
private const val MAX_PDF_PAGES = 40
private const val PDF_RENDER_WIDTH = 1440

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

@Composable
actual fun PlatformMediaPreview(path: String, fileName: String, modifier: Modifier) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    when {
        extension in previewVideoExtensions -> VideoPreview(path = path, modifier = modifier)
        extension in previewAudioExtensions ->
            AudioPreview(path = path, fileName = fileName, modifier = modifier)
        extension == "pdf" -> PdfPreview(path = path, modifier = modifier)
        else -> PlatformPreviewMessage(
            text = "Jenis berkas ini belum bisa dipratinjau. Unduh atau bagikan untuk membukanya.",
            modifier = modifier,
        )
    }
}

@Composable
private fun VideoPreview(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        VideoView(context).apply {
            val controls = MediaController(context)
            controls.setAnchorView(this)
            setMediaController(controls)
            setOnPreparedListener { start() }
            setVideoPath(path)
        }
    }

    DisposableEffect(player) {
        onDispose { player.stopPlayback() }
    }

    AndroidView(
        factory = { player },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    )
}

@Composable
private fun AudioPreview(path: String, fileName: String, modifier: Modifier) {
    var prepared by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    var durationMs by remember(path) { mutableIntStateOf(0) }
    var positionMs by remember(path) { mutableIntStateOf(0) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    val player = remember(path) { MediaPlayer() }

    DisposableEffect(player, path) {
        runCatching {
            player.setDataSource(path)
            player.setOnPreparedListener {
                durationMs = it.duration.coerceAtLeast(0)
                prepared = true
            }
            player.setOnCompletionListener {
                playing = false
                positionMs = 0
                it.seekTo(0)
            }
            player.setOnErrorListener { _, _, _ ->
                failure = "Audio ini tidak bisa diputar. Unduh untuk membukanya."
                true
            }
            player.prepareAsync()
        }.onFailure {
            failure = "Audio ini tidak bisa diputar. Unduh untuk membukanya."
        }
        onDispose { runCatching { player.release() } }
    }

    // MediaPlayer has no position callback, so the seek bar polls while playing.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            delay(400)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FileKindIcon(kind = CloudFileKind.Audio, size = 64.dp)
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val error = failure
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        } else {
            Slider(
                value = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onValueChange = { fraction ->
                    if (prepared && durationMs > 0) {
                        val target = (fraction * durationMs).toInt()
                        runCatching { player.seekTo(target) }
                        positionMs = target
                    }
                },
                enabled = prepared,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        if (!prepared) return@IconButton
                        if (playing) {
                            player.pause()
                            playing = false
                        } else {
                            player.start()
                            playing = true
                        }
                    },
                    enabled = prepared,
                ) {
                    Icon(
                        imageVector = if (playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (playing) "Jeda audio" else "Putar audio",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!prepared) {
                Text(
                    text = "Menyiapkan audio…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun PdfPreview(path: String, modifier: Modifier) {
    val pages by produceState<List<ImageBitmap>?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) { renderPdfPages(path) }
    }
    val rendered = pages

    when {
        rendered == null -> Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "Merender halaman PDF…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        rendered.isEmpty() -> PlatformPreviewMessage(
            text = "PDF ini tidak bisa ditampilkan. Unduh untuk membukanya di aplikasi lain.",
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rendered) { page ->
                Image(
                    bitmap = page,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

@Composable
private fun PlatformPreviewMessage(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun renderPdfPages(path: String): List<ImageBitmap> = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching emptyList()

    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val pages = mutableListOf<ImageBitmap>()
            val count = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)
            for (index in 0 until count) {
                renderer.openPage(index).use { page ->
                    val scale = (PDF_RENDER_WIDTH.toFloat() / page.width.coerceAtLeast(1))
                        .coerceAtMost(2f)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    // PdfRenderer leaves untouched pixels transparent, which reads as
                    // black pages in dark mode, so the page starts white.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pages.add(bitmap.asImageBitmap())
                }
            }
            pages
        }
    }
}.getOrDefault(emptyList())
