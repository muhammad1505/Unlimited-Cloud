package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.log.platformTimestamp
import com.unlimitedcloud.platform.GalleryBackgroundSync
import com.unlimitedcloud.platform.GalleryFolder
import com.unlimitedcloud.platform.GallerySyncSource
import com.unlimitedcloud.platform.LocalFolderSelection
import com.unlimitedcloud.platform.MediaSaver
import com.unlimitedcloud.platform.createUploadThumbnail
import com.unlimitedcloud.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Full history loaded in one refresh, plus the cursor left for more. */
private data class LoadedFiles(val files: List<CloudFile>, val nextFromMessageId: Long?)

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
    /** Bumped when a cover becomes available so cards retry their thumbnail. */
    val coverRevision: Int = 0,
) {
    val visibleFiles: List<CloudFile>
        get() {
            val filtered = files.filter { file ->
                (selectedFolder == null || file.folder == selectedFolder) &&
                    (query.isBlank() || file.name.contains(query.trim(), ignoreCase = true))
            }
            return when (sort) {
                FileSort.Newest -> filtered.sortedByDescending { it.messageId }
                FileSort.Name -> filtered.sortedBy { it.name.lowercase() }
                FileSort.Size -> filtered.sortedByDescending { it.size }
            }
        }

    val totalBytes: Long
        get() = files.sumOf { it.size }

    val activeTransfers: List<TransferItem>
        get() = transfers.filter { it.status == TransferStatus.Running }

    val finishedTransfers: List<TransferItem>
        get() = transfers.filter { it.status != TransferStatus.Running }

    fun folderCount(folder: String): Int = files.count { it.folder == folder }
}

