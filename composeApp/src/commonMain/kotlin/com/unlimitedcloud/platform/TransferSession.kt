package com.unlimitedcloud.platform

/**
 * Keeps a running batch alive while the app is not in front of the user.
 *
 * Android needs a foreground service for this: removing the app from recents
 * otherwise kills the process and the upload stops halfway, while its ongoing
 * notification stays in the shade. Desktop has no such process limit.
 */
interface TransferSession {
    /** Starts the keep-alive; calling it twice for one batch is harmless. */
    fun begin()

    /** Releases the keep-alive once the batch ends, however it ended. */
    fun end()
}

expect fun createTransferSession(): TransferSession
