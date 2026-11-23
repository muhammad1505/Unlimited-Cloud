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
 */
private class AndroidGallerySyncSource(
    private val context: Context,
) : GallerySyncSource {

    override val supported: Boolean = true

    override suspend fun pendingPhotos(
        folder: GalleryFolder,
        alreadySynced: Set<String>,
        limit: Int,
    ): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        clearStaleCache()
        val treeUri = runCatching { Uri.parse(folder.id) }.getOrNull()
            ?: return@withContext emptyList()
        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return@withContext emptyList()

        collectDocuments(treeUri, rootDocumentId, depth = 0, budget = MAX_SCAN)
            .asSequence()
            .filter { it.mimeType.startsWith("image/") }
            .sortedByDescending { it.lastModified }
            .mapNotNull { document ->
                val key = "${document.name}|${document.documentId}"
                if (key in alreadySynced) return@mapNotNull null
                val copy = copyToCache(treeUri, document) ?: return@mapNotNull null
                GalleryPhoto(
                    key = key,
                    name = document.name,
                    localPath = copy.absolutePath,
                    sizeBytes = document.size,
                )
            }
            .take(limit)
            .toList()
    }

    override fun release(photos: List<GalleryPhoto>) {
        photos.forEach { photo -> runCatching { File(photo.localPath).delete() } }
    }

    private fun collectDocuments(
        treeUri: Uri,
        parentDocumentId: String,
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
                        val nested = collectDocuments(
                            treeUri = treeUri,
                            parentDocumentId = documentId,
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
                        mimeType = mimeType,
                        size = cursor.getLong(3),
                        lastModified = cursor.getLong(4),
                    )
                }
            }
        }

        return documents
    }

    private fun copyToCache(treeUri: Uri, document: ScannedDocument): File? = runCatching {
        val targetDirectory = File(context.cacheDir, "gallery-sync").apply { mkdirs() }
        val safeName = document.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "foto" }
        val target = File(targetDirectory, "${document.documentId.hashCode()}-$safeName")
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, document.documentId)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target
    }.getOrNull()

    /** Removes copies orphaned by a sync that was interrupted before release(). */
    private fun clearStaleCache() {
        runCatching {
            File(context.cacheDir, "gallery-sync")
                .listFiles()
                ?.forEach { file -> file.delete() }
        }
    }

    private data class ScannedDocument(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
    )

    private companion object {
        const val MAX_DEPTH = 3
        const val MAX_SCAN = 600
    }
}

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
                name = folderName(context, uri) ?: "Galeri",
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
