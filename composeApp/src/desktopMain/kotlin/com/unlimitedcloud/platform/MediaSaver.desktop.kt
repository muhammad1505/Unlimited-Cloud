package com.unlimitedcloud.platform

actual fun createMediaSaver(): MediaSaver = DesktopMediaSaver

private object DesktopMediaSaver : MediaSaver {
    override val supported: Boolean = false

    override suspend fun saveImage(
        localPath: String,
        displayName: String,
        mimeType: String,
    ): Boolean = false
}
