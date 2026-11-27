package com.unlimitedcloud.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun createGallerySyncSource(): GallerySyncSource =
    AndroidGallerySyncSource(AndroidPlatform.requireContext())

/**
 * Reads a SAF tree the user picked once. No `READ_MEDIA_IMAGES` permission is
 * involved: the persisted tree grant is the only access the app has, and it is
 * limited to the folder the user chose.
 *
 * Every document is collected, whatever its MIME type, and the folder it lived
 * in is carried along. The old image-only filter turned this into a photo
 * uploader, which is the opposite of a cloud that stores any file.
 */
private class AndroidGallerySyncSource(
    private val context: Context,
) : GallerySyncSource {

    override val supported: Boolean = true

    override suspend fun pendingFiles(
        folder: GalleryFolder,
        alreadySynced: Set<String>,
        limit: Int,
    ): GalleryBatch = withContext(Dispatchers.IO) {
        clearStaleCache()
        val treeUri = runCatching { Uri.parse(folder.id) }.getOrNull()
            ?: return@withContext GalleryBatch()
        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return@withContext GalleryBatch()

        val cacheDirectory = File(context.cacheDir, "gallery-sync").apply { mkdirs() }
        // Every picked document is copied before TDLib can read it, so one folder
        // of videos could fill the device. A batch may use at most half of the
        // free space (capped below); a document that does not fit is reported as
        // skipped instead of failing the whole batch or the copy silently.
        var remainingBytes = cacheBudget(cacheDirectory)
        val skipped = mutableListOf<String>()

        val files = collectDocuments(
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            relativeDirectory = "",
            depth = 0,
            budget = MAX_SCAN,
        )
            .asSequence()
            .sortedByDescending { it.lastModified }
            // take(limit) comes before the copy: mapping first copied every
            // scanned file of the folder for each batch, then uploaded a handful
            // and deleted the rest again.
            .filter { document ->
                val key = document.documentId
                key !in alreadySynced && "${document.name}|$key" !in alreadySynced
            }
            .take(limit)
            .mapNotNull { document ->
                if (document.size > remainingBytes) {
                    skipped += document.documentId
                    return@mapNotNull null
                }
                val copy = copyToCache(cacheDirectory, treeUri, document)
                if (copy == null) {
                    skipped += document.documentId
                    return@mapNotNull null
                }
                remainingBytes -= copy.length()
                GalleryFile(
                    // The document id alone: the old "name|id" key changed when a
                    // file was renamed, which uploaded it a second time. The
                    // legacy form is still honoured above so nothing re-uploads.
                    key = document.documentId,
                    name = document.name,
                    localPath = copy.absolutePath,
                    sizeBytes = document.size,
                    relativeDirectory = document.relativeDirectory,
                )
            }
            .toList()

        GalleryBatch(files = files, skippedKeys = skipped)
    }

    /**
     * How many bytes one batch may copy. Half of the free space leaves room for
     * TDLib's own upload buffer; an unreadable cache falls back to a fixed value
     * so the sync can still try. A single file may be larger than the batch
     * budget would suggest: this only refuses documents that cannot fit at all.
     */
    private fun cacheBudget(directory: File): Long {
        val usable = directory.usableSpace
        return if (usable > 0) usable / 2 else FALLBACK_CACHE_BYTES
    }

    override fun release(files: List<GalleryFile>) {
        files.forEach { file -> runCatching { File(file.localPath).delete() } }
    }

    private fun collectDocuments(
        treeUri: Uri,
        parentDocumentId: String,
        relativeDirectory: String,
        depth: Int,
        budget: Int,
    ): List<ScannedDocument> {
        if (depth > MAX_DEPTH || budget <= 0) return emptyList()

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        }.getOrNull() ?: return emptyList()

        val documents = mutableListOf<ScannedDocument>()
        var remaining = budget

        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext() && remaining > 0) {
                    remaining--
                    val documentId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2).orEmpty()

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val childDirectory = name.safeDirectoryName()
                        val nested = collectDocuments(
                            treeUri = treeUri,
                            parentDocumentId = documentId,
                            relativeDirectory = if (relativeDirectory.isEmpty()) {
                                childDirectory
                            } else {
                                "$relativeDirectory/$childDirectory"
                            },
                            depth = depth + 1,
                            budget = remaining,
                        )
                        remaining -= nested.size
                        documents += nested
                        continue
                    }

                    documents += ScannedDocument(
                        documentId = documentId,
                        name = name,
                        size = cursor.getLong(3),
                        lastModified = cursor.getLong(4),
                        relativeDirectory = relativeDirectory,
                    )
                }
            }
        }

        return documents
    }

    private fun copyToCache(
        cacheDirectory: File,
        treeUri: Uri,
        document: ScannedDocument,
    ): File? = runCatching {
        val targetDirectory = cacheDirectory.apply { mkdirs() }
        val safeName = document.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "berkas" }
        val target = File(targetDirectory, "${document.documentId.hashCode()}-$safeName")
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, document.documentId)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target
    }.getOrNull()

    /**
     * Removes copies orphaned by a sync that was interrupted before release().
     * Only old files are deleted: wiping the whole directory on every batch also
     * removed copies a still-running sync had just written.
     */
    private fun clearStaleCache() {
        runCatching {
            val cutoff = System.currentTimeMillis() - STALE_CACHE_MILLIS
            File(context.cacheDir, "gallery-sync")
                .listFiles()
                ?.forEach { file -> if (file.lastModified() < cutoff) file.delete() }
        }
    }

    private data class ScannedDocument(
        val documentId: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val relativeDirectory: String,
    )

    private companion object {
        /** Deep enough for real folder trees, which used to stop at three levels. */
        const val MAX_DEPTH = 16

        /** Documents scanned per batch; the sync repeats until nothing is left. */
        const val MAX_SCAN = 4_000

        /** Copies older than this were left behind by a killed sync. */
        const val STALE_CACHE_MILLIS = 2L * 60 * 60 * 1000

        /** Budget when the file system cannot report free space. */
        const val FALLBACK_CACHE_BYTES = 512L * 1024 * 1024
    }
}

private fun String.safeDirectoryName(): String =
    substringAfterLast('/').substringAfterLast('\\').replace(':', '_').ifBlank { "folder" }

@Composable
actual fun rememberGalleryFolderPicker(onPicked: (GalleryFolder?) -> Unit): () -> Unit {
    val context = AndroidPlatform.requireContext()
    val result by rememberUpdatedState(onPicked)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            result(null)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        result(
            GalleryFolder(
                id = uri.toString(),
                name = folderName(context, uri) ?: "Folder",
            ),
        )
    }

    return remember(launcher) { { launcher.launch(null) } }
}

private fun folderName(context: Context, treeUri: Uri): String? = runCatching {
    val documentId = DocumentsContract.getTreeDocumentId(treeUri)
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    context.contentResolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
