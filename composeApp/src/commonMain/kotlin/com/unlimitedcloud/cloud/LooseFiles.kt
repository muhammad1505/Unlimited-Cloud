package com.unlimitedcloud.cloud

/** One message a tidy pass moves, together with the topic it belongs in. */
data class TidyMove(val file: CloudFile, val folder: String)

/**
 * Topic a message belongs in, or null when it is already filed.
 *
 * The type always comes from the file extension, so no caption can keep a video
 * in the photo topic. A message without a folder caption lives in General and
 * belongs to its type topic. A message that does carry one stays put unless the
 * path starts with a topic this app made: "Galeri" (what the folder sync used
 * before per-type filing) and the type topics themselves. The relative path is
 * kept, so "Galeri/Liburan" becomes "Video/Liburan" and an .mp4 misfiled under
 * "Foto/Liburan" moves to "Video/Liburan". Folders the user named themselves
 * ("Kerja", "Proyek/2026") are never touched.
 */
fun tidyTarget(file: CloudFile, autoTopics: Boolean): String? {
    val folder = file.folder
    val type = FileCategories.folderFor(file.name)
    if (!file.folderFromCaption || folder == null) return type
    if (!autoTopics) return null

    // Only paths that begin with an app-made topic are ours to correct.
    val generated = FileCategories.generatedTopicOf(folder) ?: return null
    if (generated == type) return null

    // Keep whatever sat under the old topic: "Galeri/Liburan" → "Video/Liburan".
    val slash = folder.indexOf('/')
    val relative = if (slash < 0) "" else folder.substring(slash)
    return type + relative
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
