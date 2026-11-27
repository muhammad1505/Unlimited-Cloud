package com.unlimitedcloud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.unlimitedcloud.platform.PlatformBackHandler
import com.unlimitedcloud.platform.ShareRequest
import com.unlimitedcloud.platform.ShareResult
import com.unlimitedcloud.platform.rememberFileSharer
import com.unlimitedcloud.platform.rememberLocalFilePicker
import com.unlimitedcloud.platform.rememberLocalFolderPicker
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.AppTextField
import com.unlimitedcloud.ui.components.ConfirmDialog
import com.unlimitedcloud.ui.components.EmptyState
import com.unlimitedcloud.ui.components.FileCover
import com.unlimitedcloud.ui.components.GradientButton
import com.unlimitedcloud.ui.components.IconPillButton
import com.unlimitedcloud.ui.components.MetricChip
import com.unlimitedcloud.ui.components.ShimmerBlock
import com.unlimitedcloud.ui.components.mimeTypeFor
import com.unlimitedcloud.ui.util.formatBytes
import com.unlimitedcloud.ui.util.formatProgress

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
    var previewFiles by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(-1) }
    var uploadFolder by remember { mutableStateOf<String?>(null) }

    // The Berkas tab opens on the folder tiles; a folder or a search opens the
    // file list. Kept in saveable state so rotation does not jump back out.
    var browsingFolders by rememberSaveable { mutableStateOf(true) }
    // Hoisted out of FolderBrowser: the tree leaves the composition while a
    // folder is open, and its expanded branches used to collapse on every back
    // press, which dropped the user at the root again.
    var expandedFolderPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val showingFolders = browsingFolders && state.query.isBlank()
    // Back walks one level up the folder path before it returns to the tree.
    val goBackOneLevel: () -> Unit = {
        val current = state.selectedFolder
        if (current != null && current.contains('/')) {
            viewModel.selectFolder(current.substringBeforeLast('/'))
        } else {
            browsingFolders = true
        }
    }

    // Multi-select lives in the screen: the selection is a UI concern and must
    // not survive a refresh that replaces the file list.
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteSelected by remember { mutableStateOf(false) }

    // Cards only need the transfer that is running for them; looking it up once
    // here keeps every card from scanning the whole list on each recomposition.
    val runningTransfers: Map<Int?, TransferItem> = remember(state.transfers) {
        state.transfers
            .filter { it.status == TransferStatus.Running }
            .associateBy { it.fileId }
    }

    val selectedFiles = state.visibleFiles.filter { it.messageId in selectedIds }
    val exitSelection: () -> Unit = {
        selecting = false
        selectedIds = emptySet()
    }
    val toggleSelection: (CloudFile) -> Unit = { file ->
        selectedIds = if (file.messageId in selectedIds) {
            selectedIds - file.messageId
        } else {
            selectedIds + file.messageId
        }
    }
    val startSelection: (CloudFile) -> Unit = { file ->
        selecting = true
        selectedIds = selectedIds + file.messageId
    }

    val picker = rememberLocalFilePicker { paths ->
        if (paths.isNotEmpty()) {
            viewModel.uploadMany(paths, uploadFolder)
        }
    }

    val folderPicker = rememberLocalFolderPicker { selection ->
        if (selection != null) {
            viewModel.uploadFolder(selection, uploadFolder)
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

    // The dialog navigates through the list the user is looking at, so it keeps
    // the snapshot taken when a file was opened instead of the live list.
    val openPreview: (CloudFile) -> Unit = { file ->
        previewFiles = state.visibleFiles
        previewIndex = previewFiles.indexOfFirst { it.messageId == file.messageId }
            .coerceAtLeast(0)
    }

    // Back leaves selection mode, then the open folder, instead of closing the
    // signed-in shell.
    PlatformBackHandler(enabled = selecting || !showingFolders) {
        when {
            selecting -> exitSelection()
            state.query.isNotBlank() -> viewModel.setQuery("")
            else -> goBackOneLevel()
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
                onSelect = { folder ->
                    browsingFolders = false
                    viewModel.selectFolder(folder)
                },
            )

            if (!showingFolders) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (state.query.isNotBlank()) viewModel.setQuery("") else goBackOneLevel()
                        },
                    ) {
                        Icon(
                            imageVector = AppIcons.ChevronLeft,
                            contentDescription = "Kembali ke folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = state.selectedFolder ?: "Semua berkas",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${state.visibleFiles.size} berkas",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (selecting) {
                Spacer(Modifier.height(8.dp))
                SelectionBar(
                    count = selectedFiles.size,
                    allSelected = state.visibleFiles.isNotEmpty() &&
                        selectedFiles.size == state.visibleFiles.size,
                    onToggleAll = {
                        selectedIds = if (selectedFiles.size == state.visibleFiles.size) {
                            emptySet()
                        } else {
                            state.visibleFiles.mapTo(mutableSetOf()) { it.messageId }
                        }
                    },
                    onDownload = {
                        if (selectedFiles.isNotEmpty()) {
                            viewModel.downloadMany(selectedFiles)
                        }
                        exitSelection()
                    },
                    onDelete = {
                        if (selectedFiles.isNotEmpty()) showDeleteSelected = true
                    },
                    onCancel = exitSelection,
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

                showingFolders -> FolderBrowser(
                    state = state,
                    expandedPaths = expandedFolderPaths,
                    onToggleExpanded = { path ->
                        expandedFolderPaths = if (path in expandedFolderPaths) {
                            expandedFolderPaths - path
                        } else {
                            expandedFolderPaths + path
                        }
                    },
                    onOpenFolder = { folder ->
                        browsingFolders = false
                        viewModel.selectFolder(folder)
                    },
                )

                state.visibleFiles.isEmpty() -> {
                    if (state.files.isEmpty()) {
                        EmptyState(
                            icon = AppIcons.Cloud,
                            title = "Belum ada berkas",
                            message = "Unggah berkas pertama Anda. Semua file disimpan di grup topik pribadi Telegram, dan setiap folder menjadi satu topik.",
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
                    transfers = runningTransfers,
                    loadingMore = state.loadingMore,
                    canLoadMore = state.canLoadMore,
                    onLoadMore = viewModel::loadMore,
                    onDownload = { viewModel.download(it) },
                    onDelete = { deleteTarget = it },
                    onShare = shareFile,
                    onPreview = openPreview,
                    selecting = selecting,
                    selectedIds = selectedIds,
                    onToggle = toggleSelection,
                    onSelectMode = startSelection,
                    loadCover = { file -> viewModel.cover(file) },
                    coverRevision = state.coverRevision,
                )

                else -> FileList(
                    files = state.visibleFiles,
                    transfers = runningTransfers,
                    loadingMore = state.loadingMore,
                    canLoadMore = state.canLoadMore,
                    onLoadMore = viewModel::loadMore,
                    onDownload = { viewModel.download(it) },
                    onDelete = { deleteTarget = it },
                    onShare = shareFile,
                    onPreview = openPreview,
                    selecting = selecting,
                    selectedIds = selectedIds,
                    onToggle = toggleSelection,
                    onSelectMode = startSelection,
                    loadCover = { file -> viewModel.cover(file) },
                    coverRevision = state.coverRevision,
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
            autoTopics = state.autoTopics,
            onDismiss = { showUploadDialog = false },
            onConfirm = { folder ->
                uploadFolder = folder
                showUploadDialog = false
                picker()
            },
            onPickFolder = { folder ->
                uploadFolder = folder
                showUploadDialog = false
                folderPicker()
            },
        )
    }

    if (showDeleteSelected) {
        val count = selectedFiles.size
        ConfirmDialog(
            title = "Hapus $count berkas?",
            message = "$count berkas terpilih akan dihapus dari grup Telegram. " +
                "Tindakan ini tidak bisa dibatalkan.",
            confirmLabel = "Hapus",
            onConfirm = {
                viewModel.deleteMany(selectedFiles)
                showDeleteSelected = false
                exitSelection()
            },
            onDismiss = { showDeleteSelected = false },
            destructive = true,
        )
    }

    deleteTarget?.let { file ->
        ConfirmDialog(
            title = "Hapus berkas?",
            message = "\"${file.name}\" akan dihapus dari grup Telegram. Tindakan ini tidak bisa dibatalkan.",
            confirmLabel = "Hapus",
            onConfirm = {
                viewModel.delete(file)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }

    if (previewIndex >= 0 && previewFiles.isNotEmpty()) {
        FilePreviewDialog(
            files = previewFiles,
            index = previewIndex.coerceIn(0, previewFiles.lastIndex),
            onIndexChange = { previewIndex = it },
            viewModel = viewModel,
            onDismiss = { previewIndex = -1 },
            onDownload = { file -> viewModel.download(file) },
            onShare = shareFile,
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
        // Only the top of each branch gets a chip; nested folders live in the
        // tree and their branch chip already covers them.
        val branches = state.folders
            .map { it.substringBefore('/') }
            .distinct()
            .sorted()
        branches.forEach { folder ->
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

/**
 * The Berkas tab landing view. Folder paths such as "Liburan/Pantai" form a
 * tree: tapping a branch folds it open or shut, and the arrow on the right opens
 * the folder with every file beneath it.
 */
@Composable
private fun FolderBrowser(
    state: ExplorerUiState,
    expandedPaths: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onOpenFolder: (String?) -> Unit,
) {
    // A folder only exists as the caption of its messages, so the tree is built
    // from the loaded files. Uploads are always filed by type or by the folder
    // the user picked, so the "Tanpa folder" row is only a fallback for messages
    // that somehow arrive without a readable file name.
    val tree = remember(state.files) { buildFolderTree(state.files) }
    val looseFiles = remember(state.files) { state.files.filter { it.folder == null } }
    val rows = remember(tree, expandedPaths) { flattenFolders(tree, expandedPaths) }

    if (tree.isEmpty() && looseFiles.isEmpty()) {
        EmptyState(
            icon = AppIcons.Cloud,
            title = "Belum ada berkas",
            message = "Folder akan muncul di sini setelah berkas pertama " +
                "diunggah. Setiap folder menjadi satu topik di grup Telegram Anda.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (looseFiles.isNotEmpty()) {
            item(key = "\u0000root") {
                FolderTreeRow(
                    name = "Tanpa folder",
                    fileCount = looseFiles.size,
                    bytes = looseFiles.sumOf { it.size },
                    subfolderCount = 0,
                    depth = 0,
                    expandable = false,
                    expanded = false,
                    onToggle = {},
                    onOpen = { onOpenFolder(null) },
                )
            }
        }

        items(rows, key = { it.node.path }) { row ->
            FolderTreeRow(
                name = row.node.name,
                fileCount = row.node.totalFiles,
                bytes = row.node.totalBytes,
                subfolderCount = row.node.children.size,
                depth = row.depth,
                expandable = row.node.children.isNotEmpty(),
                expanded = row.node.path in expandedPaths,
                onToggle = { onToggleExpanded(row.node.path) },
                onOpen = { onOpenFolder(row.node.path) },
            )
        }
    }
}

/** One folder of the path tree; [path] is the full caption ("Liburan/Pantai"). */
private data class FolderNode(
    val name: String,
    val path: String,
    val fileCount: Int,
    val bytes: Long,
    val children: List<FolderNode>,
) {
    val totalFiles: Int = fileCount + children.sumOf { it.totalFiles }
    val totalBytes: Long = bytes + children.sumOf { it.totalBytes }
}

private data class FolderRow(val node: FolderNode, val depth: Int)

/** Intermediate nodes are synthesized so a folder holding only subfolders shows up too. */
private fun buildFolderTree(files: List<CloudFile>): List<FolderNode> {
    val root = FolderTreeBuilder(name = "", path = "")

    fun builderFor(path: String): FolderTreeBuilder {
        var node = root
        path.split('/')
            .filter { it.isNotBlank() }
            .forEach { segment -> node = node.child(segment.trim()) }
        return node
    }

    files.forEach { file ->
        val folder = file.folder ?: return@forEach
        val node = builderFor(folder)
        node.fileCount += 1
        node.bytes += file.size
    }

    return root.sortedChildren()
}

private class FolderTreeBuilder(val name: String, val path: String) {
    var fileCount: Int = 0
    var bytes: Long = 0
    private val children = mutableMapOf<String, FolderTreeBuilder>()

    fun child(segment: String): FolderTreeBuilder = children.getOrPut(segment) {
        FolderTreeBuilder(
            name = segment,
            path = if (path.isEmpty()) segment else "$path/$segment",
        )
    }

    fun sortedChildren(): List<FolderNode> = children.values
        .sortedBy { it.name.lowercase() }
        .map { child ->
            FolderNode(
                name = child.name,
                path = child.path,
                fileCount = child.fileCount,
                bytes = child.bytes,
                children = child.sortedChildren(),
            )
        }
}

/** Visible rows: a folded node hides its whole subtree. */
private fun flattenFolders(
    nodes: List<FolderNode>,
    expandedPaths: Set<String>,
    depth: Int = 0,
): List<FolderRow> = buildList {
    nodes.forEach { node ->
        add(FolderRow(node, depth))
        if (node.children.isNotEmpty() && node.path in expandedPaths) {
            addAll(flattenFolders(node.children, expandedPaths, depth + 1))
        }
    }
}

@Composable
private fun FolderTreeRow(
    name: String,
    fileCount: Int,
    bytes: Long,
    subfolderCount: Int,
    depth: Int,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = if (expandable) onToggle else onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 18).dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (expandable) {
                    Icon(
                        imageVector = AppIcons.ChevronDown,
                        contentDescription = if (expanded) "Lipat $name" else "Bentangkan $name",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (expanded) 0f else -90f),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(fileCount)
                        append(" berkas · ")
                        append(formatBytes(bytes))
                        if (subfolderCount > 0) {
                            append(" · ")
                            append(subfolderCount)
                            append(" subfolder")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpen, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = "Buka $name",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FileList(
    files: List<CloudFile>,
    transfers: Map<Int?, TransferItem>,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onDownload: (CloudFile) -> Unit,
    onDelete: (CloudFile) -> Unit,
    onShare: (CloudFile) -> Unit,
    onPreview: (CloudFile) -> Unit,
    selecting: Boolean,
    selectedIds: Set<Long>,
    onToggle: (CloudFile) -> Unit,
    onSelectMode: (CloudFile) -> Unit,
    loadCover: suspend (CloudFile) -> String?,
    coverRevision: Int,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(files, key = { it.messageId }) { file ->
            FileCard(
                file = file,
                transfer = transfers[file.fileId],
                expanded = false,
                selecting = selecting,
                selected = file.messageId in selectedIds,
                onOpen = { if (selecting) onToggle(file) else onPreview(file) },
                onLongPress = { if (selecting) onToggle(file) else onSelectMode(file) },
                onSelectMode = { onSelectMode(file) },
                onDownload = { onDownload(file) },
                onDelete = { onDelete(file) },
                onShare = { onShare(file) },
                loadCover = loadCover,
                coverRevision = coverRevision,
            )
        }

        if (canLoadMore) {
            item {
                // The row only composes near the end of the list, so reaching the
                // bottom pages further without a tap. Keyed on the item count so a
                // failed page does not retry in a loop.
                LaunchedEffect(files.size, canLoadMore) {
                    if (!loadingMore) onLoadMore()
                }
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
    transfers: Map<Int?, TransferItem>,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onDownload: (CloudFile) -> Unit,
    onDelete: (CloudFile) -> Unit,
    onShare: (CloudFile) -> Unit,
    onPreview: (CloudFile) -> Unit,
    selecting: Boolean,
    selectedIds: Set<Long>,
    onToggle: (CloudFile) -> Unit,
    onSelectMode: (CloudFile) -> Unit,
    loadCover: suspend (CloudFile) -> String?,
    coverRevision: Int,
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
                transfer = transfers[file.fileId],
                expanded = true,
                selecting = selecting,
                selected = file.messageId in selectedIds,
                onOpen = { if (selecting) onToggle(file) else onPreview(file) },
                onLongPress = { if (selecting) onToggle(file) else onSelectMode(file) },
                onSelectMode = { onSelectMode(file) },
                onDownload = { onDownload(file) },
                onDelete = { onDelete(file) },
                onShare = { onShare(file) },
                loadCover = loadCover,
                coverRevision = coverRevision,
            )
        }

        if (canLoadMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(files.size, canLoadMore) {
                    if (!loadingMore) onLoadMore()
                }
                LoadMoreRow(
                    loadingMore = loadingMore,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file: CloudFile,
    transfer: TransferItem?,
    expanded: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onSelectMode: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    loadCover: suspend (CloudFile) -> String?,
    coverRevision: Int,
) {
    val meta = listOfNotNull(
        file.folder?.let { "Folder $it" },
        formatBytes(file.size),
    ).joinToString(" · ")

    Surface(
        // Tapping the card itself is the preview: routing it through the overflow
        // menu made opening a file two taps plus a menu read.
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
    ) {
        if (expanded) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileCover(
                        file = file,
                        loadCover = loadCover,
                        size = 48.dp,
                        revision = coverRevision,
                    )
                    Spacer(Modifier.weight(1f))
                    CardTrailing(
                        transfer = transfer,
                        selecting = selecting,
                        selected = selected,
                        compact = false,
                        onDownload = onDownload,
                        onDelete = onDelete,
                        onShare = onShare,
                        onSelectMode = onSelectMode,
                    )
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
                FileCover(file = file, loadCover = loadCover, revision = coverRevision)

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

                CardTrailing(
                    transfer = transfer,
                    selecting = selecting,
                    selected = selected,
                    compact = true,
                    onDownload = onDownload,
                    onDelete = onDelete,
                    onShare = onShare,
                    onSelectMode = onSelectMode,
                )
            }
        }
    }
}

@Composable
private fun CardTrailing(
    transfer: TransferItem?,
    selecting: Boolean,
    selected: Boolean,
    compact: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onSelectMode: () -> Unit,
) {
    when {
        selecting -> Icon(
            imageVector = if (selected) AppIcons.Check else AppIcons.Eye,
            contentDescription = if (selected) "Terpilih" else "Belum dipilih",
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )

        transfer != null -> Text(
            text = formatProgress(transfer.progress),
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelMedium
            },
            color = MaterialTheme.colorScheme.primary,
        )

        else -> FileActionsMenu(
            onDownload = onDownload,
            onDelete = onDelete,
            onShare = onShare,
            onSelectMode = onSelectMode,
        )
    }
}

@Composable
private fun FileActionsMenu(
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onSelectMode: () -> Unit,
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
                text = { Text("Pilih beberapa") },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    expanded = false
                    onSelectMode()
                },
            )
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
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 0) "Pilih berkas" else "$count dipilih",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleAll) {
                Text(if (allSelected) "Batal pilih" else "Pilih semua")
            }
            TextButton(onClick = onDownload, enabled = count > 0) {
                Text("Unduh")
            }
            TextButton(onClick = onDelete, enabled = count > 0) {
                Text("Hapus", color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = "Keluar dari pilih beberapa",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    autoTopics: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
    onPickFolder: (String?) -> Unit,
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
                            text = if (autoTopics) {
                                "Berkas otomatis masuk topik sesuai jenisnya (Foto, Video, " +
                                    "Dokumen, dan seterusnya). Pilih folder hanya bila ingin " +
                                    "menaruhnya di tempat lain."
                            } else {
                                "Pilih folder tujuan, lalu unggah beberapa berkas sekaligus " +
                                    "atau seluruh folder beserta subfoldernya."
                            },
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
                        label = { Text(if (autoTopics) "Otomatis" else "Tanpa folder") },
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
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { onPickFolder(chosenFolder) }) {
                        Icon(
                            imageVector = AppIcons.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Unggah folder")
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
