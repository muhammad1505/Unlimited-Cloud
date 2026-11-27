package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * Posts system notifications for transfers.
 *
 * One ongoing notification follows a running batch (it is updated, never
 * duplicated) and a separate one reports each finished or failed batch, so the
 * status bar shows what is happening while the app is in the background.
 * Desktop has no such surface and reports `supported = false`.
 */
interface TransferNotifier {
    val supported: Boolean

    /**
     * False when the user blocked the app's notifications or never granted the
     * Android 13 permission. Callers keep the in-app feedback either way.
     */
    val enabled: Boolean

    /** One notification per finished or failed batch. */
    fun notify(title: String, message: String)

    /** Reuses a single ongoing notification for the batch that is running. */
    fun progress(title: String, message: String)

    /** Removes the ongoing notification once a batch stops. */
    fun clearProgress()

    /** Opens the system notification settings; false when that is not possible. */
    fun openSettings(): Boolean
}

expect fun createTransferNotifier(): TransferNotifier

/**
 * Returns a callback that asks for the notification permission where the
 * platform needs one (Android 13+). It is a no-op when already granted, on
 * older Android releases, and on Desktop. [onResult] runs after the dialog is
 * answered, so the UI can refresh whether notifications may be posted.
 */
@Composable
expect fun rememberNotificationPermissionRequest(onResult: () -> Unit = {}): () -> Unit
