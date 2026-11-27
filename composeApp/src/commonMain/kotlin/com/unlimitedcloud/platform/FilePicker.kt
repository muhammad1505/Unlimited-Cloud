package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * Returns a callback that opens the platform file picker.
 *
 * The chosen files' local paths are delivered to [onPicked], or an empty list
 * when the user cancels. Android copies each picked document into the cache
 * directory so TDLib can upload from real paths; the platform picker allows
 * selecting several documents in one go.
 */
@Composable
expect fun rememberLocalFilePicker(onPicked: (List<String>) -> Unit): () -> Unit

/**
 * Whether the paths the pickers hand out are copies the app made itself instead
 * of the user's own files.
 *
 * Android copies every pick into the cache so TDLib has a real path to upload
 * from; desktop uploads from where the file already lives. Cleanup after an
 * upload may only delete the former: on desktop the picked path can be the only
 * copy of a file the user has.
 */
expect val pickedFilesAreAppCopies: Boolean
