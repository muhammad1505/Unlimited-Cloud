package com.unlimitedcloud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.cloud.ExplorerUiState
import com.unlimitedcloud.cloud.TransferDirection
import com.unlimitedcloud.cloud.TransferItem
import com.unlimitedcloud.cloud.TransferStatus
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.EmptyState
import com.unlimitedcloud.ui.components.MetricChip
import com.unlimitedcloud.ui.util.formatProgress

@Composable
fun TransfersScreen(
    viewModel: CloudFilesViewModel,
    state: ExplorerUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricChip(
                icon = AppIcons.Transfer,
                value = state.activeTransfers.size.toString(),
                label = "aktif",
            )
            MetricChip(
                icon = AppIcons.Check,
                value = state.finishedTransfers.count { it.status == TransferStatus.Completed }.toString(),
                label = "selesai",
            )
            MetricChip(
                icon = AppIcons.Info,
                value = state.finishedTransfers.count { it.status == TransferStatus.Failed }.toString(),
                label = "gagal",
            )
            Spacer(Modifier.weight(1f))
            if (state.finishedTransfers.isNotEmpty()) {
                TextButton(onClick = viewModel::clearFinishedTransfers) {
                    Text("Bersihkan")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (state.transfers.isEmpty()) {
            EmptyState(
                icon = AppIcons.Transfer,
                title = "Belum ada transfer",
                message = "Unggahan dan unduhan akan muncul di sini lengkap dengan progresnya.",
            )
        } else {
            val listState = rememberLazyListState()
            // New transfers are prepended, so the top row is the one that just
            // started. Without this the list stayed where the user left it and
            // the newest progress was off-screen.
            val newestId = state.transfers.firstOrNull()?.id
            LaunchedEffect(newestId) {
                if (newestId != null) listState.animateScrollToItem(0)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.transfers, key = { it.id }) { transfer ->
                    TransferCard(
                        transfer = transfer,
                        onRetry = { viewModel.retryTransfer(transfer) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    transfer: TransferItem,
    onRetry: () -> Unit,
) {
    val accent = when (transfer.direction) {
        TransferDirection.Upload -> MaterialTheme.colorScheme.primary
        TransferDirection.Download -> MaterialTheme.colorScheme.secondary
    }
    val directionLabel = when (transfer.direction) {
        TransferDirection.Upload -> "Unggahan"
        TransferDirection.Download -> "Unduhan"
    }
    val statusLabel = when (transfer.status) {
        TransferStatus.Running -> when (transfer.direction) {
            TransferDirection.Upload -> "Mengunggah ke Telegram"
            TransferDirection.Download -> "Mengunduh dari Telegram"
        }

        TransferStatus.Completed -> "Selesai"
        TransferStatus.Failed -> "Gagal"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (transfer.direction == TransferDirection.Upload) {
                        AppIcons.Upload
                    } else {
                        AppIcons.Download
                    },
                    contentDescription = directionLabel,
                    tint = accent,
                    modifier = Modifier.size(21.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$directionLabel · $statusLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (transfer.status == TransferStatus.Running) {
                    Spacer(Modifier.height(7.dp))
                    if (transfer.progress != null) {
                        LinearProgressIndicator(
                            progress = { transfer.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                            color = accent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                            color = accent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = formatProgress(transfer.progress),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                } else if (!transfer.detail.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = transfer.detail.substringAfterLast('/').substringAfterLast('\\'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
            when (transfer.status) {
                TransferStatus.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = accent,
                )

                TransferStatus.Completed -> Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981).copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = "Selesai",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(17.dp),
                    )
                }

                TransferStatus.Failed -> Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Info,
                        contentDescription = "Gagal",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            // Finished rows are cleared together by "Bersihkan" above; a second
            // per-row delete action only repeated it in a smaller size.
            if (transfer.status == TransferStatus.Failed && transfer.file != null) {
                TextButton(onClick = onRetry) {
                    Text("Coba lagi", style = MaterialTheme.typography.labelSmall)
                }
            }
            }
        }
    }
}
