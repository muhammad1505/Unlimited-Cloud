package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.log.platformTimestamp
import com.unlimitedcloud.platform.GalleryBackgroundSync
import com.unlimitedcloud.platform.GalleryFolder
import com.unlimitedcloud.platform.GallerySyncSource
import com.unlimitedcloud.platform.MediaSaver
import com.unlimitedcloud.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val galleryFolderName: String? = null,
    val gallerySyncing: Boolean = false,
    val galleryLastSync: String? = null,
    val gallerySyncedCount: Int = 0,
    val autoSaveSupported: Boolean = false,
    val autoSavePhotos: Boolean = false,
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
            galleryFolderName = preferences.galleryFolderName(),
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

            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(loading = false, error = "Channel penyimpanan belum siap") }
                return@launch
            }

            runCatching { repository.listFiles(chatId) }
                .onSuccess { page -> state.update { it.replaceWith(page) } }
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

            runCatching { repository.listFiles(chatId, fromMessageId = cursor) }
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
        logDiagnostic(LOG_TAG, "unggah mulai: $fileName → ${normalizedFolder ?: \"(root)\"}")

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
     * Downloads [file] and hands the local path to [onDownloaded]. Used by the
     * share action, which needs TDLib's local copy before it can build an
     * `ACTION_SEND` intent.
     */
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

    fun setGalleryFolder(folder: GalleryFolder) {
        preferences.setGalleryFolder(folder.id, folder.name)
        state.update { it.copy(galleryFolderName = folder.name) }
        refreshBackgroundSchedule()
        syncGallery(auto = true)
    }

    fun clearGalleryFolder() {
        preferences.setGalleryFolder(null, null)
        preferences.clearGallerySyncedKeys()
        state.update { it.copy(galleryFolderName = null, gallerySyncedCount = 0) }
        refreshBackgroundSchedule()
    }

    /** Forgets which photos were uploaded, so the next sync offers them again. */
    fun resetGallerySync() {
        preferences.clearGallerySyncedKeys()
        state.update { it.copy(gallerySyncedCount = 0) }
        syncGallery(auto = false)
    }

    /**
     * Uploads at most [GALLERY_BATCH_SIZE] new images from the chosen folder into
     * the "Galeri" folder of the storage channel. Runs once per app session when
     * auto sync is enabled, and on demand from Settings.
     */
    fun syncGallery(auto: Boolean = false, limit: Int = GALLERY_BATCH_SIZE) {
        if (!gallerySource.supported) {
            if (!auto) reportError("Sinkronisasi galeri hanya tersedia di Android")
            return
        }
        if (state.value.gallerySyncing) return
        // The automatic entry point must respect the toggle; only the explicit
        // "Sinkronkan sekarang" action ignores it.
        if (auto && !preferences.galleryAutoSync()) return

        val folderId = preferences.galleryFolderId()
        val folderName = state.value.galleryFolderName ?: preferences.galleryFolderName()
        if (folderId == null || folderName.isNullOrBlank()) {
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

            val folder = GalleryFolder(id = folderId, name = folderName)
            val photos = runCatching {
                gallerySource.pendingPhotos(
                    folder = folder,
                    alreadySynced = preferences.gallerySyncedKeys(),
                    limit = limit,
                )
            }.getOrElse { failure ->
                state.update {
                    it.copy(
                        gallerySyncing = false,
                        error = failure.message ?: "Gagal membaca folder galeri",
                    )
                }
                return@launch
            }

            var uploaded = 0
            var failed = 0

            photos.forEach { photo ->
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
                        uploaded++
                        preferences.addGallerySyncedKeys(listOf(photo.key))
                        logDiagnostic(LOG_TAG, "galeri tersinkron: ${photo.name}")
                    },
                    onFailure = { failure ->
                        failed++
                        logDiagnostic(LOG_TAG, "galeri gagal: ${photo.name}: ${failure.message}")
                    },
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
            !state.value.galleryFolderName.isNullOrBlank()
        if (active) backgroundSync.schedule() else backgroundSync.cancel()
    }

    private suspend fun uploadFile(
        transferId: String,
        chatId: Long,
        localPath: String,
        folder: String?,
    ): Result<Unit> = runCatching {
        repository.upload(chatId, localPath, folder) { progress ->
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

    private fun ExplorerUiState.replaceWith(page: CloudPage): ExplorerUiState {
        val folders = page.files.mapNotNull { it.folder }.distinct().sorted()
        return copy(
            files = page.files,
            folders = folders,
            selectedFolder = selectedFolder?.takeIf { it in folders },
            loading = false,
            loadingMore = false,
            canLoadMore = page.nextFromMessageId != null,
            nextFromMessageId = page.nextFromMessageId,
            error = null,
        )
    }

    private companion object {
        const val GALLERY_BATCH_SIZE = 20
        const val GALLERY_FOLDER = "Galeri"
    }
}
