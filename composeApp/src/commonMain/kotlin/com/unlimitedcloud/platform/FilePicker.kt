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