/**
 * Drives the file list, history pagination, search, one transfer at a time and
 * the incremental gallery sync.
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
        ),
    )
    val uiState: StateFlow<ExplorerUiState> = state.asStateFlow()

    init {
        refreshBackgroundSchedule()
    }

    private var transferCounter = 0L

    fun refresh() {
        scope.launch {
            state.update { it.copy(loading = true, error = null) }

            val chatId = ensureChannelWithRetry()
            if (chatId == null) {
                state.update { it.copy(loading = false, error = "Channel penyimpanan belum siap") }
                return@launch
            }

            // Every page is loaded here: leaving the list at the first page made
            // Settings report the channel's real count while the explorer stopped
            // at 50 files. MAX_AUTO_PAGES still bounds a very large channel.
            runCatching { loadAllPages(chatId) }
                .onSuccess { loaded ->
                    state.update { previous ->
                        val folders = loaded.files.mapNotNull { it.folder }.distinct().sorted()
                        previous.copy(
                            files = loaded.files,
                            folders = folders,
                            selectedFolder = previous.selectedFolder?.takeIf { it in folders },
                            loading = false,
                            loadingMore = false,
                            canLoadMore = loaded.nextFromMessageId != null,
                            nextFromMessageId = loaded.nextFromMessageId,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    state.update {
                        it.copy(loading = false, error = failure.message ?: "Gagal memuat daftar berkas")
                    }
                }
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
                state.update { it.copy(loadingMore = false, error = "Channel penyimpanan belum siap") }
                return@launch
            }

            runCatching { loadPage(chatId, fromMessageId = cursor) }
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

    fun upload(localPath: String, folder: String?) {
        val id = "transfer-${++transferCounter}"
        val fileName = localPath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "berkas" }
        val normalizedFolder = folder?.trim()?.takeIf { it.isNotEmpty() }
        logDiagnostic(LOG_TAG, "unggah mulai: $fileName → ${normalizedFolder ?: "(root)"}")

        state.update {
            it.copy(
                transfers = listOf(
                    TransferItem(
                        id = id,
                        fileName = fileName,
                        direction = TransferDirection.Upload,
                        progress = 0f,
                    ),
                ) + it.transfers,
                status = "Mengunggah $fileName…",
                error = null,
            )
        }

        scope.launch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                failTransfer(id, "Channel penyimpanan belum siap")
                return@launch
            }

            uploadFile(id, chatId, localPath, normalizedFolder)
                .onSuccess {
                    logDiagnostic(LOG_TAG, "unggah selesai: $fileName")
                    state.update { it.copy(status = "Unggahan $fileName selesai") }
                    refresh()
                }
                .onFailure { failure ->
                    failTransfer(id, failure.message ?: "Unggahan gagal")
                }
        }
    }

    /**
     * Uploads every file of a picked folder into one virtual folder. Progress is
     * tracked per file, so the Transfer tab lists them like single uploads.
     */
    fun uploadFolder(selection: LocalFolderSelection, folder: String?) {
        if (selection.files.isEmpty()) {
            reportError("Folder ${selection.name} tidak berisi berkas")
            return
        }
        val target = folder?.trim()?.takeIf { it.isNotEmpty() } ?: selection.name
        logDiagnostic(
            LOG_TAG,
            "unggah folder: ${selection.name} (${selection.files.size} berkas) → $target",
        )
        state.update {
            it.copy(
                status = "Mengunggah ${selection.files.size} berkas dari ${selection.name}…",
                error = null,
            )
        }

        scope.launch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                reportError("Channel penyimpanan belum siap")
                return@launch
            }

            var uploaded = 0
            var failed = 0

            selection.files.forEach { path ->
                val transferId = "transfer-${++transferCounter}"
                val fileName = path.substringAfterLast('/').substringAfterLast('\\')
                state.update { current ->
                    current.copy(
                        transfers = listOf(
                            TransferItem(
                                id = transferId,
                                fileName = fileName.ifBlank { "berkas" },
                                direction = TransferDirection.Upload,
                                progress = 0f,
                            ),
                        ) + current.transfers,
                    )
                }

                uploadFile(transferId, chatId, path, target).fold(
                    onSuccess = { uploaded++ },
                    onFailure = { failure ->
                        failed++
                        logDiagnostic(LOG_TAG, "folder gagal: $fileName: ${failure.message}")
                    },
                )
            }

            if (uploaded > 0) refresh()
            state.update {
                it.copy(
                    status = if (uploaded > 0) {
                        "$uploaded berkas dari ${selection.name} diunggah"
                    } else {
                        null
                    },
                    error = if (failed > 0) "$failed berkas gagal diunggah" else null,
                )
            }
        }
    }

    /**
     * Downloads [file] and hands the local path to [onDownloaded]. Used by the
     * share action, which needs TDLib's local copy before it can build an
     * `ACTION_SEND` intent.
     */
    private val thumbnailCache = mutableMapOf<Int, String>()
    private val missingThumbnails = mutableSetOf<Int>()
    private val coverPaths = mutableMapOf<Long, String>()
    private val missingCoverFiles = mutableSetOf<Long>()
    private val coverMutex = Mutex()

    fun download(file: CloudFile, onDownloaded: (String) -> Unit = {}) {
        val alreadyRunning = state.value.transfers.any {
            it.fileId == file.fileId && it.status == TransferStatus.Running
        }
        if (alreadyRunning) {
            state.update { it.copy(status = "Unduhan ${file.name} sedang berjalan") }
            return
        }
        logDiagnostic(LOG_TAG, "unduh mulai: ${file.name} (file ${file.fileId})")

        val id = "transfer-${++transferCounter}"
        state.update {
            it.copy(
                transfers = listOf(
                    TransferItem(
                        id = id,
                        fileName = file.name,
                        direction = TransferDirection.Download,
                        progress = 0f,
                        fileId = file.fileId,
                    ),
                ) + it.transfers,
                status = "Mengunduh ${file.name}…",
                error = null,
            )
        }

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
                    val savedToGallery = saveToDeviceGallery(file, path)
                    state.update {
                        it.copy(
                            status = if (savedToGallery) {
                                "Tersimpan di galeri: ${file.name}"
                            } else {
                                "Tersimpan: ${path.substringAfterLast('/').substringAfterLast('\\')}"
                            },
                        )
                    }
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
        logDiagnostic(LOG_TAG, "pratinjau mulai: ${file.name} (file ${file.fileId})")
        scope.launch {
            runCatching { repository.download(file.fileId, onProgress = onProgress) }
                .onSuccess { path ->
                    // The dialog shows the cover right away for older files that had
                    // no thumbnail until this preview cached the full image.
                    rememberCover(file, path)
                    onLoaded(path)
                }
                .onFailure { failure ->
                    logDiagnostic(LOG_TAG, "pratinjau gagal: ${file.name}: ${failure.message}")
                    onFailed(failure.message ?: "Gagal memuat pratinjau")
                }
        }
    }

    /**
     * Resolves a cover for the file card. New uploads carry TDLib's own preview;
     * older files fall back to a cover generated from the full file when it is
     * already downloaded, so nothing extra is fetched. Misses are memoised so a
     * list without covers does not retry on every scroll.
     */
    suspend fun cover(file: CloudFile): String? = coverMutex.withLock {
        coverPaths[file.messageId]?.let { return@withLock it }
        if (file.messageId in missingCoverFiles) return@withLock null

        val path = resolveThumbnail(file) ?: resolveCachedImage(file)
        if (path == null) {
            missingCoverFiles += file.messageId
            null
        } else {
            coverPaths[file.messageId] = path
            path
        }
    }

    /** Generates and caches a cover from a file the user just previewed or downloaded. */
    fun rememberCover(file: CloudFile, localPath: String) {
        scope.launch {
            val path = withContext(Dispatchers.Default) {
                createUploadThumbnail(localPath)?.path
            } ?: return@launch
            coverMutex.withLock {
                coverPaths[file.messageId] = path
                missingCoverFiles -= file.messageId
            }
            state.update { it.copy(coverRevision = it.coverRevision + 1) }
        }
    }

    private suspend fun resolveThumbnail(file: CloudFile): String? {
        val id = file.thumbnailId ?: return null
        thumbnailCache[id]?.let { return it }
        if (id in missingThumbnails) return null
        val path = runCatching { repository.localThumbnail(id) }.getOrNull()
        if (path == null) {
            missingThumbnails += id
        } else {
            thumbnailCache[id] = path
        }
        return path
    }

    private suspend fun resolveCachedImage(file: CloudFile): String? = runCatching {
        repository.cachedFilePath(file.fileId)?.let { cachedPath ->
            withContext(Dispatchers.Default) { createUploadThumbnail(cachedPath)?.path }
        }
    }.getOrNull()

    fun delete(file: CloudFile) {
        scope.launch {
            state.update { it.copy(status = "Menghapus ${file.name}…", error = null) }

            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(error = "Channel penyimpanan belum siap") }
                return@launch
            }

            runCatching { repository.deleteMessages(chatId, listOf(file.messageId)) }
                .onSuccess {
                    logDiagnostic(LOG_TAG, "hapus selesai: ${file.name}")
                    state.update { current ->
                        val remaining = current.files.filterNot { it.messageId == file.messageId }
                        current.copy(
                            files = remaining,
                            folders = remaining.mapNotNull { it.folder }.distinct().sorted(),
                            selectedFolder = current.selectedFolder?.takeIf { folder ->
                                remaining.any { it.folder == folder }
                            },
                            status = "${file.name} dihapus",
                        )
                    }
                }
                .onFailure { failure ->
                    state.update { it.copy(error = failure.message ?: "Gagal menghapus berkas") }
                }
        }
    }

    fun clearFinishedTransfers() {
        state.update { current ->
            current.copy(transfers = current.transfers.filter { it.status == TransferStatus.Running })
        }
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

    /** Forgets which photos were uploaded, so the next sync offers them again. */
    fun resetGallerySync() {
        preferences.clearGallerySyncedKeys()
        state.update { it.copy(gallerySyncedCount = 0) }
        syncGallery(auto = false)
    }

    /**
     * Uploads every new image from the chosen folders into the "Galeri" folder of
     * the storage channel, reading them in batches of [GALLERY_BATCH_SIZE] so a
     * large folder does not have to be copied to the cache at once. Runs once per
     * app session when auto sync is enabled, and on demand from Settings.
     *
     * @param perFolderLimit how many files one folder may contribute; the
     *   background worker passes a small number to stay inside its time window.
     */
    fun syncGallery(auto: Boolean = false, perFolderLimit: Int = Int.MAX_VALUE) {
        if (!gallerySource.supported) {
            if (!auto) reportError("Sinkronisasi galeri hanya tersedia di Android")
            return
        }
        if (state.value.gallerySyncing) return
        // The automatic entry point must respect the toggle; only the explicit
        // "Sinkronkan sekarang" action ignores it.
        if (auto && !preferences.galleryAutoSync()) return

        val folders = preferences.galleryFolders()
        if (folders.isEmpty()) {
            if (!auto) reportError("Pilih folder galeri terlebih dahulu")
            return
        }

        scope.launch {
            state.update { it.copy(gallerySyncing = true, error = null) }

            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update {
                    it.copy(gallerySyncing = false, error = "Channel penyimpanan belum siap")
                }
                return@launch
            }

            var uploaded = 0
            var failed = 0

            // Every selected folder contributes its own incremental batch, so a
            // second folder does not wait for the first one to be exhausted.
            // Photos are read batch by batch until the folder has no new file
            // left, instead of stopping after one fixed-size batch: a folder with
            // more files than the batch used to keep the rest waiting for the
            // next sync. Attempted keys are remembered so a file that keeps
            // failing cannot loop forever.
            val attempted = preferences.gallerySyncedKeys().toMutableSet()

            for (folder in folders) {
                var folderUploaded = 0
                var folderFailed = 0

                while (folderUploaded + folderFailed < perFolderLimit) {
                    val remaining = perFolderLimit - folderUploaded - folderFailed
                    val batch = runCatching {
                        gallerySource.pendingPhotos(
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

                    for (photo in batch) {
                        attempted += photo.key
                        val transferId = "transfer-${++transferCounter}"
                        state.update { current ->
                            current.copy(
                                transfers = listOf(
                                    TransferItem(
                                        id = transferId,
                                        fileName = photo.name,
                                        direction = TransferDirection.Upload,
                                        progress = 0f,
                                    ),
                                ) + current.transfers,
                            )
                        }

                        val result = uploadFile(transferId, chatId, photo.localPath, GALLERY_FOLDER)
                        gallerySource.release(listOf(photo))

                        result.fold(
                            onSuccess = {
                                folderUploaded++
                                preferences.addGallerySyncedKeys(listOf(photo.key))
                                logDiagnostic(LOG_TAG, "galeri tersinkron: ${photo.name}")
                            },
                            onFailure = { failure ->
                                folderFailed++
                                logDiagnostic(LOG_TAG, "galeri gagal: ${photo.name}: ${failure.message}")
                            },
                        )
                    }
                }

                uploaded += folderUploaded
                failed += folderFailed
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
                        uploaded > 0 -> "$uploaded foto galeri diunggah"
                        failed == 0 -> "Galeri sudah sinkron"
                        else -> null
                    },
                    error = if (failed > 0) "$failed foto gagal diunggah" else null,
                )
            }
        }
    }

    // --- Internals -------------------------------------------------------------

    /** Copies the download into Pictures/Unlimited Cloud when the user opted in. */
    private suspend fun saveToDeviceGallery(file: CloudFile, localPath: String): Boolean {
        if (!mediaSaver.supported || !preferences.autoSavePhotos()) return false
        val mimeType = imageMimeType(file.name) ?: return false
        return runCatching { mediaSaver.saveImage(localPath, file.name, mimeType) }
            .getOrDefault(false)
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
        // Images carry a small generated preview so the channel message (and the
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
        logDiagnostic(LOG_TAG, "transfer gagal: $message")
        updateTransfer(id) {
            it.copy(
                status = TransferStatus.Failed,
                detail = message,
            )
        }
        state.update { it.copy(status = null, error = message) }
    }

    /** Loads pages until the history ends, deduplicating the page boundary. */
    private suspend fun loadAllPages(chatId: Long): LoadedFiles {
        val files = mutableListOf<CloudFile>()
        val seen = mutableSetOf<Long>()
        var cursor = 0L
        var nextCursor: Long? = null
        var pages = 0

        while (pages < MAX_AUTO_PAGES) {
            val page = loadPage(chatId, cursor)
            page.files.forEach { file ->
                if (seen.add(file.messageId)) files += file
            }
            pages++

            val pageNext = page.nextFromMessageId
            if (pageNext == null || pageNext == cursor) {
                nextCursor = null
                break
            }
            nextCursor = pageNext
            cursor = pageNext
        }

        return LoadedFiles(files = files, nextFromMessageId = nextCursor)
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

    /** Resolves the channel, waiting out a TDLib session that is still starting. */
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
        const val MAX_AUTO_PAGES = 20
        const val GALLERY_FOLDER = "Galeri"
    }
}
