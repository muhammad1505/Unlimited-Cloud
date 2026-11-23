package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/** Files copied out of a folder the user picked, ready for TDLib to upload. */
data class LocalFolderSelection(
    /** Folder name; becomes the virtual folder when the user picks none. */
    val name: String,
    /** Local paths of the files inside the folder, already readable by TDLib. */
    val files: List<String>,
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
