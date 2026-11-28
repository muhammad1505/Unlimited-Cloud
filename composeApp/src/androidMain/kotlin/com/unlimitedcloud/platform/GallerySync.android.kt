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
import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
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
        val (treeUri, rootDocumentId) = treeOf(folder)

        val cacheDirectory = File(context.cacheDir, "gallery-sync").apply { mkdirs() }
        // Every picked document is copied before TDLib can read it, so one folder
        // of videos could fill the device. A batch may use at most half of the
        // free space (capped below); a document that does not fit is reported as
        // skipped instead of failing the whole batch or the copy silently.
        var remainingBytes = cacheBudget(cacheDirectory)
        val skipped = mutableListOf<String>()

        // The filter runs inside the walk: a fixed cut on *documents* used to
        // hide every newer file behind 4.000 already-synced ones, so the rest
        // of the folder never got its turn.
        val scan = Scan(limit = MAX_CANDIDATES) { document ->
            val key = document.documentId
            key !in alreadySynced && "${document.name}|$key" !in alreadySynced
        }
        collectDocuments(
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            relativeDirectory = "",
            depth = 0,
            scan = scan,
        )

        val files = scan.documents
            .asSequence()
            .sortedByDescending { it.lastModified }
            // take(limit) comes before the copy: mapping first copied every
            // scanned file of the folder for each batch, then uploaded a handful
            // and deleted the rest again.
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
                    documentUri = DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, document.documentId)
                        .toString(),
                )
            }
            .toList()

        GalleryBatch(files = files, skippedKeys = skipped)
    }

    override suspend fun countPending(
        folder: GalleryFolder,
        alreadySynced: Set<String>,
    ): Int = withContext(Dispatchers.IO) {
        val (treeUri, rootDocumentId) = treeOf(folder)
        // Counted with the same filter the batch uses, so the banner and the ETA
        // match what the sync will really upload. Only the budget on inspected
        // documents can still cut this short, and that is logged.
        val scan = Scan(limit = Int.MAX_VALUE) { document ->
            document.documentId !in alreadySynced &&
                "${document.name}|${document.documentId}" !in alreadySynced
        }
        collectDocuments(
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            relativeDirectory = "",
            depth = 0,
            scan = scan,
        )
        if (scan.exhausted) {
            logDiagnostic(
                LOG_TAG,
                "sinkron: hitungan berkas menunggu dipotong di ${scan.inspected} dokumen",
            )
        }
        scan.documents.size
    }

    /** The picked tree, or a failure the sync can report instead of "empty". */
    private fun treeOf(folder: GalleryFolder): Pair<Uri, String> {
        val uri = runCatching { Uri.parse(folder.id) }.getOrNull()
            ?: error("Folder sinkron tidak bisa dibaca")
        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: error("Folder sinkron tidak bisa dibaca")
        return uri to rootDocumentId
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

    /**
     * Walks the picked tree, handing every document to [scan].
     *
     * A document the scan rejects still costs inspection budget, so synced
     * files cannot make a batch look empty. [Scan.hasRoom] bounds both the work
     * (inspected documents) and the collected ones, so a huge folder is read in
     * slices the next run continues instead of being cut off for good.
     *
     * A failure on the root tree is thrown: an unreadable folder looked exactly
     * like an empty one, and the sync silently uploaded nothing. A broken
     * subfolder is logged and skipped so one bad directory cannot stop the rest.
     */
    private fun collectDocuments(
        treeUri: Uri,
        parentDocumentId: String,
        relativeDirectory: String,
        depth: Int,
        scan: Scan,
    ) {
        if (depth > MAX_DEPTH || !scan.hasRoom()) return

        val cursor = try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentDocumentId,
            )
            context.contentResolver.query(childrenUri, COLUMNS, null, null, null)
        } catch (failure: Exception) {
            unreadable(depth, failure)
            return
        } ?: run {
            unreadable(depth, null)
            return
        }

        cursor.use { rows ->
            while (rows.moveToNext() && scan.hasRoom()) {
                scan.inspected++
                val documentId = rows.getString(0) ?: continue
                val name = rows.getString(1) ?: continue
                val mimeType = rows.getString(2).orEmpty()

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childDirectory = name.safeDirectoryName()
                    collectDocuments(
                        treeUri = treeUri,
                        parentDocumentId = documentId,
                        relativeDirectory = if (relativeDirectory.isEmpty()) {
                            childDirectory
                        } else {
                            "$relativeDirectory/$childDirectory"
                        },
                        depth = depth + 1,
                        scan = scan,
                    )
                    continue
                }

                scan.offer(
                    ScannedDocument(
                        documentId = documentId,
                        name = name,
                        size = rows.getLong(3),
                        lastModified = rows.getLong(4),
                        relativeDirectory = relativeDirectory,
                    ),
                )
            }
        }
    }

    private fun unreadable(depth: Int, failure: Exception?) {
        if (depth == 0) {
            throw IllegalStateException("Folder sinkron tidak bisa dibaca", failure)
        }
        logDiagnostic(
            LOG_TAG,
            "sinkron: subfolder dilewati (${failure?.message ?: "tanpa keterangan"})",
        )
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

    /**
     * Budget of one tree walk. [inspected] counts every row the cursor returned
     * and [documents] only the pending ones, capped by [limit].
     */
    private class Scan(
        private val limit: Int,
        private val accept: (ScannedDocument) -> Boolean,
    ) {
        var inspected = 0
        val documents = mutableListOf<ScannedDocument>()
        val exhausted: Boolean get() = inspected >= MAX_INSPECTED

        fun hasRoom(): Boolean = inspected < MAX_INSPECTED && documents.size < limit

        fun offer(document: ScannedDocument) {
            if (accept(document)) documents += document
        }
    }

    private companion object {
        /** Deep enough for real folder trees, which used to stop at three levels. */
        const val MAX_DEPTH = 16

        /** Columns read from the provider for every document of the tree. */
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /** Copies older than this were left behind by a killed sync. */
        const val STALE_CACHE_MILLIS = 2L * 60 * 60 * 1000

        /** Budget when the file system cannot report free space. */
        const val FALLBACK_CACHE_BYTES = 512L * 1024 * 1024
    }
}

/** Pending documents one batch may collect; the next run picks up the rest. */
private const val MAX_CANDIDATES = 4_000

/** Documents inspected per batch, synced or not, so a walk always ends. */
private const val MAX_INSPECTED = 20_000

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
        // Write access lets a synced original move to the provider's trash once
        // it is uploaded; a provider that refuses it still keeps the read grant.
        val writeGranted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!writeGranted) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
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
