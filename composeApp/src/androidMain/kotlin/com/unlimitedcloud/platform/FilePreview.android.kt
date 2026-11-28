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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unlimitedcloud.ui.components.AudioPlayerControls
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
            AudioPreview(path = path, modifier = modifier)
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
private fun AudioPreview(path: String, modifier: Modifier) {
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val error = failure
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        } else {
            AudioPlayerControls(
                positionMs = positionMs,
                durationMs = durationMs,
                playing = playing,
                enabled = prepared,
                onSeek = { target ->
                    if (prepared) {
                        runCatching { player.seekTo(target) }
                        positionMs = target
                    }
                },
                onTogglePlay = {
                    if (prepared) {
                        if (playing) {
                            player.pause()
                            playing = false
                        } else {
                            player.start()
                            playing = true
                        }
                    }
                },
            )
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

@Composable
private fun PdfPreview(path: String, modifier: Modifier) {
    // Only the page count is read up front; every page renders itself when the
    // list reaches it.
    val info by produceState<PdfInfo?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) { pdfInfo(path) }
    }
    val rendered = info

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

        rendered.shownPages == 0 -> PlatformPreviewMessage(
            text = "PDF ini tidak bisa ditampilkan. Unduh untuk membukanya di aplikasi lain.",
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rendered.shownPages) { index -> PdfPage(path, index) }
            if (rendered.totalPages > rendered.shownPages) {
                item {
                    Text(
                        text = "Menampilkan ${rendered.shownPages} dari " +
                            "${rendered.totalPages} halaman. Unduh untuk membuka semuanya.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * One page of the document. Only the pages the list actually shows are
 * rendered: the preview used to decode 40 pages at 1440 px before the first one
 * became visible, which cost hundreds of megabytes of bitmaps for a file the
 * user may never scroll through.
 */
@Composable
private fun PdfPage(path: String, index: Int) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path, key2 = index) {
        value = withContext(Dispatchers.IO) { renderPdfPage(path, index) }
    }
    val page = bitmap
    if (page == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
    } else {
        Image(
            bitmap = page,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
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

private data class PdfInfo(val shownPages: Int, val totalPages: Int)

/** Page count of the document, capped to what the preview is willing to show. */
private fun pdfInfo(path: String): PdfInfo? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null

    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val total = renderer.pageCount
            PdfInfo(shownPages = total.coerceAtMost(MAX_PDF_PAGES), totalPages = total)
        }
    }
}.getOrNull()

/**
 * Renders one page. A renderer is opened per page because PdfRenderer is not
 * thread safe and the list may scroll while the previous page still draws.
 */
private fun renderPdfPage(path: String, index: Int): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null

    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (index !in 0 until renderer.pageCount) return@runCatching null
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
                bitmap.asImageBitmap()
            }
        }
    }
}.getOrNull()
