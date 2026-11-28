package com.unlimitedcloud.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

actual fun createTransferSession(): TransferSession =
    AndroidTransferSession(AndroidPlatform.requireContext())

/**
 * One foreground service for the whole process, however many batches run.
 *
 * A session is created per call site, so the count lives in this class: with
 * parallel transfers the first batch to finish stopped the service while the
 * other two were still uploading, and those were left without a keep-alive.
 */
private class AndroidTransferSession(private val context: Context) : TransferSession {

    override fun begin() {
        // The service is already up for the batches before this one.
        if (activeBatches.incrementAndGet() > 1) return
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
        if (activeBatches.decrementAndGet() > 0) return
        // Never below zero: an unmatched end must not let a later batch skip
        // starting the service again.
        activeBatches.set(0)
        runCatching { context.stopService(Intent(context, TransferService::class.java)) }
    }

    private companion object {
        /** Batches this process is still keeping alive. */
        val activeBatches = AtomicInteger(0)
    }
}
