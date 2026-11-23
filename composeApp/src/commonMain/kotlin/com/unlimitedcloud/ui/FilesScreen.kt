package com.unlimitedcloud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unlimitedcloud.cloud.CloudFile
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.cloud.ExplorerUiState
import com.unlimitedcloud.cloud.FileSort
import com.unlimitedcloud.cloud.TransferItem
import com.unlimitedcloud.cloud.TransferStatus
import com.unlimitedcloud.platform.ShareRequest
import com.unlimitedcloud.platform.ShareResult
import com.unlimitedcloud.platform.rememberFileSharer
import com.unlimitedcloud.platform.rememberLocalFilePicker
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.AppTextField
import com.unlimitedcloud.ui.components.ConfirmDialog
import com.unlimitedcloud.ui.components.EmptyState
import com.unlimitedcloud.ui.components.FileKindIcon
import com.unlimitedcloud.ui.components.GradientButton
import com.unlimitedcloud.ui.components.IconPillButton
import com.unlimitedcloud.ui.components.InfoBanner
import com.unlimitedcloud.ui.components.MetricChip
import com.unlimitedcloud.ui.components.ShimmerBlock
import com.unlimitedcloud.ui.components.cloudFileKind
import com.unlimitedcloud.ui.components.mimeTypeFor
import com.unlimitedcloud.ui.util.formatBytes
import com.unlimitedcloud.ui.util.formatProgress
import kotlinx.coroutines.delay

