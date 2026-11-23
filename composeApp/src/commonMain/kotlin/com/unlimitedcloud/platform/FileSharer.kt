package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/** Outcome of a share attempt, shown as a transient message by the caller. */
sealed interface ShareResult {
    data object Shared : ShareResult
    data object Cancelled : ShareResult
    data class Failed(val message: String) : ShareResult
}

/** One file to hand to the platform share sheet. */
data class ShareRequest(
    val localPath: String,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
)

/**
 * Returns a callback that hands a local file to the system share sheet.
 *
 * Android copies the file into a FileProvider-backed cache and starts a
 * chooser. Desktop puts the file on the clipboard as a file list, which every
 * major file manager and mail client can paste.
 */
@Composable
expect fun rememberFileSharer(onResult: (ShareResult) -> Unit): (ShareRequest) -> Unit
