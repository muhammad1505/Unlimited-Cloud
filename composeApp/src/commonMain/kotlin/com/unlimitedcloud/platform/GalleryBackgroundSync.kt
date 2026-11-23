package com.unlimitedcloud.platform

/**
 * Schedules the gallery sync while the app is not in the foreground.
 *
 * Android implements this with WorkManager: a periodic job starts TDLib, waits
 * for the saved session and uploads one small incremental batch. Desktop
 * reports [supported] as false and never schedules anything.
 */
interface GalleryBackgroundSync {
    val supported: Boolean

    /** Registers or updates the periodic job; safe to call repeatedly. */
    fun schedule()

    /** Removes the periodic job when the feature or the folder is turned off. */
    fun cancel()
}

expect fun createGalleryBackgroundSync(): GalleryBackgroundSync
