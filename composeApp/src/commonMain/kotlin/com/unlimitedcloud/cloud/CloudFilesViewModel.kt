package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.log.platformTimestamp
import com.unlimitedcloud.platform.GalleryBackgroundSync
import com.unlimitedcloud.platform.GalleryFile
import com.unlimitedcloud.platform.GalleryFolder
import com.unlimitedcloud.platform.GallerySyncSource
import com.unlimitedcloud.platform.LocalFolderSelection
import com.unlimitedcloud.platform.MediaSaver
import com.unlimitedcloud.platform.TransferNotifier
import com.unlimitedcloud.platform.synchronizedBlock
import com.unlimitedcloud.platform.createTransferNotifier
import com.unlimitedcloud.platform.createUploadThumbnail
import com.unlimitedcloud.settings.AppPreferences
import com.unlimitedcloud.settings.MAX_TRANSFER_PARALLELISM
import com.unlimitedcloud.settings.MIN_TRANSFER_PARALLELISM
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
    val autoSaveSupported: Boolean = false,
    val autoSavePhotos: Boolean = false,
    /** How many files a batch uploads or downloads at once (Settings). */
    val parallelTransfers: Int = 3,
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
            notificationsSupported = notifier.supported,
            notificationsEnabled = !notifier.supported || notifier.enabled,
        ),
    )
    val uiState: StateFlow<ExplorerUiState> = state.asStateFlow()

    init {
        refreshBackgroundSchedule()
    }

    private var transferCounter = 0L
    private val transferIdLock = Any()
    private var refreshJob: Job? = null

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

    /** One file waiting its turn, with the folder it will land in. */
    private data class UploadRequest(val localPath: String, val folder: String?)

    fun upload(localPath: String, folder: String?) {
        startUploads(
            requests = listOf(UploadRequest(localPath, normalizeFolder(folder))),
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
            requests = unique.map { path -> UploadRequest(path, target) },
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

        scope.launch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(status = null, error = "Penyimpanan belum siap") }
                notifyTransfer("Unggahan gagal", "Penyimpanan belum siap")
                return@launch
            }

            var uploaded = 0
            var failed = 0
            var finished = 0
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
                                ),
                            )

                            val result = uploadFile(
                                transferId = transferId,
                                chatId = chatId,
                                localPath = request.localPath,
                                folder = request.folder,
                            )
                            val message = stats.withLock {
                                finished++
                                if (result.isSuccess) uploaded++ else failed++
                                "$finished/${requests.size} selesai · $uploaded berhasil" +
                                    (if (failed > 0) ", $failed gagal" else "")
                            }
                            // One ongoing notification for the whole batch, updated
                            // instead of stacking a progress bar per file.
                            notifier.progress("Mengunggah berkas", message)
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
            cacheRoot?.let { root -> deleteCacheCopy(root) }
            if (uploaded > 0) refresh()
            state.update {
                it.copy(
                    status = when {
                        single && failed == 0 -> "Unggahan $first selesai"
                        uploaded > 0 -> "$uploaded berkas diunggah"
                        else -> null
                    }?.let { message -> note?.let { "$message · $it" } ?: message },
                    error = if (failed > 0) {
                        if (single) "$first gagal diunggah" else "$failed berkas gagal diunggah"
                    } else {
                        null
                    },
                )
            }

            // The system notification is the only result the user sees when the
            // app is no longer in the foreground.
            val noteSuffix = note?.let { " · $it" }.orEmpty()
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

        scope.launch {
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

    /** Restarts a failed download; the old entry is replaced by the new attempt. */
    fun retryTransfer(item: TransferItem) {
        val file = item.file
        if (file == null) {
            reportError("Transfer ini tidak bisa diulang dari sini")
            return
        }
        removeTransfer(item.id)
        download(file)
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

        scope.launch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update {
                    it.copy(gallerySyncing = false, error = "Penyimpanan belum siap")
                }
                return@launch
            }

            notifier.progress("Sinkron folder", "Memeriksa folder pilihan…")

            var uploaded = 0
            var failed = 0

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
                        emptyList()
                    }
                    if (batch.isEmpty()) break

                    val syncedKeys = mutableListOf<String>()
                    val failedKeys = mutableListOf<String>()

                    // The batch uploads in parallel too: a synced folder of
                    // hundreds of files used to move one file at a time.
                    val keyLock = Mutex()
                    coroutineScope {
                        val queue = Channel<GalleryFile>(capacity = batch.size)
                        batch.forEach { file ->
                            attempted += file.key
                            queue.trySend(file)
                        }
                        queue.close()

                        repeat(minOf(state.value.parallelTransfers, batch.size)) {
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

                                    // Recreate the tree: a file from
                                    // Liburan/Pantai lands in Galeri/Liburan/Pantai
                                    // instead of being flattened.
                                    val target = if (file.relativeDirectory.isBlank()) {
                                        GALLERY_FOLDER
                                    } else {
                                        "$GALLERY_FOLDER/${file.relativeDirectory}"
                                    }
                                    val result = uploadFile(
                                        transferId = transferId,
                                        chatId = chatId,
                                        localPath = file.localPath,
                                        folder = target,
                                    )
                                    gallerySource.release(listOf(file))

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
                                    notifier.progress(
                                        "Sinkron folder",
                                        "${folder.name} · ${file.name} selesai",
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
            if (uploaded > 0) {
                notifyTransfer(
                    "Sinkron folder selesai",
                    "$uploaded berkas tersimpan di folder Galeri",
                )
            } else if (failed > 0) {
                notifyTransfer("Sinkron folder gagal", "$failed berkas tidak bisa diunggah")
            }

            if (uploaded > 0) refresh()

            val stamp = platformTimestamp()
            preferences.setGalleryLastSync(stamp)
            state.update {
                it.copy(
                    gallerySyncing = false,
                    galleryLastSync = stamp,
                    gallerySyncedCount = preferences.gallerySyncedKeys().size,
                    status = when {
                        uploaded > 0 -> "$uploaded berkas disinkronkan"
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
    ): Result<Unit> = runCatching {
        // Images carry a small generated preview so the group message (and the
        // file card) has a cover from the first upload onwards.
        val thumbnail = withContext(Dispatchers.Default) { createUploadThumbnail(localPath) }
        repository.upload(chatId, localPath, folder, thumbnail) { progress ->
            updateTransfer(transferId) { it.copy(progress = progress) }
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
                .onSuccess { return it }
                .onFailure { failure ->
                    lastFailure = failure
                    delay(PAGE_RETRY_MILLIS * (attempt + 1))
                }
        }
        throw lastFailure ?: IllegalStateException("Gagal memuat daftar berkas")
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
        const val GALLERY_FOLDER = "Galeri"
        const val MAX_GALLERY_ATTEMPTS = 3
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