@Composable
fun FilesScreen(
    viewModel: CloudFilesViewModel,
    state: ExplorerUiState,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    var showUploadDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CloudFile?>(null) }
    var uploadFolder by remember { mutableStateOf<String?>(null) }

    val picker = rememberLocalFilePicker { path ->
        if (path != null) {
            viewModel.upload(path, uploadFolder)
        }
    }

    val share = rememberFileSharer { result ->
        when (result) {
            ShareResult.Shared -> viewModel.reportStatus("Berkas siap dibagikan")
            ShareResult.Cancelled -> Unit
            is ShareResult.Failed -> viewModel.reportError("Gagal membagikan: ${result.message}")
        }
    }

    // Sharing needs TDLib's local copy, so the download runs first and the
    // chooser opens as soon as the file is available.
    val shareFile: (CloudFile) -> Unit = { file ->
        viewModel.reportStatus("Menyiapkan ${file.name} untuk dibagikan…")
        viewModel.download(file) { path ->
            share(
                ShareRequest(
                    localPath = path,
                    fileName = file.name,
                    mimeType = mimeTypeFor(file.name),
                ),
            )
        }
    }

    LaunchedEffect(state.status) {
        if (state.status != null) {
            delay(4000)
            viewModel.dismissStatus()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            SummaryStrip(state = state)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = "Cari berkas",
                    modifier = Modifier.weight(1f),
                    leadingIcon = {
                        Icon(
                            imageVector = AppIcons.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(
                                    imageVector = AppIcons.Close,
                                    contentDescription = "Hapus pencarian",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        null
                    },
                )

                IconPillButton(
                    icon = AppIcons.Refresh,
                    contentDescription = "Muat ulang",
                    onClick = viewModel::refresh,
                    enabled = !state.loading,
                )

                if (expanded) {
                    Box {
                        IconPillButton(
                            icon = AppIcons.Tune,
                            contentDescription = "Urutkan berkas",
                            onClick = { showSortMenu = true },
                        )
                        SortMenu(
                            expanded = showSortMenu,
                            current = state.sort,
                            onDismiss = { showSortMenu = false },
                            onSelect = { sort ->
                                showSortMenu = false
                                viewModel.setSort(sort)
                            },
                        )
                    }

                    GradientButton(
                        text = "Unggah",
                        onClick = { showUploadDialog = true },
                        icon = AppIcons.Upload,
                    )
                }
            }

            // On compact screens the sort control gets its own row so the search
            // field and the round action buttons never squeeze each other out.
            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        CompactSortButton(
                            label = state.sort.label,
                            onClick = { showSortMenu = true },
                        )
                        SortMenu(
                            expanded = showSortMenu,
                            current = state.sort,
                            onDismiss = { showSortMenu = false },
                            onSelect = { sort ->
                                showSortMenu = false
                                viewModel.setSort(sort)
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(10.dp))
            FolderChips(
                state = state,
                onSelect = viewModel::selectFolder,
            )

            if (state.status != null) {
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    message = state.status.orEmpty(),
                    onDismiss = viewModel::dismissStatus,
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    message = state.error.orEmpty(),
                    isError = true,
                    onDismiss = viewModel::clearError,
                )
            }

            if (state.loading && state.files.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))

            when {
                state.loading && state.files.isEmpty() -> SkeletonList()

                state.visibleFiles.isEmpty() -> {
                    if (state.files.isEmpty()) {
                        EmptyState(
                            icon = AppIcons.Cloud,
                            title = "Belum ada berkas",
                            message = "Unggah berkas pertama Anda. Semua file disimpan di channel pribadi Telegram.",
                            actionLabel = "Unggah berkas",
                            onAction = { showUploadDialog = true },
                        )
                    } else {
                        EmptyState(
                            icon = AppIcons.Search,
                            title = "Tidak ada hasil",
                            message = "Coba kata kunci lain atau kembali ke folder Semua.",
                            actionLabel = "Hapus filter",
                            actionIcon = AppIcons.Close,
                            onAction = {
                                viewModel.setQuery("")
                                viewModel.selectFolder(null)
                            },
                        )
                    }
                }

                expanded -> FileGrid(
                    files = state.visibleFiles,
                    transfers = state.transfers,
                    loadingMore = state.loadingMore,
                    canLoadMore = state.canLoadMore,
                    onLoadMore = viewModel::loadMore,
                    onDownload = { viewModel.download(it) },
                    onDelete = { deleteTarget = it },
                    onShare = shareFile,
                )

                else -> FileList(
                    files = state.visibleFiles,
                    transfers = state.transfers,
                    loadingMore = state.loadingMore,
                    canLoadMore = state.canLoadMore,
                    onLoadMore = viewModel::loadMore,
                    onDownload = { viewModel.download(it) },
                    onDelete = { deleteTarget = it },
                    onShare = shareFile,
                )
            }
        }

        if (!expanded) {
            FloatingActionButton(
                onClick = { showUploadDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Upload,
                    contentDescription = "Unggah berkas",
                )
            }
        }
    }

    if (showUploadDialog) {
        UploadDialog(
            folders = state.folders,
            initialFolder = uploadFolder,
            onDismiss = { showUploadDialog = false },
            onConfirm = { folder ->
                uploadFolder = folder
                showUploadDialog = false
                picker()
            },
        )
    }

    deleteTarget?.let { file ->
        ConfirmDialog(
            title = "Hapus berkas?",
            message = "\"${file.name}\" akan dihapus dari channel Telegram. Tindakan ini tidak bisa dibatalkan.",
            confirmLabel = "Hapus",
            onConfirm = {
                viewModel.delete(file)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }
}

@Composable
private fun SummaryStrip(state: ExplorerUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricChip(
            icon = AppIcons.Folder,
            value = state.files.size.toString(),
            label = "berkas",
        )
        MetricChip(
            icon = AppIcons.Storage,
            value = formatBytes(state.totalBytes),
            label = "terindeks",
        )
        MetricChip(
            icon = AppIcons.Folder,
            value = state.folders.size.toString(),
            label = "folder",
        )
        if (state.activeTransfers.isNotEmpty()) {
            MetricChip(
                icon = AppIcons.Transfer,
                value = state.activeTransfers.size.toString(),
                label = "transfer aktif",
            )
        }
    }
}

