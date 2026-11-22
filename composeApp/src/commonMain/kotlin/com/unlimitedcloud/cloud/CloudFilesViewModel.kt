package com.unlimitedcloud.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the file list and one transfer at a time.
 *
 * Kept as a plain class (not a platform ViewModel) so Android and Desktop share
 * the exact same behaviour; the lifecycle is a Koin factory per signed-in screen.
 */
class CloudFilesViewModel(
    private val repository: CloudFileRepository,
    private val channelManager: CloudChannelManager,
    private val scope: CoroutineScope,
) {
    private val filesState = MutableStateFlow<CloudFilesState>(CloudFilesState.Loading)
    val files: StateFlow<CloudFilesState> = filesState.asStateFlow()

    private val statusState = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = statusState.asStateFlow()

    fun refresh() {
        scope.launch {
            filesState.value = CloudFilesState.Loading
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                filesState.value = CloudFilesState.Failed("Channel penyimpanan belum siap")
                return@launch
            }

            filesState.value = runCatching { CloudFilesState.Loaded(repository.listFiles(chatId)) }
                .getOrElse { CloudFilesState.Failed(it.message ?: "Gagal memuat daftar berkas") }
        }
    }

    fun upload(localPath: String) {
        scope.launch {
            val chatId = channelManager.ensureRootChannel()
            if (chatId == null) {
                statusState.value = "Channel penyimpanan belum siap"
                return@launch
            }

            statusState.value = "Mengunggah ${localPath.substringAfterLast('/')}…"
            runCatching { repository.upload(chatId, localPath) }
                .onSuccess {
                    statusState.value = "Unggahan selesai"
                    reload(chatId)
                }
                .onFailure { statusState.value = it.message ?: "Unggahan gagal" }
        }
    }

    fun download(file: CloudFile) {
        scope.launch {
            statusState.value = "Mengunduh ${file.name}…"
            runCatching { repository.download(file.fileId) }
                .onSuccess { statusState.value = "Tersimpan di $it" }
                .onFailure { statusState.value = it.message ?: "Unduhan gagal" }
        }
    }

    private suspend fun reload(chatId: Long) {
        runCatching { CloudFilesState.Loaded(repository.listFiles(chatId)) }
            .onSuccess { filesState.value = it }
    }
}
