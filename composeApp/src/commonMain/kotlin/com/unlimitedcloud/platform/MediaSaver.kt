package com.unlimitedcloud.platform

/**
 * Copies a downloaded file somewhere the user can open outside the app.
 *
 * Android writes images into the photo library and everything else into
 * `Download/Unlimited Cloud` through MediaStore, which needs no storage
 * permission on Android 10+. Desktop copies into `~/Downloads/Unlimited Cloud`.
 */
interface MediaSaver {
    /** True when the platform has a photo library to write into. */
    val supported: Boolean

    /** Returns true when the image was inserted into the system gallery. */
    suspend fun saveImage(localPath: String, displayName: String, mimeType: String): Boolean

    /**
     * Saves any file into the downloads folder and returns the location shown
     * to the user, or null when the platform could not write it.
     */
    suspend fun saveFile(localPath: String, displayName: String, mimeType: String): String?
}

expect fun createMediaSaver(): MediaSaver
