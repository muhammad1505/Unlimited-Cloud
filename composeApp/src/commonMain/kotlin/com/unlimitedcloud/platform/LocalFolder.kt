package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/** One file copied out of a picked folder, ready for TDLib to upload. */
data class LocalFolderFile(
    /** Path inside the picked folder, `/`-separated, e.g. `Liburan/pantai.jpg`. */
    val relativePath: String,
    /** Where the copy sits locally, already readable by TDLib. */
    val localPath: String,
)

/** Files copied out of a folder the user picked, ready for TDLib to upload. */
data class LocalFolderSelection(
    /** Folder name; becomes the virtual folder when the user picks none. */
    val name: String,
    /**
     * Every file of the tree, subfolders included. The relative path is kept so
     * the upload can recreate the same folder structure instead of flattening
     * a year of photos into one topic.
     */
    val files: List<LocalFolderFile>,
)

/**
 * Opens the platform folder picker.
 *
 * Android copies the tree into the cache because TDLib uploads from a real
 * path; Desktop hands out absolute paths, which it already has. `null` means
 * the user cancelled.
 */
@Composable
expect fun rememberLocalFolderPicker(onPicked: (LocalFolderSelection?) -> Unit): () -> Unit
