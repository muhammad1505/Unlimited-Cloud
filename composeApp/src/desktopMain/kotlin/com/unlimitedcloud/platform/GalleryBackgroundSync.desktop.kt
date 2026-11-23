package com.unlimitedcloud.platform

actual fun createGalleryBackgroundSync(): GalleryBackgroundSync = DesktopGalleryBackgroundSync

private object DesktopGalleryBackgroundSync : GalleryBackgroundSync {
    override val supported: Boolean = false

    override fun schedule() = Unit

    override fun cancel() = Unit
}
