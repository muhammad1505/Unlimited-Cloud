package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.log.platformTimestamp
import com.unlimitedcloud.platform.GalleryBackgroundSync
import com.unlimitedcloud.platform.GalleryBatch
import com.unlimitedcloud.platform.GalleryFile
import com.unlimitedcloud.platform.GalleryFolder
import com.unlimitedcloud.platform.GallerySyncSource
import com.unlimitedcloud.platform.LocalFolderSelection
import com.unlimitedcloud.platform.MediaSaver
import com.unlimitedcloud.platform.TransferNotifier
import com.unlimitedcloud.platform.synchronizedBlock
import com.unlimitedcloud.platform.clearUploadCache
import com.unlimitedcloud.platform.createTransferNotifier
import com.unlimitedcloud.platform.createUploadThumbnail
import com.unlimitedcloud.platform.deleteSourceFile
import com.unlimitedcloud.platform.pickedFilesAreAppCopies
import com.unlimitedcloud.platform.StorageUsage
import com.unlimitedcloud.platform.TransferSession
import com.unlimitedcloud.platform.sourceFileDeletionSupported
import com.unlimitedcloud.platform.storageUsage
import com.unlimitedcloud.platform.uploadCacheSupported
import com.unlimitedcloud.settings.AppPreferences
import com.unlimitedcloud.settings.MAX_TRANSFER_PARALLELISM
import com.unlimitedcloud.settings.MIN_TRANSFER_PARALLELISM
import com.unlimitedcloud.ui.util.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.TimeSource

enum class FileSort(val label: String) {
    Newest("Terbaru"),
    Name("Nama"),
    Size("Ukuran"),
}

enum class TransferDirection {
    Upload,
    Download,
}

enum class TransferStatus {
    Running,
    Completed,
    Failed,
}

/** One upload/download shown in the Transfer tab and inline on the file card. */
data class TransferItem(
    val id: String,
    val fileName: String,
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.Running,
    val progress: Float? = null,
    val fileId: Int? = null,
    val detail: String? = null,
    /** Kept for downloads so a failed transfer can be retried from the list. */
    val file: CloudFile? = null,
    /** Kept for uploads so a failed transfer can be retried from the list. */
    val localPath: String? = null,
    /** Target folder of an upload, replayed by a retry. */
    val folder: String? = null,
    /** Whether the uploaded copy may be removed again after a successful retry. */
    val canDeleteSource: Boolean = false,
    /** SAF document of the original, replayed by a retry when it is known. */
    val documentUri: String? = null,
)

/** Everything the explorer renders, including the selected folder and search. */
data class ExplorerUiState(
    val files: List<CloudFile> = emptyList(),
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val query: String = "",
    val sort: FileSort = FileSort.Newest,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val nextFromMessageId: Long? = null,
    val error: String? = null,
    val status: String? = null,
    val transfers: List<TransferItem> = emptyList(),
    val gallerySupported: Boolean = false,
    val galleryAutoSync: Boolean = false,
    val galleryBackgroundSupported: Boolean = false,
    val galleryBackgroundSync: Boolean = false,
    val galleryFolders: List<GalleryFolder> = emptyList(),
    val gallerySyncing: Boolean = false,
    val galleryLastSync: String? = null,
    val gallerySyncedCount: Int = 0,
    /** Files this sync run found waiting, so progress has a known total. */
    val galleryPlanned: Int = 0,
    /** Files already handled by the running sync, success or failure. */
    val galleryDone: Int = 0,
    /** Rough remaining time of the running sync; null while it is unknown. */
    val galleryEtaSeconds: Long? = null,
    val autoSaveSupported: Boolean = false,
    val autoSavePhotos: Boolean = false,
    /** How many files a batch uploads or downloads at once (Settings). */
    val parallelTransfers: Int = 3,
    /** Whether uploads without a folder are filed into a topic by file type. */
    val autoTopics: Boolean = true,
    /**
     * Whether the copies the app made for an upload are deleted once TDLib has
     * the file. The original file is never the one removed.
     */
    val deleteUploadedCopies: Boolean = true,
    /** Whether the user's own file is moved to trash after a confirmed upload. */
    val deleteOriginals: Boolean = false,
    /** True when this platform can remove an uploaded original recoverably. */
    val originalsDeletionSupported: Boolean = false,
    /** Whether this platform keeps upload copies that can be measured and cleared. */
    val cacheSupported: Boolean = false,
    /** Size of the upload cache in bytes; null until measured. */
    val cacheBytes: Long? = null,
    /** Disk usage of the app's own directories; null until measured. */
    val storageUsage: StorageUsage? = null,
    /** True while the cache is being emptied, so the button cannot be tapped twice. */
    val cacheClearing: Boolean = false,
    /** True while an old-file tidy pass runs; Settings disables its button. */
    val tidying: Boolean = false,
    /** Custom name of the storage group; null while it keeps the default one. */
    val storageTitle: String? = null,
    /** Whether the storage group is muted in Telegram. */
    val storageMuted: Boolean = true,
    /** Whether the storage group is kept in the Telegram archive. */
    val storageArchived: Boolean = false,
    /** True while a rename or icon upload runs, so Settings disables both. */
    val storageCustomizing: Boolean = false,
    /** True when the platform has a notification shade to post into. */
    val notificationsSupported: Boolean = false,
    /** False when notifications are blocked; Settings offers a shortcut to fix it. */
    val notificationsEnabled: Boolean = true,
    /** Bumped when a cover becomes available so cards retry their thumbnail. */
    val coverRevision: Int = 0,
) {
    /**
     * Kecachean per instance: setiap perubahan membuat instance baru lewat
     * `copy`, jadi nilai cache tidak mungkin basi, sedangkan filter + sort tidak
     * lagi dijalankan sekali per recomposition (dan sekali per cover baru).
     */
    private var visibleFilesCache: List<CloudFile>? = null

    val visibleFiles: List<CloudFile>
        get() {
            visibleFilesCache?.let { return it }
            // Subfolders are stored as paths ("Liburan/Pantai"), so opening a
            // folder also shows the files of everything beneath it. Without this
            // a parent that only holds subfolders would open empty.
            val filtered = files.filter { file ->
                (selectedFolder == null || file.inFolder(selectedFolder)) &&
                    (query.isBlank() || file.name.contains(query.trim(), ignoreCase = true))
            }
            val sorted = when (sort) {
                FileSort.Newest -> filtered.sortedByDescending { it.messageId }
                FileSort.Name -> filtered.sortedBy { it.name.lowercase() }
                FileSort.Size -> filtered.sortedByDescending { it.size }
            }
            visibleFilesCache = sorted
            return sorted
        }

    private var totalBytesCache: Long? = null

    val totalBytes: Long
        get() = totalBytesCache ?: files.sumOf { it.size }.also { totalBytesCache = it }

    val activeTransfers: List<TransferItem>
        get() = transfers.filter { it.status == TransferStatus.Running }

    val finishedTransfers: List<TransferItem>
        get() = transfers.filter { it.status != TransferStatus.Running }

    fun folderCount(folder: String): Int = files.count { it.inFolder(folder) }

    /** Old messages still waiting for their type topic, i.e. the tidy button's count. */
    val looseFileCount: Int
        get() = files.count { tidyTarget(it, autoTopics) != null }
}

/** True for a file stored directly in [folder] or in one of its subfolders. */
private fun CloudFile.inFolder(folder: String): Boolean {
    val path = this.folder ?: return false
    return path == folder || path.startsWith("$folder/")
}

/**
 * Drives the file list, history pagination, search, the parallel transfer queue
 * and the incremental folder sync.
 *
 * Kept as a plain class (not a platform ViewModel) so Android and Desktop share
 * the exact same behaviour; Koin keeps a single instance for the signed-in shell.
 */
