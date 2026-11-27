package com.unlimitedcloud.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Keeps the process alive while a transfer batch runs.
 *
 * Without a foreground service, removing the app from recents killed the process
 * mid-upload: the transfer stopped while its ongoing notification stayed in the
 * shade. The service reuses the notifier's notification id, so live progress
 * keeps updating the same entry, and the entry disappears with the service even
 * when the process is killed.
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = transferProgressNotification(
            context = this,
            title = "Transfer berjalan",
            message = "Berkas sedang dipindahkan ke Telegram…",
        )
        // startForeground must happen regardless of the notification permission:
        // the service is the keep-alive, the notification is just its surface.
        runCatching { startForeground(TRANSFER_PROGRESS_ID, notification) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
}
