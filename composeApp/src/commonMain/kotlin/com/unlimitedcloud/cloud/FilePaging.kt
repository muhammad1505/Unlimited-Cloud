package com.unlimitedcloud.cloud

/** Pages requested by a single "load more" action. */
internal const val PAGES_PER_MORE = 4

/** Full history loaded by one refresh before it stops. */
internal const val PAGES_PER_REFRESH = 20

/** Files of a page run plus the cursor for whatever follows them. */
internal data class LoadedFiles(val files: List<CloudFile>, val nextFromMessageId: Long?)

/**
 * Appends [page] to [existing], dropping anything already known.
 *
 * TDLib repeats the boundary message between two history requests, so a naive
 * concatenation showed the same file twice until the next refresh.
 */
internal fun appendPage(existing: List<CloudFile>, page: CloudPage): List<CloudFile> {
    if (page.files.isEmpty()) return existing
    val known = existing.mapTo(mutableSetOf()) { it.messageId }
    val fresh = page.files.filterNot { known.contains(it.messageId) }
    if (fresh.isEmpty()) return existing
    return existing + fresh
}

/**
 * Reads up to [maxPages] history pages starting at [fromMessageId].
 *
 * Kept free of the view model so pagination can be tested without TDLib: [load]
 * stands in for one `getChatHistory` request.
 */
internal suspend fun collectPages(
    fromMessageId: Long,
    maxPages: Int,
    load: suspend (Long) -> CloudPage,
): LoadedFiles {
    var files = emptyList<CloudFile>()
    var cursor = fromMessageId
    var nextCursor: Long? = null
    var pages = 0

    while (pages < maxPages) {
        val page = load(cursor)
        pages++
        files = appendPage(files, page)

        val pageNext = page.nextFromMessageId
        if (pageNext == null || pageNext == cursor) {
            // No cursor, or the same cursor twice: asking again would loop.
            nextCursor = null
            break
        }
        nextCursor = pageNext
        cursor = pageNext
    }

    return LoadedFiles(files = files, nextFromMessageId = nextCursor)
}
