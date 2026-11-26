package com.unlimitedcloud.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

actual fun createTransferNotifier(): TransferNotifier =
    AndroidTransferNotifier(AndroidPlatform.requireContext())

/**
 * One notification per finished or failed transfer, in its own low-importance
 * channel so the user can silence them without muting the app entirely.
 * Tapping it opens the app; nothing runs in the background.
 */
private class AndroidTransferNotifier(private val context: Context) : TransferNotifier {

    override val supported: Boolean = true

    override fun notify(title: String, message: String) {
        if (!canPost()) return
        ensureChannel()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()

        // Posting can still be refused (permission revoked, channel disabled);
        // that must never take a finished transfer down with it.
        runCatching { NotificationManagerCompat.from(context).notify(nextId(), notification) }
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
                description = "Hasil unggahan dan unduhan, misalnya selesai atau gagal."
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "transfers"
        val ids = AtomicInteger(1000)

        fun nextId(): Int = ids.incrementAndGet()
    }
}

@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = AndroidPlatform.requireContext()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    return remember(launcher) {
        {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
