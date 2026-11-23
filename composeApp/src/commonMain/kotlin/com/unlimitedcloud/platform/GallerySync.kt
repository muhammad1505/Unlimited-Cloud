package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/** A gallery folder chosen through the platform picker (a SAF tree on Android). */
data class GalleryFolder(
    val id: String,
    val name: String,
)

/** One image copied into the app cache, ready for TDLib to upload. */
data class GalleryPhoto(
    /** Stable key stored after a successful upload, e.g. `name|documentId`. */
    val key: String,
    val name: String,
    val localPath: String,
    val sizeBytes: Long,
)

/**
 * Reads images from a user-picked gallery folder without any storage permission.
 *
 * Android implements this with the Storage Access Framework: the user grants
 * one folder, the grant survives restarts, and the app only ever reads what is
 * inside it. Desktop reports `supported = false`.
 */
interface GallerySyncSource {
    val supported: Boolean

    suspend fun pendingPhotos(
        folder: GalleryFolder,
        alreadySynced: Set<String>,
        limit: Int,
    ): List<GalleryPhoto>

    /** Removes the temporary cache copies once TDLib finished with them. */
    fun release(photos: List<GalleryPhoto>)
}

expect fun createGallerySyncSource(): GallerySyncSource

/** Opens the platform folder picker; `null` means the user cancelled. */
@Composable
expect fun rememberGalleryFolderPicker(onPicked: (GalleryFolder?) -> Unit): () -> Unit
