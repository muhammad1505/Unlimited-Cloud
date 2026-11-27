package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * Returns a callback that opens the platform image picker.
 *
 * Used for the storage group photo: the chosen image is handed over as a local
 * file path, or `null` when the user cancels. Android copies the picked image
 * into the cache because TDLib uploads from real paths; desktop hands over the
 * file where it already lives.
 */
@Composable
expect fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit
