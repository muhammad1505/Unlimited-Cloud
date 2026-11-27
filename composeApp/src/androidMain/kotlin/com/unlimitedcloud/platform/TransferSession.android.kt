package com.unlimitedcloud.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

actual fun createTransferSession(): TransferSession =
    AndroidTransferSession(AndroidPlatform.requireContext())

private class AndroidTransferSession(private val context: Context) : TransferSession {

    override fun begin() {
        // Can be refused when the caller itself runs in the background (the
        // gallery worker): the batch then simply has no keep-alive, and the
        // worker retries it later.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TransferService::class.java),
            )
        }
    }

    override fun end() {
        runCatching { context.stopService(Intent(context, TransferService::class.java)) }
    }
}
