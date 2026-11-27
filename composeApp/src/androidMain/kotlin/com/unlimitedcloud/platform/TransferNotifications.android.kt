package com.unlimitedcloud.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

actual fun createTransferNotifier(): TransferNotifier =
    AndroidTransferNotifier(AndroidPlatform.requireContext())

/** Id shared by the notifier and [TransferService]: both update one entry. */
internal const val TRANSFER_PROGRESS_ID = 997

private const val TRANSFER_CHANNEL_ID = "transfers"

/** Creates the transfer channel on first use; a no-op on older Android. */
internal fun ensureTransferChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(TRANSFER_CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            TRANSFER_CHANNEL_ID,
            "Proses transfer",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Progres dan hasil unggahan, unduhan, serta sinkron folder."
        },
    )
}

/**
 * The ongoing progress notification. [TransferService] posts the same one as its
 * foreground notification, so the shade shows one live entry per batch.
 */
internal fun transferProgressNotification(
    context: Context,
    title: String,
    message: String,
    fraction: Float? = null,
): Notification {
    ensureTransferChannel(context)
    return NotificationCompat.Builder(context, TRANSFER_CHANNEL_ID)
        .setSmallIcon(context.applicationInfo.icon)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        // A determinate bar shows how much of the whole batch is done; without a
        // fraction the batch length is unknown, so the bar keeps spinning.
        .setProgress(
            100,
            fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: 0,
            fraction == null,
        )
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .apply { contentIntent(context)?.let { setContentIntent(it) } }
        .build()
}

/** Reopens the app when the transfer notification is tapped. */
internal fun contentIntent(context: Context): PendingIntent? {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: return null
    return PendingIntent.getActivity(
        context,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * One ongoing notification for the running batch and one per finished or failed
 * batch, in a dedicated channel so the user can silence them without muting the
 * app entirely. Tapping any of them opens the app.
 */
private class AndroidTransferNotifier(private val context: Context) : TransferNotifier {

    override val supported: Boolean = true

    override val enabled: Boolean
        get() = canPost() && NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun notify(title: String, message: String) {
        if (!canPost()) return
        ensureTransferChannel(context)

        val notification = NotificationCompat.Builder(context, TRANSFER_CHANNEL_ID)
            .setSmallIcon(appIcon())
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { contentIntent(context)?.let { setContentIntent(it) } }
            .build()

        // Posting can still be refused (permission revoked, channel disabled);
        // that must never take a finished transfer down with it.
        runCatching { NotificationManagerCompat.from(context).notify(nextId(), notification) }
    }

    override fun progress(title: String, message: String, fraction: Float?) {
        if (!canPost()) return
        val notification = transferProgressNotification(context, title, message, fraction)
        runCatching {
            NotificationManagerCompat.from(context).notify(TRANSFER_PROGRESS_ID, notification)
        }
    }

    override fun clearProgress() {
        runCatching { NotificationManagerCompat.from(context).cancel(TRANSFER_PROGRESS_ID) }
    }

    override fun openSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** The app's own launcher icon instead of a generic system glyph. */
    private fun appIcon(): Int = context.applicationInfo.icon

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val ids = AtomicInteger(1000)

        fun nextId(): Int = ids.incrementAndGet()
    }
}

@Composable
actual fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val context = AndroidPlatform.requireContext()
    val result by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result() }

    return remember(launcher) {
        {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                result()
            }
        }
    }
}