@Composable
private fun FolderChips(
    state: ExplorerUiState,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FolderChip(
            label = "Semua",
            count = state.files.size,
            selected = state.selectedFolder == null,
            onClick = { onSelect(null) },
        )
        state.folders.forEach { folder ->
            FolderChip(
                label = folder,
                count = state.folderCount(folder),
                selected = state.selectedFolder == folder,
                onClick = { onSelect(folder) },
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label ($count)") },
        leadingIcon = {
            Icon(
                imageVector = AppIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun FileList(
    files: List<CloudFile>,
    transfers: List<TransferItem>,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onDownload: (CloudFile) -> Unit,
    onDelete: (CloudFile) -> Unit,
    onShare: (CloudFile) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(files, key = { it.messageId }) { file ->
            FileCard(
                file = file,
                transfer = transfers.firstOrNull { it.fileId == file.fileId && it.status == TransferStatus.Running },
                expanded = false,
                onDownload = { onDownload(file) },
                onDelete = { onDelete(file) },
                onShare = { onShare(file) },
            )
        }

        if (canLoadMore) {
            item {
                LoadMoreRow(
                    loadingMore = loadingMore,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun FileGrid(
    files: List<CloudFile>,
    transfers: List<TransferItem>,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onDownload: (CloudFile) -> Unit,
    onDelete: (CloudFile) -> Unit,
    onShare: (CloudFile) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        gridItems(files, key = { it.messageId }) { file ->
            FileCard(
                file = file,
                transfer = transfers.firstOrNull { it.fileId == file.fileId && it.status == TransferStatus.Running },
                expanded = true,
                onDownload = { onDownload(file) },
                onDelete = { onDelete(file) },
                onShare = { onShare(file) },
            )
        }

        if (canLoadMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreRow(
                    loadingMore = loadingMore,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun FileCard(
    file: CloudFile,
    transfer: TransferItem?,
    expanded: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val kind = cloudFileKind(file.name)
    val meta = listOfNotNull(
        file.folder?.let { "Folder $it" },
        formatBytes(file.size),
    ).joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        if (expanded) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileKindIcon(kind = kind, size = 48.dp)
                    Spacer(Modifier.weight(1f))
                    if (transfer != null) {
                        Text(
                            text = formatProgress(transfer.progress),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        FileActionsMenu(
                            onDownload = onDownload,
                            onDelete = onDelete,
                            onShare = onShare,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (transfer != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { transfer.progress ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FileKindIcon(kind = kind)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (transfer != null) {
                        Spacer(Modifier.height(7.dp))
                        LinearProgressIndicator(
                            progress = { transfer.progress ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }

                if (transfer != null) {
                    Text(
                        text = formatProgress(transfer.progress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    FileActionsMenu(
                        onDownload = onDownload,
                        onDelete = onDelete,
                        onShare = onShare,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileActionsMenu(
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = AppIcons.MoreVert,
                contentDescription = "Aksi berkas",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Bagikan") },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text("Unduh") },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    expanded = false
                    onDownload()
                },
            )
            DropdownMenuItem(
                text = { Text("Hapus") },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun LoadMoreRow(
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loadingMore) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        TextButton(
            onClick = onLoadMore,
            enabled = !loadingMore,
        ) {
            Text(if (loadingMore) "Memuat…" else "Muat lebih banyak")
        }
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    current: FileSort,
    onDismiss: () -> Unit,
    onSelect: (FileSort) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        FileSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text("Urutkan: ${sort.label}") },
                leadingIcon = {
                    Icon(
                        imageVector = if (current == sort) AppIcons.Check else AppIcons.Tune,
                        contentDescription = null,
                        tint = if (current == sort) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                onClick = { onSelect(sort) },
            )
        }
    }
}

@Composable
private fun CompactSortButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = AppIcons.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Urutkan: $label",
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(
                imageVector = AppIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SkeletonList() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(5) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShimmerBlock(modifier = Modifier.size(44.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShimmerBlock(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp))
                    ShimmerBlock(modifier = Modifier.fillMaxWidth(0.35f).height(10.dp))
                }
                ShimmerBlock(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun UploadDialog(
    folders: List<String>,
    initialFolder: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var folder by remember { mutableStateOf(initialFolder) }
    var customFolder by remember { mutableStateOf("") }
    val chosenFolder = customFolder.trim().takeIf { it.isNotEmpty() } ?: folder

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(20.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AppIcons.Upload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unggah berkas", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Pilih folder tujuan, lalu pilih berkas dari perangkat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text("Folder tujuan", style = MaterialTheme.typography.labelLarge)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = customFolder.isBlank() && folder == null,
                        onClick = {
                            folder = null
                            customFolder = ""
                        },
                        label = { Text("Tanpa folder") },
                        shape = RoundedCornerShape(12.dp),
                    )
                    folders.forEach { existing ->
                        FilterChip(
                            selected = customFolder.isBlank() && folder == existing,
                            onClick = {
                                folder = existing
                                customFolder = ""
                            },
                            label = { Text(existing) },
                            leadingIcon = {
                                Icon(
                                    imageVector = AppIcons.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }

                AppTextField(
                    value = customFolder,
                    onValueChange = { customFolder = it },
                    label = "Atau buat folder baru",
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = AppIcons.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal")
                    }
                    Spacer(Modifier.width(8.dp))
                    GradientButton(
                        text = "Pilih berkas",
                        onClick = { onConfirm(chosenFolder) },
                        icon = AppIcons.Upload,
                    )
                }
            }
        }
    }
}