class CloudFilesViewModel(
    private val repository: CloudFileRepository,
    private val channelManager: CloudChannelManager,
    private val preferences: AppPreferences,
    private val gallerySource: GallerySyncSource,
    private val backgroundSync: GalleryBackgroundSync,
    private val mediaSaver: MediaSaver,
    private val transferSession: TransferSession,
    private val scope: CoroutineScope,
) {
    /**
     * Running and finished transfers go to the system notification shade where
     * the platform has one; the in-app banner stays the foreground feedback.
     * Declared before the state because the first snapshot asks whether posting
     * is currently allowed.
     */
    private val notifier: TransferNotifier = createTransferNotifier()

    private val state = MutableStateFlow(
        ExplorerUiState(
            gallerySupported = gallerySource.supported,
            galleryAutoSync = preferences.galleryAutoSync(),
            galleryBackgroundSupported = backgroundSync.supported,
            galleryBackgroundSync = preferences.galleryBackgroundSync(),
            galleryFolders = preferences.galleryFolders(),
            galleryLastSync = preferences.galleryLastSync(),
            gallerySyncedCount = preferences.gallerySyncedKeys().size,
            autoSaveSupported = mediaSaver.supported,
            autoSavePhotos = preferences.autoSavePhotos(),
            parallelTransfers = preferences.transferParallelism(),
            autoTopics = preferences.autoTopics(),
            deleteUploadedCopies = preferences.deleteUploadedCopies(),
            deleteOriginals = preferences.deleteUploadedOriginals(),
            originalsDeletionSupported = sourceFileDeletionSupported,
            cacheSupported = uploadCacheSupported,
            storageTitle = preferences.cloudRootTitle(),
            storageMuted = preferences.cloudRootMuted(),
            storageArchived = preferences.cloudRootArchived(),
            notificationsSupported = notifier.supported,
            notificationsEnabled = !notifier.supported || notifier.enabled,
        ),
    )
    val uiState: StateFlow<ExplorerUiState> = state.asStateFlow()

    init {
        // A process killed mid-transfer leaves its ongoing notification behind;
        // nothing can be running yet in this one, so drop it.
        notifier.clearProgress()
        refreshBackgroundSchedule()
        refreshCacheUsage()
    }

    private var transferCounter = 0L
    private val transferIdLock = Any()
    private var refreshJob: Job? = null
    private var tidyJob: Job? = null

    /**
     * Transfer ids are handed out from parallel workers, so the counter is
     * locked: two uploads starting at the same moment used to share an id and
     * one of the rows silently updated the other.
     */
    private fun nextTransferId(): Long = synchronizedBlock(transferIdLock) { ++transferCounter }

    fun refresh() {
        // Two refreshes paging at once would append duplicate pages, so the new
        // one replaces the one still running.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            state.update { it.copy(loading = true, loadingMore = false, error = null) }

            val chatId = ensureChannelWithRetry()
            if (chatId == null) {
                state.update { it.copy(loading = false, error = "Penyimpanan belum siap") }
                return@launch
            }

            // The history of a chat TDLib has not opened yet only comes from the
            // local cache, so the first refresh could show a single file until
            // the list was reloaded by hand.
            runCatching { repository.prepareHistory(chatId) }

            // The first page paints the list right away; the remaining pages are
            // appended while the user is already scrolling, instead of blocking
            // on a long chain of requests before showing anything.
            val firstPage = runCatching { loadPage(chatId, 0L) }.getOrElse { failure ->
                state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = failure.message ?: "Gagal memuat daftar berkas",
                    )
                }
                return@launch
            }

            var cursor = firstPage.nextFromMessageId
            applyPage(firstPage, replace = true, moreToLoad = cursor != null)

            var pages = 1
            while (cursor != null && pages < PAGES_PER_REFRESH) {
                val page = runCatching { loadPage(chatId, cursor) }.getOrElse { break }
                pages++
                cursor = page.nextFromMessageId
                applyPage(page, replace = false, moreToLoad = cursor != null)
            }

            state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    canLoadMore = cursor != null,
                    nextFromMessageId = cursor,
                )
            }

            // Reaching the end of the history is the only moment the whole list
            // is known, so messages without a folder are tidied here.
            if (cursor == null) maybeTidyLooseFiles(chatId)
        }
    }

    /** Merges one history page into the list and keeps folders in sync. */
    private fun applyPage(page: CloudPage, replace: Boolean, moreToLoad: Boolean) {
        state.update { previous ->
            val merged = if (replace) {
                page.files
            } else {
                val known = previous.files.mapTo(mutableSetOf()) { it.messageId }
                previous.files + page.files.filterNot { known.contains(it.messageId) }
            }
            val folders = merged.mapNotNull { it.folder }.distinct().sorted()
            previous.copy(
                files = merged,
                folders = folders,
                selectedFolder = previous.selectedFolder?.takeIf { it in folders },
                loading = false,
                loadingMore = moreToLoad,
                // The refresh keeps paging on its own; the scroll trigger only
                // takes over once it has finished.
                canLoadMore = false,
                nextFromMessageId = page.nextFromMessageId,
                error = null,
            )
        }
    }

    fun loadMore() {
        val current = state.value
        val cursor = current.nextFromMessageId ?: return
        if (current.loadingMore || current.loading) return

        scope.launch {
            state.update { it.copy(loadingMore = true) }

            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(loadingMore = false, error = "Penyimpanan belum siap") }
                return@launch
            }

            runCatching { loadMorePages(chatId, cursor) }
                .onSuccess { page ->
                    state.update { previous ->
                        val known = previous.files.mapTo(mutableSetOf()) { it.messageId }
                        val fresh = page.files.filterNot { known.contains(it.messageId) }
                        val merged = previous.files + fresh
                        previous.copy(
                            files = merged,
                            folders = merged.mapNotNull { it.folder }.distinct().sorted(),
                            loadingMore = false,
                            canLoadMore = page.nextFromMessageId != null,
                            nextFromMessageId = page.nextFromMessageId,
                            error = null,
                        )
                    }
                    if (page.nextFromMessageId == null) maybeTidyLooseFiles(chatId)
                }
                .onFailure { failure ->
                    state.update {
                        it.copy(loadingMore = false, error = failure.message ?: "Gagal memuat lebih banyak")
                    }
                }
        }
    }

    fun selectFolder(folder: String?) {
        state.update { it.copy(selectedFolder = folder) }
    }

    fun setQuery(query: String) {
        state.update { it.copy(query = query) }
    }

    fun setSort(sort: FileSort) {
        state.update { it.copy(sort = sort) }
    }

    /**
     * Turns automatic filing on or off. The list is re-read so caption-less
     * messages are regrouped right away instead of after the next launch.
     */
    fun setAutoTopics(enabled: Boolean) {
        preferences.setAutoTopics(enabled)
        state.update { it.copy(autoTopics = enabled) }
        refresh()
    }

    /**
     * Turns the post-upload cache cleanup on or off. Only copies the app made
     * are ever removed; the file the user picked stays where it was.
     */
    fun setDeleteUploadedCopies(enabled: Boolean) {
        preferences.setDeleteUploadedCopies(enabled)
        state.update { it.copy(deleteUploadedCopies = enabled) }
    }

    /**
     * Turns removal of the original on or off. Only files the app can address
     * afterwards are affected, and Settings asks for confirmation first.
     */
    fun setDeleteUploadedOriginals(enabled: Boolean) {
        preferences.setDeleteUploadedOriginals(enabled)
        state.update { it.copy(deleteOriginals = enabled) }
    }

    /**
     * Measures the app's directories so Settings can show where the space went
     * and how much the upload cache holds.
     */
    fun refreshCacheUsage() {
        scope.launch {
            val usage = withContext(Dispatchers.IO) {
                runCatching { storageUsage() }.getOrNull()
            } ?: return@launch
            state.update {
                it.copy(storageUsage = usage, cacheBytes = usage.uploadCacheBytes)
            }
        }
    }

    /**
     * Empties the temporary upload copies. Refused while a transfer runs: the
     * copies in the cache are exactly what the running upload reads from.
     */
    fun clearUploadCacheNow() {
        if (!state.value.cacheSupported || state.value.cacheClearing) return
        if (state.value.activeTransfers.isNotEmpty() || state.value.gallerySyncing) {
            reportError("Tunggu transfer selesai sebelum membersihkan cache")
            return
        }
        scope.launch {
            state.update { it.copy(cacheClearing = true) }
            val freed = withContext(Dispatchers.IO) {
                runCatching { clearUploadCache() }.getOrDefault(0L)
            }
            val usage = withContext(Dispatchers.IO) {
                runCatching { storageUsage() }.getOrNull()
            }
            logDiagnostic(LOG_TAG, "cache unggahan dibersihkan: ${formatBytes(freed)}")
            state.update {
                it.copy(
                    cacheClearing = false,
                    cacheBytes = usage?.uploadCacheBytes ?: 0L,
                    storageUsage = usage ?: it.storageUsage,
                    status = if (freed > 0L) {
                        "${formatBytes(freed)} cache unggahan dibersihkan"
                    } else {
                        "Cache unggahan sudah kosong"
                    },
                    error = null,
                )
            }
        }
    }

    /**
     * Moves messages left over from versions that did not file by type into
     * their type topic. The first pass scans the whole history; later launches
     * only retry the ids that failed before, so a file TDLib refuses is not copied
     * again (and again) on every start.
     */
    private fun maybeTidyLooseFiles(chatId: Long) {
        if (!state.value.autoTopics) return
        if (tidyJob?.isActive == true) return
        val firstPass = !preferences.looseFilesTidied()
        // One pass re-files the uploads the folder sync left in "Galeri" before
        // files were filed by type.
        val retypePass = !preferences.galleryFilesRetyped()
        if (!firstPass && !retypePass && preferences.tidyPending().isEmpty()) return
        // Safe to mark before the move: whatever does not make it is kept in
        // cloud.tidyPending and retried by a later pass.
        preferences.setLooseFilesTidied(true)
        if (retypePass) preferences.setGalleryFilesRetyped(true)
        launchTidy(chatId, tidyEverything = firstPass || retypePass, manual = false)
    }

    /**
     * The "Rapikan berkas lama" button. It runs even with automatic filing
     * switched off, because moving old files by hand is exactly what the switch
     * cannot do for the user.
     */
    fun tidyNow() {
        if (tidyJob?.isActive == true) return
        tidyJob = scope.launch {
            val chatId = ensureChannelWithRetry()
            if (chatId == null) {
                reportError("Penyimpanan belum siap")
                return@launch
            }
            // Every page has to be in memory, otherwise files on pages that were
            // never scrolled to would be left behind and the count would be wrong.
            var historyComplete = true
            if (!isHistoryComplete(state.value)) {
                state.update {
                    it.copy(tidying = true, status = "Memuat seluruh daftar berkas…", error = null)
                }
                val loaded = runCatching { completeHistory(chatId) }
                val failure = loaded.exceptionOrNull()
                if (failure != null) {
                    state.update {
                        it.copy(
                            tidying = false,
                            status = null,
                            error = failure.message ?: "Gagal memuat daftar berkas",
                        )
                    }
                    return@launch
                }
                historyComplete = loaded.getOrDefault(false)
            }
            runTidy(
                chatId = chatId,
                tidyEverything = true,
                manual = true,
                historyComplete = historyComplete,
            )
        }
    }

    /**
     * Loads the remaining history pages so a manual tidy sees every file.
     *
     * @return true when the end of the history was reached. A very large group
     * stops at [MAX_COMPLETE_PAGES], and the cursor is kept, so pressing the
     * button again continues from there instead of loading the same pages again.
     */
    private suspend fun completeHistory(chatId: Long): Boolean {
        // The button can be pressed before the explorer ever loaded a page.
        if (state.value.files.isEmpty() && state.value.nextFromMessageId == null) {
            val first = loadPage(chatId)
            applyPage(first, replace = true, moreToLoad = first.nextFromMessageId != null)
        }
        var pages = 0
        var stalled = false
        var cursor = state.value.nextFromMessageId
        while (cursor != null && pages < MAX_COMPLETE_PAGES) {
            val page = loadPage(chatId, cursor)
            pages++
            applyPage(page, replace = false, moreToLoad = page.nextFromMessageId != null)
            val next = page.nextFromMessageId
            // A cursor that does not advance would page the same slice forever,
            // and the history is then not known to be complete either.
            if (next == cursor) {
                stalled = true
                cursor = null
            } else {
                cursor = next
            }
        }
        if (cursor != null || stalled) {
            logDiagnostic(
                LOG_TAG,
                "riwayat belum habis setelah $pages halaman; sisanya menyusul",
            )
        }
        return cursor == null && !stalled
    }

    private fun launchTidy(chatId: Long, tidyEverything: Boolean, manual: Boolean) {
        if (tidyJob?.isActive == true) return
        tidyJob = scope.launch { runTidy(chatId, tidyEverything, manual) }
    }

    /**
     * Forwards every target into its type topic and deletes the old message only
     * after the copy exists. Ids that fail are stored before the pass returns, so
     * the next launch (or the button) picks them up again.
     */
    private suspend fun runTidy(
        chatId: Long,
        tidyEverything: Boolean,
        manual: Boolean,
        historyComplete: Boolean = true,
    ) {
        val pending = preferences.tidyPending()
        val targets = tidyTargets(
            files = state.value.files,
            pending = pending,
            tidyEverything = tidyEverything,
            autoTopics = state.value.autoTopics,
        )
        if (targets.isEmpty()) {
            // Remembered ids that vanished or already carry a caption need no retry.
            if (pending.isNotEmpty()) preferences.setTidyPending(emptySet())
            state.update {
                it.copy(
                    tidying = false,
                    status = when {
                        // Saying "nothing to tidy" while pages are still unread
                        // would claim a clean history the app has not seen.
                        manual && !historyComplete ->
                            "Sebagian riwayat belum termuat; tekan Rapikan lagi untuk melanjutkan"
                        manual -> "Tidak ada berkas lama yang perlu dirapikan"
                        else -> it.status
                    },
                )
            }
            return
        }

        // The whole batch is remembered first: a launch killed in the middle still
        // retries the rest instead of leaving those files untracked.
        val remaining = targets.mapTo(mutableSetOf<Long>()) { it.file.messageId }
        preferences.setTidyPending(remaining)
        // A forward + delete per file is slow and Telegram throttles bursts, so
        // the run moves a slice at a time and pauses between slices. The slices
        // happen in one run now: the rest of a 100 file history used to wait for
        // the next launch or another press of the button.
        val batches = tidyBatches(targets, MAX_TIDY_PER_PASS, MAX_TIDY_RUNS)
        logDiagnostic(
            LOG_TAG,
            "rapikan: ${targets.size} berkas dalam ${batches.size} putaran",
        )
        state.update {
            it.copy(tidying = true, status = "Merapikan ${targets.size} berkas lama…", error = null)
        }

        var moved = 0
        val failed = mutableSetOf<Long>()
        for ((index, batch) in batches.withIndex()) {
            if (index > 0) {
                delay(TIDY_PAUSE_MILLIS)
                state.update {
                    it.copy(status = "Merapikan berkas lama… $moved/${targets.size}")
                }
            }
            var movedInBatch = 0
            batch.forEach { move ->
                val ok = runCatching {
                    repository.moveToTypeTopic(chatId, move.file.messageId, move.folder)
                }.getOrDefault(false)
                if (ok) {
                    moved++
                    movedInBatch++
                    remaining -= move.file.messageId
                } else {
                    failed += move.file.messageId
                }
            }
            // A slice where nothing at all moved means the account refuses the
            // rest too (flood wait, missing rights); stop instead of hammering.
            preferences.setTidyPending(remaining)
            if (movedInBatch == 0) break
        }
        logDiagnostic(LOG_TAG, "rapikan selesai: $moved dipindah, ${failed.size} gagal")
        state.update {
            it.copy(
                tidying = false,
                status = when {
                    moved > 0 && remaining.isNotEmpty() ->
                        "$moved berkas dipindah ke topik jenisnya, ${remaining.size} menyusul"
                    moved > 0 -> "$moved berkas lama dipindah ke topik jenisnya"
                    manual -> "Tidak ada berkas lama yang bisa dirapikan"
                    else -> null
                },
                error = if (failed.isNotEmpty()) {
                    "${failed.size} berkas gagal dirapikan, dicoba lagi nanti"
                } else {
                    null
                },
            )
        }
        // The ids changed (forward + delete), so the list is read again.
        if (moved > 0) refresh()
    }

    /** Saves the parallel transfer count; running batches keep their worker count. */
    fun setTransferParallelism(count: Int) {
        val clamped = count.coerceIn(MIN_TRANSFER_PARALLELISM, MAX_TRANSFER_PARALLELISM)
        preferences.setTransferParallelism(clamped)
        state.update { it.copy(parallelTransfers = clamped) }
    }

    fun dismissStatus() {
        state.update { it.copy(status = null) }
    }

    fun clearError() {
        state.update { it.copy(error = null) }
    }

    /** Shows a one-shot confirmation, e.g. after the share sheet was opened. */
    fun reportStatus(message: String) {
        state.update { it.copy(status = message, error = null) }
    }

    fun reportError(message: String) {
        state.update { it.copy(error = message, status = null) }
    }

    /**
     * Renames the Telegram group that stores the files. Only the label changes:
     * every folder stays a topic, so no file moves and nothing is uploaded again.
     */
    fun renameStorageGroup(title: String) {
        if (state.value.storageCustomizing) return
        scope.launch {
            state.update { it.copy(storageCustomizing = true) }
            val result = channelManager.renameRoot(title)
            state.update { current ->
                if (result.isSuccess) {
                    current.copy(
                        storageCustomizing = false,
                        storageTitle = preferences.cloudRootTitle(),
                        status = "Nama grup penyimpanan diperbarui",
                        error = null,
                    )
                } else {
                    current.copy(
                        storageCustomizing = false,
                        status = null,
                        error = result.exceptionOrNull()?.message ?: "Nama grup gagal diganti",
                    )
                }
            }
        }
    }

    /** Puts the friendly default name back on the storage group. */
    fun resetStorageName() {
        if (state.value.storageCustomizing) return
        scope.launch {
            state.update { it.copy(storageCustomizing = true) }
            val result = channelManager.resetRootTitle()
            state.update { current ->
                if (result.isSuccess) {
                    current.copy(
                        storageCustomizing = false,
                        storageTitle = preferences.cloudRootTitle(),
                        status = "Nama grup penyimpanan dikembalikan ke bawaan",
                        error = null,
                    )
                } else {
                    current.copy(
                        storageCustomizing = false,
                        status = null,
                        error = result.exceptionOrNull()?.message ?: "Nama grup gagal dikembalikan",
                    )
                }
            }
        }
    }

    /** Uploads a photo the user picked as the storage group photo. */
    fun applyStoragePhoto(path: String) {
        if (state.value.storageCustomizing) return
        scope.launch {
            state.update { it.copy(storageCustomizing = true) }
            val result = channelManager.applyRootPhoto(path)
            state.update { current ->
                if (result.isSuccess) {
                    current.copy(
                        storageCustomizing = false,
                        status = "Foto grup penyimpanan diperbarui",
                        error = null,
                    )
                } else {
                    current.copy(
                        storageCustomizing = false,
                        status = null,
                        error = result.exceptionOrNull()?.message ?: "Foto grup gagal dipasang",
                    )
                }
            }
        }
    }

    /** Mutes the storage group so its upload messages stay silent. */
    fun setStorageMuted(muted: Boolean) {
        if (state.value.storageCustomizing) return
        scope.launch {
            state.update { it.copy(storageCustomizing = true) }
            val result = channelManager.setMuted(muted)
            state.update { current ->
                current.copy(
                    storageCustomizing = false,
                    storageMuted = if (result.isSuccess) muted else current.storageMuted,
                    status = if (result.isSuccess) {
                        if (muted) "Grup penyimpanan dibisukan" else "Grup penyimpanan kembali berbunyi"
                    } else {
                        null
                    },
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    /** Keeps the storage group in the Telegram archive, or back in the chat list. */
    fun setStorageArchived(archived: Boolean) {
        if (state.value.storageCustomizing) return
        scope.launch {
            state.update { it.copy(storageCustomizing = true) }
            val result = channelManager.setArchived(archived)
            state.update { current ->
                current.copy(
                    storageCustomizing = false,
                    storageArchived = if (result.isSuccess) archived else current.storageArchived,
                    status = if (result.isSuccess) {
                        if (archived) "Grup penyimpanan diarsipkan" else "Grup penyimpanan keluar dari arsip"
                    } else {
                        null
                    },
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    /** Puts the app's own launcher icon on the storage group. */
    fun applyStorageIcon() {
        if (state.value.storageCustomizing) return
        scope.launch {
            state.update { it.copy(storageCustomizing = true) }
            val result = channelManager.applyRootIcon()
            state.update { current ->
                if (result.isSuccess) {
                    current.copy(
                        storageCustomizing = false,
                        status = "Ikon aplikasi dipasang di grup penyimpanan",
                        error = null,
                    )
                } else {
                    current.copy(
                        storageCustomizing = false,
                        status = null,
                        error = result.exceptionOrNull()?.message ?: "Ikon grup gagal dipasang",
                    )
                }
            }
        }
    }

    /**
     * One file waiting its turn, with the folder it will land in and, when the
     * file came from a folder whose grant outlives the picker, the document the
     * original can be removed through afterwards.
     */
    private data class UploadRequest(
        val localPath: String,
        val folder: String?,
        val documentUri: String? = null,
    ) {
        /**
         * Whether the original can still be addressed after the picker closed:
         * Android needs the document uri of a granted folder, desktop always has
         * the file's own path.
         */
        val canDeleteSource: Boolean
            get() = if (pickedFilesAreAppCopies) documentUri != null else true
    }

    fun upload(localPath: String, folder: String?) {
        startUploads(
            // A file chosen one by one only has a one-shot read grant, so its
            // original cannot be addressed later; nothing to pass here.
            requests = listOf(UploadRequest(localPath, folderFor(folder, localPath))),
            summary = null,
        )
    }

    /**
     * Uploads every picked file through the shared worker pool. How many run at
     * once is the user's setting, so a slow mobile connection can be told to
     * move one file at a time.
     */
    fun uploadMany(paths: List<String>, folder: String?) {
        val unique = paths.distinct()
        if (unique.isEmpty()) return
        val target = normalizeFolder(folder)
        startUploads(
            requests = unique.map { path -> UploadRequest(path, folderFor(target, path)) },
            summary = "${unique.size} berkas",
        )
    }

    /**
     * Uploads a picked folder and recreates its subfolders as folders of the
     * same name ("Liburan/Pantai"): every file keeps the path it had on disk,
     * relative to the picked root.
     */
    fun uploadFolder(selection: LocalFolderSelection, folder: String?) {
        if (selection.files.isEmpty()) {
            reportError(
                if (selection.incomplete) {
                    "Tidak ada berkas yang bisa dibaca dari ${selection.name}"
                } else {
                    "Folder ${selection.name} tidak berisi berkas"
                },
            )
            return
        }
        val base = normalizeFolder(folder) ?: selection.name.ifBlank { "folder" }
        logDiagnostic(
            LOG_TAG,
            "unggah folder: ${selection.name} (${selection.files.size} berkas, " +
                "dilewati ${selection.skipped}, terpotong ${selection.truncated}) → $base",
        )
        startUploads(
            requests = selection.files.map { file ->
                val relativeDirectory = file.relativePath.substringBeforeLast('/', "")
                UploadRequest(
                    localPath = file.localPath,
                    folder = if (relativeDirectory.isEmpty()) base else "$base/$relativeDirectory",
                    documentUri = file.documentUri,
                )
            },
            summary = "${selection.files.size} berkas dari ${selection.name}",
            cacheRoot = selection.cacheRoot,
            note = when {
                selection.truncated -> "batas pemindaian tercapai, sebagian berkas belum ikut"
                selection.skipped > 0 -> "${selection.skipped} berkas tidak bisa dibaca"
                else -> null
            },
        )
    }

    /**
     * Runs a batch in the app scope with the platform keeping the process alive.
     * Removing the app from recents used to cancel the upload halfway through,
     * because nothing held the process while the batch ran.
     */
    private fun launchBatch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch {
            transferSession.begin()
            try {
                block()
            } finally {
                transferSession.end()
            }
        }
    }

    private fun startUploads(
        requests: List<UploadRequest>,
        summary: String?,
        cacheRoot: String? = null,
        note: String? = null,
    ) {
        if (requests.isEmpty()) return
        val single = requests.size == 1
        val first = requests.first().localPath.fileName()
        val label = if (single) first else "${requests.size} berkas"
        logDiagnostic(
            LOG_TAG,
            "unggah mulai: $label" + if (single) " → ${requests.first().folder ?: "(root)"}" else "",
        )

        state.update {
            it.copy(
                status = if (single) "Mengunggah $first…" else "Mengunggah ${requests.size} berkas…",
                error = null,
            )
        }

        launchBatch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(status = null, error = "Penyimpanan belum siap") }
                notifyTransfer("Unggahan gagal", "Penyimpanan belum siap")
                return@launchBatch
            }

            var uploaded = 0
            var failed = 0
            var finished = 0
            var freedBytes = 0L
            var originalsDeleted = 0
            var originalsFailed = 0
            val deleteOriginals = state.value.deleteOriginals
            // Read once per batch: switching the setting off while an upload
            // runs must not start deleting copies the user wants to keep.
            val deleteCopies = state.value.deleteUploadedCopies
            // Workers run on the shared multi-threaded dispatcher, so the
            // counters and the one ongoing notification are guarded.
            val stats = Mutex()

            // A batch no longer crawls one file at a time: the configured number
            // of workers keeps TDLib busy without flooding a mobile connection.
            coroutineScope {
                val queue = Channel<UploadRequest>(capacity = requests.size)
                requests.forEach { queue.send(it) }
                queue.close()

                repeat(minOf(state.value.parallelTransfers, requests.size)) {
                    launch {
                        for (request in queue) {
                            val transferId = "transfer-${nextTransferId()}"
                            val name = request.localPath.fileName()
                            addTransfer(
                                TransferItem(
                                    id = transferId,
                                    fileName = name,
                                    direction = TransferDirection.Upload,
                                    progress = 0f,
                                    localPath = request.localPath,
                                    folder = request.folder,
                                    canDeleteSource = request.canDeleteSource,
                                    documentUri = request.documentUri,
                                ),
                            )

                            logDiagnostic(
                                LOG_TAG,
                                "berkas mulai: $name (paralel ${state.value.parallelTransfers})",
                            )
                            val result = uploadFile(
                                transferId = transferId,
                                chatId = chatId,
                                localPath = request.localPath,
                                folder = request.folder,
                                onProgress = { fraction ->
                                    // The notification follows the whole batch, not
                                    // one file: the bar would jump back otherwise.
                                    // Read without the lock on purpose: this only
                                    // labels the notification, and a stale count by
                                    // one file is harmless.
                                    val done = finished
                                    val overall = ((done + fraction) / requests.size)
                                        .coerceIn(0f, 1f)
                                    notifier.progress(
                                        "Mengunggah ${done + 1}/${requests.size} berkas",
                                        "$name · ${(fraction * 100).toInt()}%",
                                        overall,
                                    )
                                },
                            )
                            logDiagnostic(
                                LOG_TAG,
                                "berkas ${if (result.isSuccess) "selesai" else "gagal"}: $name",
                            )
                            // Only after TDLib took the file: a failed or cancelled
                            // upload still needs its copy to retry from.
                            val freed = if (result.isSuccess && deleteCopies) {
                                deleteUploadedCopy(request.localPath)
                            } else {
                                0L
                            }
                            // The user's own file only goes away once Telegram has
                            // it, and only through the platform's recoverable route.
                            val originalGone = result.isSuccess && deleteOriginals &&
                                request.canDeleteSource && deleteOriginal(request)
                            val message = stats.withLock {
                                finished++
                                if (result.isSuccess) uploaded++ else failed++
                                freedBytes += freed
                                if (result.isSuccess && deleteOriginals && request.canDeleteSource) {
                                    if (originalGone) originalsDeleted++ else originalsFailed++
                                }
                                "$finished/${requests.size} selesai · $uploaded berhasil" +
                                    (if (failed > 0) ", $failed gagal" else "")
                            }
                            // One ongoing notification for the whole batch, updated
                            // instead of stacking a progress bar per file.
                            notifier.progress(
                                "Mengunggah berkas",
                                message,
                                (finished.toFloat() / requests.size).coerceIn(0f, 1f),
                            )
                            result.fold(
                                onSuccess = {
                                    if (single) {
                                        logDiagnostic(LOG_TAG, "unggah selesai: $name")
                                    }
                                },
                                onFailure = { failure ->
                                    logDiagnostic(
                                        LOG_TAG,
                                        "unggah gagal: $name: ${failure.message}",
                                    )
                                },
                            )
                        }
                    }
                }
            }

            notifier.clearProgress()
            // The cache copies are only needed while TDLib uploads them; keeping
            // them filled the cache after a few folder uploads.
            if (deleteCopies) cacheRoot?.let { root -> deleteCacheCopy(root) }
            if (uploaded > 0) refresh()
            val freedNote = freedBytes
                .takeIf { it > 0L }
                ?.let { "cache ${formatBytes(it)} dibersihkan" }
            val originalsNote = when {
                originalsDeleted > 0 && originalsFailed > 0 ->
                    "$originalsDeleted berkas asli dipindah ke Sampah, $originalsFailed gagal"
                originalsDeleted > 0 -> "$originalsDeleted berkas asli dipindah ke Sampah"
                originalsFailed > 0 -> "$originalsFailed berkas asli tidak bisa dipindah"
                else -> null
            }
            val extras = listOfNotNull(note, freedNote, originalsNote).joinToString(" · ")
            if (originalsDeleted > 0 || originalsFailed > 0) {
                logDiagnostic(
                    LOG_TAG,
                    "berkas asli: $originalsDeleted dipindah, $originalsFailed gagal",
                )
            }
            refreshCacheUsage()
            if (freedBytes > 0L) {
                logDiagnostic(LOG_TAG, "cache dibersihkan: ${formatBytes(freedBytes)}")
            }
            state.update {
                it.copy(
                    status = when {
                        single && failed == 0 -> "Unggahan $first selesai"
                        uploaded > 0 -> "$uploaded berkas diunggah"
                        else -> null
                    }?.let { message -> if (extras.isEmpty()) message else "$message · $extras" },
                    error = if (failed > 0) {
                        if (single) "$first gagal diunggah" else "$failed berkas gagal diunggah"
                    } else {
                        null
                    },
                )
            }

            // The system notification is the only result the user sees when the
            // app is no longer in the foreground.
            val noteSuffix = extras.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
            val notifyWithNote: (String, String) -> Unit = { title, message ->
                notifyTransfer(title, message + noteSuffix)
            }
            if (failed > 0) {
                notifyTransfer(
                    "Unggahan gagal",
                    (if (single) "$first gagal diunggah" else "$failed dari ${requests.size} berkas gagal") +
                        noteSuffix,
                )
            } else {
                notifyWithNote(
                    "Unggahan selesai",
                    if (single) {
                        "$first tersimpan di Telegram"
                    } else {
                        "${summary ?: "$uploaded berkas"} tersimpan di Telegram"
                    },
                )
            }
        }
    }

    private fun normalizeFolder(folder: String?): String? =
        folder?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Explicit folders win; without one the file is filed by its type. Every
     * upload therefore has a topic, so nobody has to pick a folder first.
     */
    private fun folderFor(folder: String?, localPath: String): String? {
        normalizeFolder(folder)?.let { return it }
        return if (state.value.autoTopics) FileCategories.folderFor(localPath.fileName()) else null
    }

    private fun String.fileName(): String =
        substringAfterLast('/').substringAfterLast('\\').ifBlank { "berkas" }

    /** System notification for a finished transfer; in-app feedback stays separate. */
    private fun notifyTransfer(title: String, message: String) {
        runCatching { notifier.notify(title, message) }
    }

    /** Re-reads whether system notifications may be posted, after the permission dialog. */
    fun refreshNotificationState() {
        if (!notifier.supported) return
        val enabled = notifier.enabled
        state.update { current ->
            if (current.notificationsEnabled == enabled) current else current.copy(notificationsEnabled = enabled)
        }
    }

    fun openNotificationSettings() {
        if (!notifier.openSettings()) {
            reportError("Pengaturan notifikasi tidak bisa dibuka di perangkat ini")
        }
    }

    /** Deletes the copies a picked folder left in the cache, once TDLib is done. */
    private suspend fun deleteCacheCopy(root: String) = withContext(Dispatchers.Default) {
        runCatching { FileSystem.SYSTEM.deleteRecursively(root.toPath()) }
        Unit
    }

    /**
     * Deletes the copy the app made for an upload and returns how many bytes
     * that freed. Desktop hands out the user's own files, so nothing is deleted
     * there; the check lives here so no caller can get it wrong.
     */
    private suspend fun deleteUploadedCopy(path: String): Long = withContext(Dispatchers.Default) {
        if (!pickedFilesAreAppCopies) return@withContext 0L
        runCatching {
            val target = path.toPath()
            val size = FileSystem.SYSTEM.metadataOrNull(target)?.size ?: 0L
            FileSystem.SYSTEM.delete(target, mustExist = false)
            size
        }.getOrDefault(0L)
    }

    /**
     * Removes one original through the platform's recoverable route. Blocking
     * provider and trash calls stay off the UI thread.
     */
    private suspend fun deleteOriginal(request: UploadRequest): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { deleteSourceFile(request.localPath, request.documentUri) }
                .getOrDefault(false)
        }

    /** Removes one synced original through the platform's recoverable route. */
    private suspend fun deleteGalleryOriginal(file: GalleryFile): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { deleteSourceFile(file.localPath, file.documentUri) }
                .getOrDefault(false)
        }

    /** Local copies handed out for previews, so reopening a file is instant. */
    private val previewPaths = mutableMapOf<Long, String>()

    private val thumbnailCache = mutableMapOf<Int, String>()
    private val missingThumbnails = mutableSetOf<Int>()
    private val coverPaths = mutableMapOf<Long, String>()
    private val missingCoverFiles = mutableSetOf<Long>()
    private val coverMutex = Mutex()

    /** Bounded write so browsing thousands of files cannot grow the caches forever. */
    private fun <K, V> MutableMap<K, V>.putBounded(key: K, value: V, limit: Int) {
        if (key !in this && size >= limit) keys.firstOrNull()?.let { remove(it) }
        this[key] = value
    }

    /**
     * Downloads [file] and hands the local path to [onDownloaded]. Used by the
     * share action, which needs TDLib's local copy before it can build an
     * `ACTION_SEND` intent.
     */
    fun download(file: CloudFile, onDownloaded: (String) -> Unit = {}) {
        val alreadyRunning = state.value.transfers.any {
            it.direction == TransferDirection.Download &&
                it.fileId == file.fileId &&
                it.status == TransferStatus.Running
        }
        if (alreadyRunning) {
            state.update { it.copy(status = "Unduhan ${file.name} sedang berjalan") }
            return
        }
        logDiagnostic(LOG_TAG, "unduh mulai: ${file.name} (file ${file.fileId})")

        val id = "transfer-${nextTransferId()}"
        state.update {
            it.copy(
                transfers = (listOf(
                    TransferItem(
                        id = id,
                        fileName = file.name,
                        direction = TransferDirection.Download,
                        progress = 0f,
                        fileId = file.fileId,
                        file = file,
                    ),
                ) + it.transfers).trimTransfers(),
                status = "Mengunduh ${file.name}…",
                error = null,
            )
        }
        notifier.progress("Mengunduh berkas", file.name)

        launchBatch {
            runCatching {
                repository.download(file.fileId) { progress ->
                    updateTransfer(id) { it.copy(progress = progress) }
                }
            }
                .onSuccess { path ->
                    logDiagnostic(LOG_TAG, "unduh selesai: ${file.name}")
                    updateTransfer(id) {
                        it.copy(
                            status = TransferStatus.Completed,
                            progress = 1f,
                            detail = path,
                        )
                    }
                    val savedTo = saveDownloadedFile(file, path)
                    state.update {
                        it.copy(
                            status = savedTo?.let { location -> "Tersimpan di $location" }
                                ?: "Tersimpan di cache aplikasi: ${file.name}",
                        )
                    }
                    notifier.clearProgress()
                    notifyTransfer("Unduhan selesai", "${file.name} siap dipakai")
                    onDownloaded(path)
                }
                .onFailure { failure ->
                    failTransfer(id, failure.message ?: "Unduhan gagal")
                }
        }
    }

    /**
     * Fetches TDLib's cached copy for the in-app preview. Unlike [download] this
     * adds no transfer entry and never writes to the device gallery: the bytes
     * stay in TDLib's cache and the caller renders them directly.
     */
    fun preview(
        file: CloudFile,
        onProgress: (Float?) -> Unit = {},
        onLoaded: (String) -> Unit,
        onFailed: (String) -> Unit = {},
    ) {
        // Re-opening a file used to start the download request again. A copy
        // this session already produced is handed out immediately.
        cachedPreviewPath(file.messageId)?.let { path ->
            onLoaded(path)
            return
        }
        logDiagnostic(LOG_TAG, "pratinjau mulai: ${file.name} (file ${file.fileId})")
        scope.launch {
            // Already downloaded by a transfer or an earlier preview: read it
            // from TDLib's cache instead of asking for the bytes again.
            val cached = runCatching { repository.cachedFilePath(file.fileId) }.getOrNull()
            if (cached != null) {
                rememberPreviewPath(file.messageId, cached)
                rememberCover(file, cached)
                logDiagnostic(LOG_TAG, "pratinjau dari cache: ${file.name}")
                onLoaded(cached)
                return@launch
            }
            runCatching { repository.download(file.fileId, onProgress = onProgress) }
                .onSuccess { path ->
                    // The dialog shows the cover right away for older files that had
                    // no thumbnail until this preview cached the full image.
                    rememberPreviewPath(file.messageId, path)
                    rememberCover(file, path)
                    onLoaded(path)
                }
                .onFailure { failure ->
                    logDiagnostic(LOG_TAG, "pratinjau gagal: ${file.name}: ${failure.message}")
                    onFailed(failure.message ?: "Gagal memuat pratinjau")
                }
        }
    }

    private fun cachedPreviewPath(messageId: Long): String? {
        val path = previewPaths[messageId] ?: return null
        if (!FileSystem.SYSTEM.exists(path.toPath())) {
            previewPaths.remove(messageId)
            return null
        }
        return path
    }

    private fun rememberPreviewPath(messageId: Long, path: String) {
        if (messageId !in previewPaths && previewPaths.size >= MAX_PREVIEW_CACHE_ENTRIES) {
            previewPaths.keys.firstOrNull()?.let { previewPaths.remove(it) }
        }
        previewPaths[messageId] = path
    }

    /**
     * Resolves a cover for the file card. New uploads carry TDLib's own preview;
     * older files fall back to a cover generated from the full file when it is
     * already downloaded, so nothing extra is fetched. Misses are memoised so a
     * list without covers does not retry on every scroll.
     */
    suspend fun cover(file: CloudFile): String? {
        // The network probe stays outside the mutex: holding it while TDLib
        // generated or fetched a thumbnail serialised every other card behind
        // one slow file (up to the 20 s thumbnail timeout each).
        coverMutex.withLock { coverPaths[file.messageId] }?.let { return it }
        if (coverMutex.withLock { file.messageId in missingCoverFiles }) return null

        val path = resolveThumbnail(file) ?: resolveCachedImage(file)
        coverMutex.withLock {
            if (path == null) {
                missingCoverFiles += file.messageId
                if (missingCoverFiles.size > MAX_COVER_CACHE_ENTRIES) missingCoverFiles.clear()
            } else {
                coverPaths.putBounded(file.messageId, path, MAX_COVER_CACHE_ENTRIES)
                missingCoverFiles -= file.messageId
            }
        }
        return path
    }

    /** Generates and caches a cover from a file the user just previewed or downloaded. */
    fun rememberCover(file: CloudFile, localPath: String) {
        scope.launch {
            val path = withContext(Dispatchers.Default) {
                createUploadThumbnail(localPath)?.path
            } ?: return@launch
            coverMutex.withLock {
                coverPaths.putBounded(file.messageId, path, MAX_COVER_CACHE_ENTRIES)
                missingCoverFiles -= file.messageId
            }
            state.update { it.copy(coverRevision = it.coverRevision + 1) }
        }
    }

    private suspend fun resolveThumbnail(file: CloudFile): String? {
        val id = file.thumbnailId ?: return null
        coverMutex.withLock { thumbnailCache[id] }?.let { return it }
        if (coverMutex.withLock { id in missingThumbnails }) return null
        val path = runCatching { repository.localThumbnail(id) }.getOrNull()
        coverMutex.withLock {
            if (path == null) {
                missingThumbnails += id
                if (missingThumbnails.size > MAX_COVER_CACHE_ENTRIES) missingThumbnails.clear()
            } else {
                thumbnailCache.putBounded(id, path, MAX_COVER_CACHE_ENTRIES)
                missingThumbnails -= id
            }
        }
        return path
    }

    private suspend fun resolveCachedImage(file: CloudFile): String? = runCatching {
        repository.cachedFilePath(file.fileId)?.let { cachedPath ->
            withContext(Dispatchers.Default) { createUploadThumbnail(cachedPath)?.path }
        }
    }.getOrNull()

    fun delete(file: CloudFile) = deleteMany(listOf(file))

    /** Deletes several messages with one request, so a multi-select stays fast. */
    fun deleteMany(files: List<CloudFile>) {
        if (files.isEmpty()) return
        logDiagnostic(LOG_TAG, "hapus ${files.size} berkas")
        scope.launch {
            state.update { it.copy(status = "Menghapus ${files.size} berkas…", error = null) }

            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(error = "Penyimpanan belum siap") }
                return@launch
            }

            runCatching { repository.deleteMessages(chatId, files.map { it.messageId }) }
                .onSuccess {
                    val removed = files.mapTo(mutableSetOf()) { it.messageId }
                    logDiagnostic(LOG_TAG, "hapus selesai: ${files.size} berkas")
                    // Folders the deleted files belonged to. Their topic is only
                    // removed when the whole history is in memory: an unloaded page
                    // may still hold files that would otherwise be lost with it.
                    val touchedFolders = files.mapNotNull { it.folder }.distinct()
                    val historyComplete = isHistoryComplete(state.value)
                    state.update { current ->
                        val remaining = current.files.filterNot { it.messageId in removed }
                        current.copy(
                            files = remaining,
                            folders = remaining.mapNotNull { it.folder }.distinct().sorted(),
                            selectedFolder = current.selectedFolder?.takeIf { folder ->
                                remaining.any { it.folder == folder }
                            },
                            status = if (files.size == 1) {
                                "${files.first().name} dihapus"
                            } else {
                                "${files.size} berkas dihapus"
                            },
                        )
                    }

                    if (historyComplete) {
                        val left = state.value.files
                        val emptied = touchedFolders.filter { folder ->
                            left.none { it.folder == folder }
                        }
                        val deleted = emptied.filter { folder ->
                            runCatching { repository.deleteTopic(chatId, folder) }
                                .getOrElse { failure ->
                                    logDiagnostic(
                                        LOG_TAG,
                                        "topik '$folder' gagal dihapus: ${failure.message}",
                                    )
                                    false
                                }
                        }
                        if (deleted.isNotEmpty()) {
                            state.update { current ->
                                current.copy(
                                    status = listOfNotNull(
                                        current.status,
                                        "topik ${deleted.joinToString(", ") { "'$it'" }} dihapus",
                                    ).joinToString(" · "),
                                )
                            }
                        }
                    }
                }
                .onFailure { failure ->
                    state.update { it.copy(error = failure.message ?: "Gagal menghapus berkas") }
                }
        }
    }

    /**
     * Downloads a selection with a few files in flight at once. Every file still
     * gets its own row in the Transfer tab; the counters are guarded because the
     * workers share a multi-threaded dispatcher.
     */
    fun downloadMany(files: List<CloudFile>) {
        if (files.isEmpty()) return
        logDiagnostic(LOG_TAG, "unduh massal: ${files.size} berkas")

        scope.launch {
            var saved = 0
            var failed = 0
            val stats = Mutex()

            coroutineScope {
                val queue = Channel<CloudFile>(capacity = files.size)
                files.forEach { queue.send(it) }
                queue.close()

                repeat(minOf(state.value.parallelTransfers, files.size)) {
                    launch {
                        for (file in queue) {
                            val id = "transfer-${nextTransferId()}"
                            addTransfer(
                                TransferItem(
                                    id = id,
                                    fileName = file.name,
                                    direction = TransferDirection.Download,
                                    progress = 0f,
                                    fileId = file.fileId,
                                    file = file,
                                ),
                            )

                            runCatching {
                                repository.download(file.fileId) { progress ->
                                    updateTransfer(id) { it.copy(progress = progress) }
                                }
                            }.fold(
                                onSuccess = { path ->
                                    updateTransfer(id) {
                                        it.copy(
                                            status = TransferStatus.Completed,
                                            progress = 1f,
                                            detail = path,
                                        )
                                    }
                                    saveDownloadedFile(file, path)
                                    stats.withLock { saved++ }
                                },
                                onFailure = { failure ->
                                    updateTransfer(id) {
                                        it.copy(
                                            status = TransferStatus.Failed,
                                            detail = failure.message ?: "Unduhan gagal",
                                        )
                                    }
                                    stats.withLock { failed++ }
                                },
                            )
                            val message = stats.withLock {
                                "${saved + failed}/${files.size} selesai · $saved berhasil" +
                                    (if (failed > 0) ", $failed gagal" else "")
                            }
                            notifier.progress("Mengunduh berkas", message)
                        }
                    }
                }
            }

            notifier.clearProgress()
            state.update {
                it.copy(
                    status = if (saved > 0) "$saved berkas diunduh" else null,
                    error = if (failed > 0) "$failed berkas gagal diunduh" else null,
                )
            }
        }
    }

    private fun addTransfer(item: TransferItem) {
        state.update { current ->
            current.copy(transfers = (listOf(item) + current.transfers).trimTransfers())
        }
    }

    fun clearFinishedTransfers() {
        state.update { current ->
            current.copy(transfers = current.transfers.filter { it.status == TransferStatus.Running })
        }
    }

    /** Drops one entry from the Transfer tab without touching the group. */
    fun removeTransfer(id: String) {
        state.update { current ->
            current.copy(transfers = current.transfers.filterNot { it.id == id })
        }
    }

    /** Restarts a failed transfer; the old entry is replaced by the new attempt. */
    fun retryTransfer(item: TransferItem) {
        item.file?.let { file ->
            removeTransfer(item.id)
            download(file)
            return
        }
        val path = item.localPath
        if (item.direction != TransferDirection.Upload || path == null) {
            reportError("Transfer ini tidak bisa diulang dari sini")
            return
        }
        if (!FileSystem.SYSTEM.exists(path.toPath())) {
            reportError("Salinan berkas sudah dibersihkan; unggah ulang dari perangkat")
            return
        }
        removeTransfer(item.id)
        startUploads(
            requests = listOf(
                UploadRequest(
                    localPath = path,
                    folder = item.folder,
                    documentUri = item.documentUri,
                ),
            ),
            summary = null,
        )
    }

    /**
     * Clears everything that belongs to the signed-in account. Called after a
     * logout, because the stored group id and the gallery sync history would
     * otherwise point at the previous account on the next sign-in.
     */
    fun forgotAccount() {
        channelManager.forget()
        preferences.clearAccountData()
        previewPaths.clear()
        thumbnailCache.clear()
        missingThumbnails.clear()
        coverPaths.clear()
        missingCoverFiles.clear()
        state.value = ExplorerUiState(
            gallerySupported = gallerySource.supported,
            galleryAutoSync = preferences.galleryAutoSync(),
            galleryBackgroundSupported = backgroundSync.supported,
            galleryBackgroundSync = preferences.galleryBackgroundSync(),
            galleryFolders = preferences.galleryFolders(),
            galleryLastSync = preferences.galleryLastSync(),
            autoSaveSupported = mediaSaver.supported,
            autoSavePhotos = preferences.autoSavePhotos(),
            parallelTransfers = preferences.transferParallelism(),
            autoTopics = preferences.autoTopics(),
            deleteUploadedCopies = preferences.deleteUploadedCopies(),
            deleteOriginals = preferences.deleteUploadedOriginals(),
            originalsDeletionSupported = sourceFileDeletionSupported,
            cacheSupported = uploadCacheSupported,
            storageTitle = preferences.cloudRootTitle(),
            storageMuted = preferences.cloudRootMuted(),
            storageArchived = preferences.cloudRootArchived(),
        )
    }

    // --- Gallery auto sync -----------------------------------------------------

    fun setGalleryAutoSync(enabled: Boolean) {
        preferences.setGalleryAutoSync(enabled)
        state.update { it.copy(galleryAutoSync = enabled) }
        refreshBackgroundSchedule()
        if (enabled) syncGallery(auto = true)
    }

    fun setGalleryBackgroundSync(enabled: Boolean) {
        preferences.setGalleryBackgroundSync(enabled)
        state.update { it.copy(galleryBackgroundSync = enabled) }
        refreshBackgroundSchedule()
        if (enabled) syncGallery(auto = true)
    }

    fun setAutoSavePhotos(enabled: Boolean) {
        preferences.setAutoSavePhotos(enabled)
        state.update { it.copy(autoSavePhotos = enabled) }
    }

    /** Adds one folder to the sync set; picking the same tree twice is a no-op. */
    fun addGalleryFolder(folder: GalleryFolder) {
        preferences.addGalleryFolder(folder)
        state.update { it.copy(galleryFolders = preferences.galleryFolders()) }
        refreshBackgroundSchedule()
        syncGallery(auto = true)
    }

    fun removeGalleryFolder(id: String) {
        preferences.removeGalleryFolder(id)
        state.update { it.copy(galleryFolders = preferences.galleryFolders()) }
        refreshBackgroundSchedule()
    }

    fun clearGalleryFolders() {
        preferences.clearGalleryFolders()
        preferences.clearGallerySyncedKeys()
        state.update { it.copy(galleryFolders = emptyList(), gallerySyncedCount = 0) }
        refreshBackgroundSchedule()
    }

    /**
     * Forgets which photos were uploaded, so the next sync offers them again.
     *
     * This no longer starts the sync itself: the Settings action used to look
     * like a second "Sinkronkan sekarang" and could re-upload the whole gallery
     * without the user realising. The next explicit sync picks the photos up.
     */
    fun resetGallerySync() {
        preferences.clearGallerySyncedKeys()
        state.update { it.copy(gallerySyncedCount = 0) }
        reportStatus("Riwayat sinkron diatur ulang. Tekan Sinkronkan sekarang untuk mengunggah ulang.")
    }

    /**
     * Uploads every new file - any type, from every subfolder - into the
     * "Galeri" folder of the storage group, keeping the folder path it had on
     * disk. Files are read in batches of [GALLERY_BATCH_SIZE] so a large folder
     * does not have to be copied to the cache at once. Runs once per app session
     * when auto sync is enabled, and on demand from Settings.
     *
     * @param perFolderLimit how many files one folder may contribute; the
     *   background worker passes a small number to stay inside its time window.
     */
    fun syncGallery(auto: Boolean = false, perFolderLimit: Int = Int.MAX_VALUE) {
        if (!gallerySource.supported) {
            if (!auto) reportError("Sinkron folder tidak tersedia di perangkat ini")
            return
        }
        // The automatic entry point must respect the toggle; only the explicit
        // "Sinkronkan sekarang" action ignores it.
        if (auto && !preferences.galleryAutoSync()) return

        val folders = preferences.galleryFolders()
        if (folders.isEmpty()) {
            if (!auto) reportError("Pilih folder yang akan disinkronkan terlebih dahulu")
            return
        }

        // Claim the sync in one atomic state update. Checking the flag and
        // launching in two steps let two callers (Settings button, shell effect,
        // background worker) pass the check together and upload the same photos
        // twice.
        var claimed = false
        state.update { current ->
            if (current.gallerySyncing) {
                current
            } else {
                claimed = true
                current.copy(gallerySyncing = true, error = null)
            }
        }
        if (!claimed) return

        launchBatch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update {
                    it.copy(gallerySyncing = false, error = "Penyimpanan belum siap")
                }
                return@launchBatch
            }

            notifier.progress("Sinkron folder", "Memeriksa folder pilihan…")

            var uploaded = 0
            var failed = 0
            // Documents the source could not prepare (unreadable, or too big for
            // the batch's cache budget). They are reported instead of vanishing.
            var skipped = 0
            // Originals moved to trash after their upload succeeded; only when
            // the user turned the option on.
            var originalsDeleted = 0
            var originalsFailed = 0

            // Every selected folder contributes its own incremental batch, so a
            // second folder does not wait for the first one to be exhausted.
            // Photos are read batch by batch until the folder has no new file
            // left, instead of stopping after one fixed-size batch: a folder with
            // more files than the batch used to keep the rest waiting for the
            // next sync. Attempted keys are remembered so a file that keeps
            // failing cannot loop forever.
            // Files that failed three times are skipped: otherwise one broken
            // photo could use the whole background batch on every single run.
            val attempted = (
                preferences.gallerySyncedKeys() +
                    preferences.galleryFailedAttempts()
                        .filterValues { it >= MAX_GALLERY_ATTEMPTS }
                        .keys
                ).toMutableSet()

            // Count the waiting files before copying anything, so the banner and
            // the notification can say how many files and roughly how long,
            // instead of showing an endless spinner.
            var planned = 0
            folders.forEach { folder ->
                planned += runCatching {
                    gallerySource.countPending(folder, attempted)
                }.getOrDefault(0)
            }
            val startedAt = TimeSource.Monotonic.markNow()
            var doneSoFar = 0

            fun etaText(seconds: Long?): String = when {
                seconds == null -> "menghitung…"
                seconds < 60 -> "±${seconds.coerceAtLeast(1)} detik"
                else -> "±${(seconds + 59) / 60} menit"
            }

            state.update {
                it.copy(galleryPlanned = planned, galleryDone = 0, galleryEtaSeconds = null)
            }
            logDiagnostic(LOG_TAG, "sinkron folder: $planned berkas menunggu")
            notifier.progress(
                "Sinkron folder",
                "0/$planned berkas · menyiapkan…",
                0f,
            )

            for (folder in folders) {
                var folderUploaded = 0
                var folderFailed = 0

                while (folderUploaded + folderFailed < perFolderLimit) {
                    val remaining = perFolderLimit - folderUploaded - folderFailed
                    val batch = runCatching {
                        gallerySource.pendingFiles(
                            folder = folder,
                            alreadySynced = attempted,
                            limit = minOf(GALLERY_BATCH_SIZE, remaining),
                        )
                    }.getOrElse { failure ->
                        folderFailed++
                        logDiagnostic(
                            LOG_TAG,
                            "galeri ${folder.name} gagal dibaca: ${failure.message}",
                        )
                        // A folder that cannot be read will not heal inside this
                        // run, so it is left to the next sync instead of being
                        // retried until the per-folder limit.
                        null
                    } ?: break
                    // Skipped keys are not retried inside this run: the budget
                    // shrinks as files are copied, so a second attempt would
                    // read the same unreadable document again. The next sync
                    // starts with fresh space and tries them again.
                    batch.skippedKeys.forEach { key -> attempted += key }
                    if (batch.skippedKeys.isNotEmpty()) {
                        skipped += batch.skippedKeys.size
                        logDiagnostic(
                            LOG_TAG,
                            "galeri ${folder.name}: ${batch.skippedKeys.size} berkas dilewati",
                        )
                    }
                    if (batch.files.isEmpty()) break

                    val syncedKeys = mutableListOf<String>()
                    val failedKeys = mutableListOf<String>()

                    // The batch uploads in parallel too: a synced folder of
                    // hundreds of files used to move one file at a time.
                    val keyLock = Mutex()
                    coroutineScope {
                        val queue = Channel<GalleryFile>(capacity = batch.files.size)
                        batch.files.forEach { file ->
                            attempted += file.key
                            queue.trySend(file)
                        }
                        queue.close()

                        repeat(minOf(state.value.parallelTransfers, batch.files.size)) {
                            launch {
                                for (file in queue) {
                                    val transferId = "transfer-${nextTransferId()}"
                                    addTransfer(
                                        TransferItem(
                                            id = transferId,
                                            fileName = file.name,
                                            direction = TransferDirection.Upload,
                                            progress = 0f,
                                        ),
                                    )

                                    // Synced files are filed by type as well, and
                                    // the tree is recreated beneath that topic: a
                                    // photo from Liburan/Pantai lands in
                                    // Foto/Liburan/Pantai instead of "Galeri".
                                    // With automatic topics off they keep using
                                    // the single "Galeri" folder as before.
                                    val topic = if (state.value.autoTopics) {
                                        FileCategories.folderFor(file.name)
                                    } else {
                                        GALLERY_FOLDER
                                    }
                                    val target = topic +
                                        file.relativeDirectory
                                            .takeIf { it.isNotBlank() }
                                            ?.let { "/$it" }
                                            .orEmpty()
                                    val result = uploadFile(
                                        transferId = transferId,
                                        chatId = chatId,
                                        localPath = file.localPath,
                                        folder = target,
                                    )
                                    gallerySource.release(listOf(file))
                                    // The original only moves once Telegram has the
                                    // file, and only through the recoverable route.
                                    if (result.isSuccess && state.value.deleteOriginals) {
                                        val gone = deleteGalleryOriginal(file)
                                        keyLock.withLock {
                                            if (gone) originalsDeleted++ else originalsFailed++
                                        }
                                    }

                                    result.fold(
                                        onSuccess = {
                                            keyLock.withLock {
                                                folderUploaded++
                                                syncedKeys += file.key
                                            }
                                            logDiagnostic(LOG_TAG, "sinkron folder: ${file.name}")
                                        },
                                        onFailure = { failure ->
                                            keyLock.withLock {
                                                folderFailed++
                                                failedKeys += file.key
                                            }
                                            logDiagnostic(
                                                LOG_TAG,
                                                "sinkron folder gagal: ${file.name}: " +
                                                    failure.message,
                                            )
                                        },
                                    )
                                    val eta = keyLock.withLock {
                                        doneSoFar++
                                        val elapsed = startedAt.elapsedNow().inWholeSeconds
                                        val perFile = if (doneSoFar > 0) {
                                            elapsed.toDouble() / doneSoFar
                                        } else {
                                            0.0
                                        }
                                        val remaining = ((planned - doneSoFar).coerceAtLeast(0) * perFile)
                                            .toLong()
                                            .takeIf { planned > doneSoFar }
                                        state.update {
                                            it.copy(
                                                galleryDone = doneSoFar,
                                                galleryEtaSeconds = remaining,
                                            )
                                        }
                                        remaining
                                    }
                                    notifier.progress(
                                        "Sinkron folder",
                                        "$doneSoFar/$planned berkas · ${folder.name} · sisa ${etaText(eta)}",
                                        if (planned > 0) {
                                            (doneSoFar.toFloat() / planned).coerceIn(0f, 1f)
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // One settings write per batch instead of one per photo: the
                    // whole file was rewritten (and read back) for every image.
                    if (syncedKeys.isNotEmpty()) {
                        preferences.addGallerySyncedKeys(syncedKeys)
                        preferences.clearGalleryFailures(syncedKeys)
                    }
                    if (failedKeys.isNotEmpty()) preferences.recordGalleryFailures(failedKeys)
                }

                uploaded += folderUploaded
                failed += folderFailed
            }

            notifier.clearProgress()
            val skippedNote = if (skipped > 0) " · $skipped berkas dilewati" else ""
            val originalsNote = when {
                originalsDeleted > 0 && originalsFailed > 0 ->
                    " · $originalsDeleted berkas asli dipindah ke Sampah, $originalsFailed gagal"
                originalsDeleted > 0 -> " · $originalsDeleted berkas asli dipindah ke Sampah"
                originalsFailed > 0 -> " · $originalsFailed berkas asli tidak bisa dipindah"
                else -> ""
            }
            if (uploaded > 0) {
                notifyTransfer(
                    "Sinkron folder selesai",
                    "$uploaded berkas tersimpan di topiknya$skippedNote$originalsNote",
                )
            } else if (failed > 0) {
                notifyTransfer(
                    "Sinkron folder gagal",
                    "$failed berkas tidak bisa diunggah$skippedNote",
                )
            }

            if (uploaded > 0) refresh()

            val stamp = platformTimestamp()
            preferences.setGalleryLastSync(stamp)
            state.update {
                it.copy(
                    gallerySyncing = false,
                    galleryLastSync = stamp,
                    gallerySyncedCount = preferences.gallerySyncedKeys().size,
                    galleryPlanned = 0,
                    galleryDone = 0,
                    galleryEtaSeconds = null,
                    status = when {
                        uploaded > 0 -> "$uploaded berkas disinkronkan$skippedNote$originalsNote"
                        failed == 0 && skipped > 0 ->
                            "$skipped berkas dilewati: tidak bisa dibaca atau tidak muat di cache"
                        failed == 0 -> "Folder sudah sinkron"
                        else -> null
                    },
                    error = if (failed > 0) "$failed berkas gagal disinkronkan" else null,
                )
            }
        }
    }

    // --- Internals -------------------------------------------------------------

    /**
     * Copies a finished download somewhere the user can open it. Images go to
     * the photo library when auto-save is on; everything else (and images with
     * the toggle off) goes to the downloads folder. Returns the shown location.
     */
    private suspend fun saveDownloadedFile(file: CloudFile, localPath: String): String? {
        val mimeType = imageMimeType(file.name)
        if (mimeType != null && mediaSaver.supported && preferences.autoSavePhotos()) {
            val saved = runCatching { mediaSaver.saveImage(localPath, file.name, mimeType) }
                .getOrDefault(false)
            if (saved) return "galeri (Pictures/Unlimited Cloud)"
        }
        return runCatching {
            mediaSaver.saveFile(localPath, file.name, mimeType ?: "application/octet-stream")
        }.getOrNull()
    }

    private fun imageMimeType(name: String): String? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            else -> null
        }

    private fun refreshBackgroundSchedule() {
        if (!backgroundSync.supported) return
        val active = state.value.galleryAutoSync &&
            preferences.galleryBackgroundSync() &&
            preferences.galleryFolders().isNotEmpty()
        if (active) backgroundSync.schedule() else backgroundSync.cancel()
    }

    private suspend fun uploadFile(
        transferId: String,
        chatId: Long,
        localPath: String,
        folder: String?,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> = runCatching {
        // Images carry a small generated preview so the group message (and the
        // file card) has a cover from the first upload onwards.
        val thumbnail = withContext(Dispatchers.Default) { createUploadThumbnail(localPath) }
        repository.upload(chatId, localPath, folder, thumbnail) { progress ->
            updateTransfer(transferId) { it.copy(progress = progress) }
            progress?.let(onProgress)
        }
    }.onSuccess {
        updateTransfer(transferId) {
            it.copy(
                status = TransferStatus.Completed,
                progress = 1f,
                detail = "Tersimpan di Telegram",
            )
        }
    }.onFailure { failure ->
        updateTransfer(transferId) {
            it.copy(
                status = TransferStatus.Failed,
                detail = failure.message ?: "Transfer gagal",
            )
        }
    }

    private fun updateTransfer(id: String, transform: (TransferItem) -> TransferItem) {
        state.update { current ->
            current.copy(
                transfers = current.transfers.map { item ->
                    if (item.id == id) transform(item) else item
                },
            )
        }
    }

    private fun failTransfer(id: String, message: String) {
        val transfer = state.value.transfers.firstOrNull { item -> item.id == id }
        logDiagnostic(LOG_TAG, "transfer gagal: $message")
        updateTransfer(id) {
            it.copy(
                status = TransferStatus.Failed,
                detail = message,
            )
        }
        state.update { it.copy(status = null, error = message) }
        // Uploads report their own batch result in startUploads; downloads have
        // no other surface once the app is in the background.
        if (transfer?.direction == TransferDirection.Download) {
            notifier.clearProgress()
            notifyTransfer("Unduhan gagal", "${transfer.fileName}: $message")
        }
    }

    /**
     * Loads a few pages per request so reaching the end of a large group does
     * not need one round trip per 50 files when the user asks for more.
     */
    private suspend fun loadMorePages(chatId: Long, fromMessageId: Long): LoadedFiles =
        collectPages(fromMessageId = fromMessageId, maxPages = PAGES_PER_MORE) { cursor ->
            loadPage(chatId, cursor)
        }

    /**
     * Loads one page, retrying while TDLib is still opening the chat. A request
     * sent right after sign-in used to come back as a temporary error, which left
     * the explorer empty until the list was refreshed by hand.
     */
    private suspend fun loadPage(chatId: Long, fromMessageId: Long = 0L): CloudPage {
        var lastFailure: Throwable? = null
        repeat(PAGE_ATTEMPTS) { attempt ->
            runCatching { repository.listFiles(chatId, fromMessageId) }
                .onSuccess { return it.filedByType(state.value.autoTopics) }
                .onFailure { failure ->
                    lastFailure = failure
                    delay(PAGE_RETRY_MILLIS * (attempt + 1))
                }
        }
        throw lastFailure ?: IllegalStateException("Gagal memuat daftar berkas")
    }

    /** Files caption-less messages under their type topic; off keeps them loose. */
    private fun CloudPage.filedByType(enabled: Boolean): CloudPage =
        if (!enabled) {
            this
        } else {
            copy(
                files = files.map { file ->
                    if (file.folderFromCaption) file
                    else file.copy(folder = FileCategories.folderFor(file.name))
                },
            )
        }

    /** Resolves the group, waiting out a TDLib session that is still starting. */
    private suspend fun ensureChannelWithRetry(): Long? {
        repeat(CHANNEL_ATTEMPTS) { attempt ->
            channelManager.ensureRootChannel()?.let { return it }
            delay(CHANNEL_RETRY_MILLIS * (attempt + 1))
        }
        return null
    }

    private companion object {
        const val GALLERY_BATCH_SIZE = 20
        const val CHANNEL_ATTEMPTS = 3
        const val CHANNEL_RETRY_MILLIS = 1_500L
        const val PAGE_ATTEMPTS = 3
        const val PAGE_RETRY_MILLIS = 1_000L
        const val GALLERY_FOLDER = FileCategories.GALLERY
        const val MAX_GALLERY_ATTEMPTS = 3
        const val MAX_COMPLETE_PAGES = 500

        /** Moves per tidy slice, so a burst stays under the account limits. */
        const val MAX_TIDY_PER_PASS = 40

        /** Slices one tidy run may send before the rest waits for the next run. */
        const val MAX_TIDY_RUNS = 10

        /** Pause between slices, long enough for Telegram to forget the burst. */
        const val TIDY_PAUSE_MILLIS = 2_000L
        const val MAX_COVER_CACHE_ENTRIES = 500
        const val MAX_PREVIEW_CACHE_ENTRIES = 12
    }
}

private const val MAX_TRANSFERS = 200

/**
 * Caps the transfer list without dropping a running transfer. Dropping one used
 * to hide that a download was still active, so the retry started a second one.
 */
internal fun List<TransferItem>.trimTransfers(
    limit: Int = MAX_TRANSFERS,
): List<TransferItem> {
    val running = filter { it.status == TransferStatus.Running }
    val finished = filter { it.status != TransferStatus.Running }
    return running.take(limit) + finished.take((limit - running.size).coerceAtLeast(0))
}

/**
 * True only when every history page is in memory. `canLoadMore` alone is false
 * while a refresh or a load-more request is still running, so deleting files in
 * that window used to remove a topic that still had unloaded files in it.
 */
internal fun isHistoryComplete(state: ExplorerUiState): Boolean =
    !state.loading && !state.loadingMore && !state.canLoadMore && state.nextFromMessageId == null
