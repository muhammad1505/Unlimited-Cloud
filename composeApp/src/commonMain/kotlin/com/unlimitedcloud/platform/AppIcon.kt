package com.unlimitedcloud.platform

/**
 * Path to a PNG of the app's own launcher icon.
 *
 * Telegram chat photos can only be set from a file, so the platform icon is
 * written to the app data directory first: Android renders the (adaptive)
 * launcher drawable into a square bitmap, desktop copies the packaged
 * `icon.png`. Returns `null` when the icon cannot be materialised; the storage
 * group then simply keeps the photo it already has.
 */
expect suspend fun appIconFilePath(): String?
