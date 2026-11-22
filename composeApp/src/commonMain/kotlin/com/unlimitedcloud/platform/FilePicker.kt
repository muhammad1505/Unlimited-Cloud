package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * Returns a callback that opens the platform file picker.
 *
 * The chosen file's local path is delivered to [onPicked], or `null` when the
 * user cancels. Android copies the picked document into the cache directory so
 * TDLib can upload from a real path.
 */
@Composable
expect fun rememberLocalFilePicker(onPicked: (String?) -> Unit): () -> Unit
