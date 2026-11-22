package com.unlimitedcloud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelManager
import com.unlimitedcloud.cloud.CloudChannelState
import com.unlimitedcloud.cloud.CloudFile
import com.unlimitedcloud.cloud.CloudFilesState
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
    val files by viewModel.files.collectAsState()
    val status by viewModel.status.collectAsState()
    val picker = rememberLocalFilePicker { path -> if (path != null) viewModel.upload(path) }

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

    status?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (val current = files) {
        CloudFilesState.Loading -> CircularProgressIndicator()
        is CloudFilesState.Failed -> Text(
            text = current.message,
            color = MaterialTheme.colorScheme.error,
        )

        is CloudFilesState.Loaded -> {
            if (current.files.isEmpty()) {
                Text("Belum ada berkas di channel ini.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(current.files, key = { it.messageId }) { file ->
                        FileRow(file = file, onDownload = { viewModel.download(file) })
                    }
                }
            }
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
                text = formatSize(file.size),
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
