package com.unlimitedcloud.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unlimitedcloud.cloud.CloudFile
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.platform.decodeImageFile
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.cloudFileKind
import com.unlimitedcloud.ui.components.mimeTypeFor
import com.unlimitedcloud.ui.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

private const val PREVIEW_TEXT_LIMIT = 64 * 1024L

private val previewTextExtensions = setOf(
    "txt", "md", "log", "json", "csv", "xml", "yaml", "yml", "ini", "cfg",
    "kt", "kts", "java", "py", "js", "ts", "html", "css", "sh", "sql",
    "c", "cpp", "h", "go", "rs", "rb", "php",
)

/** Images and plain text render inside the app; other types keep the download flow. */
fun canPreview(fileName: String): Boolean =
    mimeTypeFor(fileName).startsWith("image/") ||
        fileName.substringAfterLast('.', "").lowercase() in previewTextExtensions

/**
 * Shows a file straight from TDLib's local cache: [CloudFilesViewModel.preview]
 * fetches the bytes, and nothing is copied to Downloads or the gallery. Images
 * are decoded downsampled; text is read up to [PREVIEW_TEXT_LIMIT] bytes.
 */
@Composable
fun FilePreviewDialog(
    file: CloudFile,
    viewModel: CloudFilesViewModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    val previewable = remember(file.name) { canPreview(file.name) }
    var localPath by remember(file.messageId) { mutableStateOf<String?>(null) }
    var failure by remember(file.messageId) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.messageId) {
        if (!previewable) return@LaunchedEffect
        viewModel.preview(
            file = file,
            onLoaded = { path -> localPath = path },
            onFailed = { message -> failure = message },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 24.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${cloudFileKind(file.name).label} · ${formatBytes(file.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = "Tutup pratinjau",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        !previewable -> Text(
                            text = "Jenis berkas ini belum bisa dipratinjau. " +
                                "Unduh atau bagikan untuk membukanya di aplikasi lain.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        failure != null -> Text(
                            text = failure.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )

                        localPath == null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Menyiapkan pratinjau…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> PreviewContent(file = file, path = localPath.orEmpty())
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            onDownload()
                            onDismiss()
                        },
                    ) {
                        Icon(
                            imageVector = AppIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Unduh")
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            onShare()
                            onDismiss()
                        },
                    ) {
                        Icon(
                            imageVector = AppIcons.Share,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Bagikan")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewContent(file: CloudFile, path: String) {
    if (mimeTypeFor(file.name).startsWith("image/")) {
        val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
            value = withContext(Dispatchers.Default) { decodeImageFile(path) }
        }
        val image = bitmap
        if (image == null) {
            Text(
                text = "Gambar ini tidak bisa ditampilkan. Unduh untuk membukanya.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Image(
                bitmap = image,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        val text by produceState<String?>(initialValue = null, key1 = path) {
            value = withContext(Dispatchers.Default) { readTextPreview(path) }
        }
        val content = text
        if (content == null) {
            Text(
                text = "Teks ini tidak bisa dibaca. Unduh untuk membukanya.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun readTextPreview(path: String): String? = runCatching {
    val file = path.toPath()
    val size = FileSystem.SYSTEM.metadata(file).size ?: 0L
    FileSystem.SYSTEM.read(file) {
        readUtf8(size.coerceAtMost(PREVIEW_TEXT_LIMIT))
    }
}.getOrNull()
