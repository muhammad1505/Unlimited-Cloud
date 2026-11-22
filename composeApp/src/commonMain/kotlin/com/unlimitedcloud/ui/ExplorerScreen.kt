package com.unlimitedcloud.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelManager
import com.unlimitedcloud.cloud.CloudChannelState
import com.unlimitedcloud.cloud.CloudFile
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.platform.rememberLocalFilePicker
import kotlin.math.roundToInt
import org.koin.compose.koinInject

/** Signed-in shell: account header, storage channel status and the explorer. */
@Composable
fun SignedInScreen(state: AuthUiState.Ready, engine: TelegramEngine) {
    val channelManager = koinInject<CloudChannelManager>()
    val channelState by channelManager.channelState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Unlimited Cloud", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "User ${state.userId} · ${channelLabel(channelState)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { engine.logout() }) { Text("Keluar") }
        }

        HorizontalDivider()
        ExplorerContent()
    }
}

@Composable
private fun ExplorerContent() {
    val viewModel = koinInject<CloudFilesViewModel>()
    val state by viewModel.uiState.collectAsState()
    var folderInput by remember { mutableStateOf("") }
    val picker = rememberLocalFilePicker { path ->
        if (path != null) {
            viewModel.upload(path, folderInput.trim().ifBlank { null })
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Berkas",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { viewModel.refresh() }) { Text("Muat ulang") }
        Button(onClick = { picker() }) { Text("Unggah") }
    }

    OutlinedTextField(
        value = folderInput,
        onValueChange = { folderInput = it },
        label = { Text("Folder tujuan (opsional)") },
        singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )

    FolderRow(
        folders = state.folders,
        selected = state.selectedFolder,
        onSelect = { folder ->
            viewModel.selectFolder(folder)
            folderInput = folder.orEmpty()
        },
    )

    state.status?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.error?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }

    when {
        state.loading -> CircularProgressIndicator()

        state.visibleFiles.isEmpty() -> Text(
            text = "Belum ada berkas di folder ini.",
            style = MaterialTheme.typography.bodyMedium,
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.visibleFiles, key = { it.messageId }) { file ->
                FileRow(file = file, onDownload = { viewModel.download(file) })
            }

            if (state.canLoadMore) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(
                            onClick = { viewModel.loadMore() },
                            enabled = !state.loadingMore,
                        ) {
                            Text(if (state.loadingMore) "Memuat…" else "Muat lebih banyak")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("Semua") },
        )
        folders.forEach { folder ->
            FilterChip(
                selected = selected == folder,
                onClick = { onSelect(folder) },
                label = { Text(folder) },
            )
        }
    }
}

@Composable
private fun FileRow(file: CloudFile, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(file.folder, formatSize(file.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDownload) { Text("Unduh") }
    }
}

private fun channelLabel(state: CloudChannelState): String = when (state) {
    CloudChannelState.Idle, CloudChannelState.Resolving -> "menyiapkan channel…"
    is CloudChannelState.Ready -> "channel ${state.chatId}"
    is CloudChannelState.Failed -> "channel gagal disiapkan"
}

private fun formatSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1000.0 && index < units.lastIndex) {
        value /= 1000.0
        index++
    }
    if (index == 0) return "$bytes B"
    val rounded = (value * 10).roundToInt() / 10.0
    return "$rounded ${units[index]}"
}
