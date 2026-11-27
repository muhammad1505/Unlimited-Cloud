package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop keeps process feedback inside the window: the shell already shows a
 * notification banner, and a tray/toast integration would need a dependency the
 * app does not otherwise use.
 */
actual fun createTransferNotifier(): TransferNotifier = NoopTransferNotifier

private object NoopTransferNotifier : TransferNotifier {
    override val supported: Boolean = false
    override val enabled: Boolean = false

    override fun notify(title: String, message: String) = Unit
    override fun progress(title: String, message: String) = Unit
    override fun clearProgress() = Unit
    override fun openSettings(): Boolean = false
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit =
    remember { { onResult() } }
