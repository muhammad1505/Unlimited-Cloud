package com.unlimitedcloud.platform

/**
 * Copies a downloaded image into the device photo library.
 *
 * Android uses MediaStore, which needs no storage permission on Android 10+
 * because the app only writes into its own collection. Desktop reports
 * [supported] as false.
 */
interface MediaSaver {
    val supported: Boolean

    /** Returns true when the image was inserted into the system gallery. */
    suspend fun saveImage(localPath: String, displayName: String, mimeType: String): Boolean
}

expect fun createMediaSaver(): MediaSaver
