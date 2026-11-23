package com.unlimitedcloud.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.cloud.CloudFile
import com.unlimitedcloud.platform.decodeImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover thumbnail for a file card. TDLib keeps a small preview image next to
 * each document; when it exists the card shows it, otherwise the file type
 * icon stays. Covers are read from TDLib's cache, never from a full download.
 */
@Composable
fun FileCover(
    file: CloudFile,
    loadCover: suspend (CloudFile) -> String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    revision: Int = 0,
) {
    val path by produceState<String?>(initialValue = null, key1 = file.messageId, key2 = revision) {
        value = loadCover(file)
    }
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = path?.let { withContext(Dispatchers.Default) { decodeImageFile(it) } }
    }
    val cover = image

    if (cover == null) {
        FileKindIcon(kind = cloudFileKind(file.name), modifier = modifier, size = size)
    } else {
        Image(
            bitmap = cover,
            contentDescription = file.name,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
