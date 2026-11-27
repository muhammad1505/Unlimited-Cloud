package com.unlimitedcloud.platform

import android.Manifest
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

/**
 * One ongoing notification for the running batch and one per finished or failed
 * batch, in a dedicated channel so the user can silence them without muting the
 * app entirely. Tapping any of them opens the app; nothing runs in the
 * background.
 */
private class AndroidTransferNotifier(private val context: Context) : TransferNotifier {

    override val supported: Boolean = true

    override val enabled: Boolean
        get() = canPost() && NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun notify(title: String, message: String) {
        if (!canPost()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(appIcon())
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { contentIntent()?.let { setContentIntent(it) } }
            .build()

        // Posting can still be refused (permission revoked, channel disabled);
        // that must never take a finished transfer down with it.
        runCatching { NotificationManagerCompat.from(context).notify(nextId(), notification) }
    }

    override fun progress(title: String, message: String) {
        if (!canPost()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(appIcon())
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            // Indeterminate on purpose: TDLib reports one fraction per file, and
            // a bar jumping backwards between files is worse than a spinner.
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { contentIntent()?.let { setContentIntent(it) } }
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(PROGRESS_ID, notification) }
    }

    override fun clearProgress() {
        runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_ID) }
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

    private fun contentIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Proses transfer",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Progres dan hasil unggahan, unduhan, serta sinkron folder."
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "transfers"

        /** Stable id: every update reuses the same ongoing notification. */
        const val PROGRESS_ID = 997

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
