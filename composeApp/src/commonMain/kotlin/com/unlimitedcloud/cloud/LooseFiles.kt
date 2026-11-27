package com.unlimitedcloud.cloud

/**
 * Chooses the messages one tidy pass moves into their type topic.
 *
 * A message stays a candidate while it carries no folder caption: once its copy
 * has one, the file is filed for good and must never be forwarded again. With
 * [tidyEverything] the whole history is taken (first pass after the feature
 * arrived, or the manual button); otherwise only the ids remembered from earlier
 * failures are retried, so a launch does not copy files that already failed for
 * a permanent reason.
 */
fun tidyTargets(
    files: List<CloudFile>,
    pending: Set<Long>,
    tidyEverything: Boolean,
): List<CloudFile> = files.filter { file ->
    !file.folderFromCaption && (tidyEverything || file.messageId in pending)
}
