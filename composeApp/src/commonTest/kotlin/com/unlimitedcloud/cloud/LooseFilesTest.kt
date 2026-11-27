package com.unlimitedcloud.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LooseFilesTest {

    private fun file(id: Long, name: String, folder: String? = null) = CloudFile(
        messageId = id,
        fileId = id.toInt(),
        name = name,
        size = 1_024L,
        folder = folder ?: FileCategories.folderFor(name),
        folderFromCaption = folder != null,
    )

    @Test
    fun firstPassTakesEveryUncaptionedMessage() {
        val files = listOf(
            file(1, "lama.jpg"),
            file(2, "laporan.pdf"),
            file(3, "rapi.zip", folder = "Arsip"),
        )

        val targets = tidyTargets(files, pending = emptySet(), tidyEverything = true)

        assertEquals(listOf(1L, 2L), targets.map { it.messageId })
    }

    @Test
    fun laterPassesOnlyRetryRememberedIds() {
        val files = listOf(
            file(1, "lama.jpg"),
            file(2, "baru.png"),
            file(3, "catatan.txt"),
        )

        val targets = tidyTargets(files, pending = setOf(2L, 9L), tidyEverything = false)

        assertEquals(listOf(2L), targets.map { it.messageId })
    }

    @Test
    fun captionedMessagesAreNeverMovedAgain() {
        val files = listOf(
            file(1, "sudah.jpg", folder = FileCategories.PHOTOS),
            file(2, "manual.pdf", folder = "Kerja"),
        )

        assertTrue(tidyTargets(files, pending = setOf(1L, 2L), tidyEverything = true).isEmpty())
        assertTrue(tidyTargets(files, pending = setOf(1L, 2L), tidyEverything = false).isEmpty())
    }

    @Test
    fun anEmptyPendingQueueMeansNothingToRetry() {
        val files = listOf(file(1, "lama.jpg"))

        assertTrue(tidyTargets(files, pending = emptySet(), tidyEverything = false).isEmpty())
    }
}
