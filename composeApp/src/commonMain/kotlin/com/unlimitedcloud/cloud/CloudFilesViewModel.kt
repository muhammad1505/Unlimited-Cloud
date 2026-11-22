package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
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
 * Drives the file list, history pagination, search and one transfer at a time.
 *
 * Kept as a plain class (not a platform ViewModel) so Android and Desktop share
 * the exact same behaviour; Koin keeps a single instance for the signed-in shell.
 */
class CloudFilesViewModel(
    private val repository: CloudFileRepository,
    private val channelManager: CloudChannelManager,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = state.asStateFlow()

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

            runCatching {
                repository.upload(chatId, localPath, normalizedFolder) { progress ->
                    updateTransfer(id) { it.copy(progress = progress) }
                }
            }
                .onSuccess {
                    updateTransfer(id) {
                        it.copy(
                            status = TransferStatus.Completed,
                            progress = 1f,
                            detail = "Tersimpan di Telegram",
                        )
                    }
                    logDiagnostic(LOG_TAG, "unggah selesai: $fileName")
                    state.update { it.copy(status = "Unggahan $fileName selesai") }
                    refresh()
                }
                .onFailure { failure ->
                    failTransfer(id, failure.message ?: "Unggahan gagal")
                }
        }
    }

    fun download(file: CloudFile) {
        val alreadyRunning = state.value.transfers.any {
            it.fileId == file.fileId && it.status == TransferStatus.Running
        }
        if (alreadyRunning) return
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
                    state.update {
                        it.copy(status = "Tersimpan: ${path.substringAfterLast('/').substringAfterLast('\\')}")
                    }
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
}
