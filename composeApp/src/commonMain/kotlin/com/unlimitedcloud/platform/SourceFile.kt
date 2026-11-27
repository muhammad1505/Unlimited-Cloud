package com.unlimitedcloud.platform

/**
 * Whether this platform can remove the user's own copy of an uploaded file
 * through a recoverable route: the desktop system trash, or an Android document
 * provider that trashes what it deletes.
 */
expect val sourceFileDeletionSupported: Boolean

/**
 * Removes one original file after it is safely stored in Telegram, without
 * permanently destroying it where the platform knows better.
 *
 * [localPath] is the path the app uploaded from, [documentUri] the Storage
 * Access Framework document when the file came from a folder the user granted.
 * Android refuses a pick without a document uri, because a file chosen one by
 * one only carries a one-shot read grant; desktop moves the file to the system
 * trash instead of unlinking it.
 *
 * Returns true only when the file is really gone from its original place.
 */
expect fun deleteSourceFile(localPath: String, documentUri: String?): Boolean
