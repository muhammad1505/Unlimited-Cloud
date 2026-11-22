package com.unlimitedcloud.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the explorer renders, including the currently selected folder. */
data class ExplorerUiState(
    val files: List<CloudFile> = emptyList(),
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val nextFromMessageId: Long? = null,
    val error: String? = null,
    val status: String? = null,
) {
    val visibleFiles: List<CloudFile>
        get() = selectedFolder?.let { folder -> files.filter { it.folder == folder } } ?: files
}

/**
 * Drives the file list, history pagination and one transfer at a time.
 *
 * Kept as a plain class (not a platform ViewModel) so Android and Desktop share
 * the exact same behaviour; the lifecycle is a Koin factory per signed-in screen.
 */
class CloudFilesViewModel(
    private val repository: CloudFileRepository,
    private val channelManager: CloudChannelManager,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = state.asStateFlow()

    fun refresh() {
        scope.launch {
            state.update { it.copy(loading = true, error = null) }

            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(loading = false, error = "Channel penyimpanan belum siap") }
                return@launch
            }

            runCatching { repository.listFiles(chatId) }
                .onSuccess { page -> state.update { it.withPage(page) } }
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
                        previous.withPage(page).copy(files = previous.files + fresh)
                    }
                }
                .onFailure { failure ->
                    state.update { it.copy(loadingMore = false, status = failure.message) }
                }
        }
    }

    fun selectFolder(folder: String?) {
        state.update { it.copy(selectedFolder = folder) }
    }

    fun upload(localPath: String, folder: String?) {
        scope.launch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                state.update { it.copy(status = "Channel penyimpanan belum siap") }
                return@launch
            }

            state.update { it.copy(status = "Mengunggah ${localPath.substringAfterLast('/')}…") }
            runCatching { repository.upload(chatId, localPath, folder) }
                .onSuccess {
                    state.update { it.copy(status = "Unggahan selesai") }
                    refresh()
                }
                .onFailure { failure ->
                    state.update { it.copy(status = failure.message ?: "Unggahan gagal") }
                }
        }
    }

    fun download(file: CloudFile) {
        scope.launch {
            state.update { it.copy(status = "Mengunduh ${file.name}…") }
            runCatching { repository.download(file.fileId) }
                .onSuccess { path -> state.update { it.copy(status = "Tersimpan di $path") } }
                .onFailure { failure ->
                    state.update { it.copy(status = failure.message ?: "Unduhan gagal") }
                }
        }
    }

    private fun ExplorerUiState.withPage(page: CloudPage): ExplorerUiState {
        val merged = files + page.files
        return copy(
            files = merged,
            folders = merged.mapNotNull { it.folder }.distinct().sorted(),
            loading = false,
            loadingMore = false,
            canLoadMore = page.nextFromMessageId != null,
            nextFromMessageId = page.nextFromMessageId,
            error = null,
        )
    }
}
