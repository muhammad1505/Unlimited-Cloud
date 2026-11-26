package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * Posts one system notification when a transfer finishes or fails.
 *
 * Only final states are reported: progress updates already show in the app and
 * would otherwise turn the notification shade into a second progress bar.
 * Desktop has no such surface and reports `supported = false`.
 */
interface TransferNotifier {
    val supported: Boolean

    fun notify(title: String, message: String)
}

expect fun createTransferNotifier(): TransferNotifier

/**
 * Returns a callback that asks for the notification permission where the
 * platform needs one (Android 13+). It is a no-op when already granted, on
 * older Android releases, and on Desktop.
 */
@Composable
expect fun rememberNotificationPermissionRequest(): () -> Unit
