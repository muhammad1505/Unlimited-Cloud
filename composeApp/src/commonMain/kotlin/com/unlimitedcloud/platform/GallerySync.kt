package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * A folder chosen through the platform picker: a SAF tree on Android, an
 * absolute directory path on Desktop.
 */
data class GalleryFolder(
    val id: String,
    val name: String,
)

/** One file copied into the app cache, ready for TDLib to upload. */
data class GalleryFile(
    /** Stable key stored after a successful upload, e.g. `name|documentId`. */
    val key: String,
    val name: String,
    val localPath: String,
    val sizeBytes: Long,
    /**
     * Subfolder inside the picked tree, `/`-separated and empty for the root,
     * so the upload can recreate `Galeri/Liburan/Pantai` instead of flattening
     * every file into one topic.
     */
    val relativeDirectory: String = "",
)

/**
 * One scan result: the files ready to upload plus the keys that could not be
 * prepared. Android reports the latter when a document is unreadable or does not
 * fit the cache budget; Desktop copies nothing, so it is always empty there.
 */
data class GalleryBatch(
    val files: List<GalleryFile> = emptyList(),
    val skippedKeys: List<String> = emptyList(),
)

/**
 * Reads files from a user-picked folder without any storage permission.
 *
 * Every document counts, whatever its type: images, documents, archives,
 * video, audio, installers. Android implements this with the Storage Access
 * Framework, where the user grants one folder, the grant survives restarts, and
 * the app only ever reads what is inside it. Desktop walks the picked directory
 * on disk and uploads the files in place.
 */
interface GallerySyncSource {
    val supported: Boolean

    suspend fun pendingFiles(
        folder: GalleryFolder,
        alreadySynced: Set<String>,
        limit: Int,
    ): GalleryBatch

    /** Removes the temporary cache copies once TDLib finished with them. */
    fun release(files: List<GalleryFile>)
}

expect fun createGallerySyncSource(): GallerySyncSource

/** Opens the platform folder picker; `null` means the user cancelled. */
@Composable
expect fun rememberGalleryFolderPicker(onPicked: (GalleryFolder?) -> Unit): () -> Unit
