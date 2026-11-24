package com.unlimitedcloud.cloud

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePagingTest {

    private fun file(id: Long) = CloudFile(
        messageId = id,
        fileId = id.toInt(),
        name = "file-$id",
        size = id,
        folder = null,
    )

    private fun page(vararg ids: Long, next: Long? = null) =
        CloudPage(files = ids.map { file(it) }, nextFromMessageId = next)

    @Test
    fun appendDropsTheBoundaryMessageThatTdlibRepeats() {
        val existing = listOf(file(3), file(2))

        val merged = appendPage(existing, page(2, 1, next = 1))

        assertEquals(listOf(3L, 2L, 1L), merged.map { it.messageId })
    }

    @Test
    fun appendKeepsTheListUntouchedForAnEmptyPage() {
        val existing = listOf(file(3), file(2))

        assertEquals(existing, appendPage(existing, page()))
    }

    @Test
    fun collectFollowsTheCursorAndStopsAtTheEndOfTheHistory() = runTest {
        val pages = mapOf(
            0L to page(9, 8, next = 8),
            8L to page(8, 7, next = 7),
            7L to page(7),
        )
        var requests = 0

        val loaded = collectPages(fromMessageId = 0L, maxPages = PAGES_PER_MORE) { cursor ->
            requests++
            pages.getValue(cursor)
        }

        assertEquals(listOf(9L, 8L, 7L), loaded.files.map { it.messageId })
        assertNull(loaded.nextFromMessageId)
        assertEquals(3, requests)
    }

    @Test
    fun collectLeavesTheCursorForTheNextRequestWhenTheBudgetRunsOut() = runTest {
        val pages = mapOf(
            0L to page(5, 4, next = 4),
            4L to page(3, 2, next = 2),
            2L to page(1),
        )
        var requests = 0

        val loaded = collectPages(fromMessageId = 0L, maxPages = 2) { cursor ->
            requests++
            pages.getValue(cursor)
        }

        assertEquals(listOf(5L, 4L, 3L, 2L), loaded.files.map { it.messageId })
        assertEquals(2L, loaded.nextFromMessageId)
        assertEquals(2, requests)
    }

    @Test
    fun collectStopsWhenTheCursorDoesNotAdvance() = runTest {
        var requests = 0

        val loaded = collectPages(fromMessageId = 42L, maxPages = PAGES_PER_MORE) {
            requests++
            page(next = 42L)
        }

        assertEquals(emptyList(), loaded.files)
        assertNull(loaded.nextFromMessageId)
        assertEquals(1, requests)
    }

    @Test
    fun collectKeepsPagingThroughPagesThatHoldNoFiles() = runTest {
        val pages = mapOf(
            0L to page(next = 6),
            6L to page(5, next = null),
        )

        val loaded = collectPages(fromMessageId = 0L, maxPages = PAGES_PER_MORE) { cursor ->
            pages.getValue(cursor)
        }

        assertEquals(listOf(5L), loaded.files.map { it.messageId })
        assertNull(loaded.nextFromMessageId)
    }

    @Test
    fun deletingIsOnlySafeWhenEveryHistoryPageIsLoaded() {
        val loaded = ExplorerUiState(loading = false, canLoadMore = false, nextFromMessageId = null)

        assertTrue(isHistoryComplete(loaded))
        assertFalse(isHistoryComplete(loaded.copy(loading = true)))
        assertFalse(isHistoryComplete(loaded.copy(loadingMore = true)))
        assertFalse(isHistoryComplete(loaded.copy(canLoadMore = true)))
        assertFalse(isHistoryComplete(loaded.copy(nextFromMessageId = 7L)))
    }

    @Test
    fun trimmingTransfersKeepsRunningEntries() {
        val running = (1..3).map { index ->
            TransferItem(id = "r$index", fileName = "r", direction = TransferDirection.Download)
        }
        val finished = (1..5).map { index ->
            TransferItem(
                id = "f$index",
                fileName = "f",
                direction = TransferDirection.Download,
                status = TransferStatus.Completed,
            )
        }

        val trimmed = trimTransfers(running + finished, limit = 4)

        assertEquals(listOf("r1", "r2", "r3", "f1"), trimmed.map { it.id })
    }

    @Test
    fun messagesAreDeletedInBatchesTdlibAccepts() {
        val batches = deleteBatches((1L..250L).toList())

        assertEquals(listOf(100, 100, 50), batches.map { it.size })
        assertEquals(MESSAGES_PER_DELETE, batches.first().size)
        assertTrue(deleteBatches(emptyList()).isEmpty())
    }
}
