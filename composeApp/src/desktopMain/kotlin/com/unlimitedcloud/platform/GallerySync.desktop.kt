package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

actual fun createGallerySyncSource(): GallerySyncSource = DesktopGallerySyncSource

/**
 * Desktop equivalent of the Android SAF source: the picked folder is a plain
 * directory on disk, so files are handed to TDLib where they already live and
 * nothing has to be copied to a cache first.
 *
 * Everything inside the tree counts, any file type, and the subfolder is kept
 * so the sync recreates `Galeri/Liburan/Pantai` instead of flattening the tree.
 */
private object DesktopGallerySyncSource : GallerySyncSource {
    override val supported: Boolean = true

    override suspend fun pendingFiles(
        folder: GalleryFolder,
        alreadySynced: Set<String>,
        limit: Int,
    ): GalleryBatch = withContext(Dispatchers.IO) {
        val root = runCatching { File(folder.id) }.getOrNull() ?: return@withContext GalleryBatch()
        if (!root.isDirectory) return@withContext GalleryBatch()

        val files = root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .filter { file -> file.isFile && file.canRead() }
            .take(MAX_SCAN)
            // Newest first, like the Android source: a folder keeps growing at
            // the bottom, and the files the user just copied are the ones worth
            // syncing first.
            .sortedByDescending { file -> file.lastModified() }
            .filter { file -> file.absolutePath !in alreadySynced }
            .take(limit)
            .map { file ->
                GalleryFile(
                    // The absolute path is the stable identity here; renaming the
                    // file changes the key, exactly like a new SAF document id.
                    key = file.absolutePath,
                    name = file.name,
                    localPath = file.absolutePath,
                    sizeBytes = file.length(),
                    relativeDirectory = file.relativeTo(root).invariantSeparatorsPath
                        .substringBeforeLast('/', ""),
                )
            }
            .toList()

        // Files are read from the original location, so nothing can be skipped
        // for size or a failed cache copy.
        GalleryBatch(files = files)
    }

    /** Nothing was copied, so there is no temporary file to remove. */
    override fun release(files: List<GalleryFile>) = Unit

    private const val MAX_DEPTH = 16

    /** Files inspected per batch; the sync repeats until the folder is done. */
    private const val MAX_SCAN = 4_000
}

@Composable
actual fun rememberGalleryFolderPicker(onPicked: (GalleryFolder?) -> Unit): () -> Unit {
    val result by rememberUpdatedState(onPicked)
    val chooser = remember {
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Pilih folder untuk disinkronkan"
        }
    }
    return remember(chooser) {
        {
            val chosen = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile
            } else {
                null
            }
            result(
                chosen?.let { folder ->
                    // The absolute path doubles as the tree id, so the selection
                    // survives a restart without any extra grant to renew.
                    GalleryFolder(id = folder.absolutePath, name = folder.name)
                },
            )
        }
    }
}

/** `File.relativeTo` already returns the platform separator; keep it uniform. */
private val File.invariantSeparatorsPath: String
    get() = path.replace(File.separatorChar, '/')
