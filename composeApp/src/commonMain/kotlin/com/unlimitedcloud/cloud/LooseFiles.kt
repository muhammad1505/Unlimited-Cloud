package com.unlimitedcloud.cloud

/** One message a tidy pass moves, together with the topic it belongs in. */
data class TidyMove(val file: CloudFile, val folder: String)

/**
 * Topic a message belongs in, or null when it is already filed.
 *
 * A message without a folder caption lives in General, so it always belongs to
 * the topic of its file type. Messages that do carry a caption are left alone,
 * except the ones the folder sync used to file under "Galeri": once automatic
 * filing is on, a video sitting there is moved to "Video" (keeping the relative
 * path, so "Galeri/Liburan" becomes "Video/Liburan"). Without that second rule
 * every video uploaded before per-type filing stayed in the gallery topic
 * forever, because its caption made it look already filed.
 */
fun tidyTarget(file: CloudFile, autoTopics: Boolean): String? {
    val folder = file.folder
    val type = FileCategories.folderFor(file.name)
    if (!file.folderFromCaption || folder == null) return type
    if (!autoTopics) return null

    val gallery = FileCategories.GALLERY
    val isGallery = folder == gallery || folder.startsWith("$gallery/")
    if (!isGallery) return null

    // Keep the subfolder the sync recreated: "Galeri/Liburan" → "Video/Liburan".
    val relative = folder.removePrefix(gallery)
    return (type + relative).takeIf { it != folder }
}

/**
 * Chooses the messages one tidy pass moves.
 *
 * A message stays a candidate while it has no folder caption; gallery messages
 * whose type topic differs join them so the old gallery uploads are re-filed
 * once. With [tidyEverything] the whole history is taken (first pass after the
 * feature arrived, or the manual button); otherwise only the ids remembered from
 * earlier failures are retried, so a launch does not copy files that already
 * failed for a permanent reason.
 */
fun tidyTargets(
    files: List<CloudFile>,
    pending: Set<Long>,
    tidyEverything: Boolean,
    autoTopics: Boolean,
): List<TidyMove> = files.mapNotNull { file ->
    if (!tidyEverything && file.messageId !in pending) return@mapNotNull null
    tidyTarget(file, autoTopics)?.let { folder -> TidyMove(file, folder) }
}
