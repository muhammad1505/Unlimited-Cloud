package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

actual fun createGallerySyncSource(): GallerySyncSource = DesktopGallerySyncSource

/** Desktop has no SAF tree to scan; the settings card explains this. */
private object DesktopGallerySyncSource : GallerySyncSource {
    override val supported: Boolean = false

    override suspend fun pendingPhotos(
        folder: GalleryFolder,
        alreadySynced: Set<String>,
        limit: Int,
    ): List<GalleryPhoto> = emptyList()

    override fun release(photos: List<GalleryPhoto>) = Unit
}

@Composable
actual fun rememberGalleryFolderPicker(onPicked: (GalleryFolder?) -> Unit): () -> Unit {
    val result by rememberUpdatedState(onPicked)
    return remember { { result(null) } }
}
