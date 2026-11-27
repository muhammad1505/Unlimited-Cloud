package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop reports transfers through the tray icon: the tooltip follows the
 * running batch and a balloon message announces the result. The in-window banner
 * stays the primary feedback; the tray is what the user sees after closing the
 * window.
 */
actual fun createTransferNotifier(): TransferNotifier = DesktopTransferNotifier

private object DesktopTransferNotifier : TransferNotifier {
    override val supported: Boolean get() = DesktopTray.supported

    /** A tray icon has no permission gate: supported means it can post. */
    override val enabled: Boolean get() = DesktopTray.supported

    override fun notify(title: String, message: String) = DesktopTray.notify(title, message)

    override fun progress(title: String, message: String, fraction: Float?) =
        DesktopTray.showProgress(title, message)

    override fun clearProgress() = DesktopTray.clearProgress()

    override fun openSettings(): Boolean = false
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit =
    remember { { onResult() } }
