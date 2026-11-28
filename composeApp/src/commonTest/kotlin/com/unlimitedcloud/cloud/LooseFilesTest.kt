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

        val targets = tidyTargets(
            files,
            pending = emptySet(),
            tidyEverything = true,
            autoTopics = true,
        )

        assertEquals(listOf(1L, 2L), targets.map { it.file.messageId })
    }

    @Test
    fun laterPassesOnlyRetryRememberedIds() {
        val files = listOf(
            file(1, "lama.jpg"),
            file(2, "baru.png"),
            file(3, "catatan.txt"),
        )

        val targets = tidyTargets(
            files,
            pending = setOf(2L, 9L),
            tidyEverything = false,
            autoTopics = true,
        )

        assertEquals(listOf(2L), targets.map { it.file.messageId })
    }

    @Test
    fun captionedMessagesAreNeverMovedAgain() {
        val files = listOf(
            file(1, "sudah.jpg", folder = FileCategories.PHOTOS),
            file(2, "manual.pdf", folder = "Kerja"),
        )

        assertTrue(
            tidyTargets(files, setOf(1L, 2L), tidyEverything = true, autoTopics = true).isEmpty(),
        )
        assertTrue(
            tidyTargets(files, setOf(1L, 2L), tidyEverything = false, autoTopics = true).isEmpty(),
        )
    }

    @Test
    fun anEmptyPendingQueueMeansNothingToRetry() {
        val files = listOf(file(1, "lama.jpg"))

        assertTrue(
            tidyTargets(files, emptySet(), tidyEverything = false, autoTopics = true).isEmpty(),
        )
    }

    @Test
    fun galleryUploadsAreRefiledIntoTheirTypeTopic() {
        val files = listOf(
            file(1, "liburan.mp4", folder = FileCategories.GALLERY),
            file(2, "pantai.jpg", folder = "${FileCategories.GALLERY}/Liburan/Pantai"),
        )

        val targets = tidyTargets(files, emptySet(), tidyEverything = true, autoTopics = true)

        assertEquals(
            listOf(FileCategories.VIDEOS, "${FileCategories.PHOTOS}/Liburan/Pantai"),
            targets.map { it.folder },
        )
    }

    @Test
    fun filesUnderTheWrongTypeTopicFollowTheirExtension() {
        val files = listOf(
            file(1, "klip.mp4", folder = FileCategories.PHOTOS),
            file(2, "foto.png", folder = "${FileCategories.VIDEOS}/Liburan"),
            file(3, "dokumen.pdf", folder = FileCategories.GALLERY),
        )

        val targets = tidyTargets(files, emptySet(), tidyEverything = true, autoTopics = true)

        assertEquals(
            listOf(FileCategories.VIDEOS, "${FileCategories.PHOTOS}/Liburan", FileCategories.DOCUMENTS),
            targets.map { it.folder },
        )
    }

    @Test
    fun foldersTheUserNamedAreLeftAlone() {
        val files = listOf(
            file(1, "klip.mp4", folder = "Kerja"),
            file(2, "foto.png", folder = "Proyek/2026"),
        )

        assertTrue(tidyTargets(files, emptySet(), tidyEverything = true, autoTopics = true).isEmpty())
    }

    @Test
    fun oneTidyRunKeepsGoingUntilEverySliceIsDone() {
        val targets = (1L..100L).map { id ->
            TidyMove(file(id, "lama.jpg", "Kerja"), FileCategories.PHOTOS)
        }

        val batches = tidyBatches(targets, perRun = 40, maxRuns = 10)

        assertEquals(listOf(40, 40, 20), batches.map { it.size })
        assertEquals(100, batches.flatten().map { it.file.messageId }.distinct().size)
    }

    @Test
    fun aTidyRunStopsAtItsSliceBudget() {
        val targets = (1L..1_000L).map { id ->
            TidyMove(file(id, "lama.jpg", "Kerja"), FileCategories.PHOTOS)
        }

        val batches = tidyBatches(targets, perRun = 40, maxRuns = 2)

        assertEquals(listOf(40, 40), batches.map { it.size })
        assertTrue(tidyBatches(emptyList(), perRun = 40, maxRuns = 10).isEmpty())
        assertTrue(tidyBatches(targets, perRun = 0, maxRuns = 10).isEmpty())
    }

    @Test
    fun galleryUploadsStayWhenAutomaticTopicsAreOff() {
        val files = listOf(file(1, "liburan.mp4", folder = FileCategories.GALLERY))

        assertTrue(tidyTargets(files, emptySet(), tidyEverything = true, autoTopics = false).isEmpty())
    }
}
