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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberLocalFolderPicker(onPicked: (LocalFolderSelection?) -> Unit): () -> Unit {
    val context = AndroidPlatform.requireContext()
    val result by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            result(null)
            return@rememberLauncherForActivityResult
        }
        // The grant is kept for the session so the originals of uploaded files
        // can later be moved to trash; write access is needed for that, and a
        // provider that refuses it still leaves the read grant working.
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
        // Copying a whole tree takes long enough that doing it on the main
        // thread froze the UI (and could trip an ANR).
        scope.launch {
            val selection = withContext(Dispatchers.IO) {
                runCatching { copyFolderToCache(context, uri) }.getOrNull()
            }
            result(selection)
        }
    }

    return remember(launcher) { { launcher.launch(null) } }
}

/**
 * Copies the picked SAF tree into the cache so TDLib can upload from plain
 * paths, mirroring the subfolders into nested cache directories.
 *
 * Every document is copied, whatever its type: the picker never filters by
 * MIME type, because the whole point of the app is storing any file. The old
 * limit of 500 files silently dropped the rest of a large folder, which looked
 * like "only images were read" whenever that is what the cursor happened to
 * return first. The limits below only exist to protect the cache, and hitting
 * one is reported through [LocalFolderSelection.truncated] instead of hiding
 * the files that were left behind.
 *
 * Every pick gets its own directory and every file a unique name: two files with
 * the same display name (common across sub-folders) used to overwrite each other,
 * so one of them was uploaded twice instead of both.
 */
private fun copyFolderToCache(context: Context, treeUri: Uri): LocalFolderSelection? {
    val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: return null
    val name = documentName(context, treeUri, rootId)?.ifBlank { "folder" } ?: "folder"
    val uploadRoot = File(context.cacheDir, "uploads").apply { mkdirs() }
    pruneOldUploads(uploadRoot)
    val targetDirectory = File(
        uploadRoot,
        "${name.safeFileName()}-${System.currentTimeMillis()}",
    ).apply { mkdirs() }

    // Keep at most half of the free cache space: the copies are temporary, and
    // filling the cache would break TDLib's own file storage as well.
    val freeSpace = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
    val byteBudget = if (freeSpace > 0L) minOf(MAX_BYTES, freeSpace / 2) else MAX_BYTES

    val scan = FolderScan(
        context = context,
        treeUri = treeUri,
        targetDirectory = targetDirectory,
        byteBudget = byteBudget,
    )
    scan.scan(rootId)

    logDiagnostic(
        LOG_TAG,
        "pindai folder: ${scan.files.size} berkas (${scan.bytes} B) dari $name, " +
            "dilewati ${scan.skipped}, batas tercapai ${scan.truncated}" +
            (scan.lastFailure?.let { " · terakhir: $it" } ?: ""),
    )

    return LocalFolderSelection(
        name = name,
        files = scan.files,
        skipped = scan.skipped,
        truncated = scan.truncated,
        cacheRoot = targetDirectory.absolutePath,
    )
}

/**
 * Walks one picked tree: folders are recursed into, every other document is
 * copied. A document that cannot be opened is counted as skipped, never silently
 * ignored, so the upload summary can tell the user how many files stayed behind.
 */
private class FolderScan(
    private val context: Context,
    private val treeUri: Uri,
    private val targetDirectory: File,
    private val byteBudget: Long,
) {
    val files = mutableListOf<LocalFolderFile>()
    var bytes = 0L
    var skipped = 0
    var truncated = false
    var lastFailure: String? = null

    private fun limitReached(): Boolean = files.size >= MAX_FILES || bytes >= byteBudget

    fun scan(rootDocumentId: String) {
        copyChildren(
            parentDocumentId = rootDocumentId,
            relativeDirectory = "",
            depth = 0,
        )
    }

    private fun copyChildren(
        parentDocumentId: String,
        relativeDirectory: String,
        depth: Int,
    ) {
        // Depth is reported, not hidden: a folder nested deeper than the guard
        // counts as truncated so the upload summary says so.
        if (depth > MAX_DEPTH) {
            truncated = true
            return
        }
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        }.getOrElse { failure ->
            skipped++
            lastFailure = failure.message
            return
        }

        val query = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (limitReached()) {
                        truncated = true
                        return@use
                    }
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2).orEmpty()

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val childDirectory = displayName.safeFileName()
                        copyChildren(
                            parentDocumentId = documentId,
                            relativeDirectory = if (relativeDirectory.isEmpty()) {
                                childDirectory
                            } else {
                                "$relativeDirectory/$childDirectory"
                            },
                            depth = depth + 1,
                        )
                        // Only the file/size budget stops the whole walk; a
                        // folder that was merely too deep still lets its
                        // siblings be copied.
                        if (limitReached()) {
                            truncated = true
                            return@use
                        }
                        continue
                    }

                    val directory = File(targetDirectory, relativeDirectory).apply { mkdirs() }
                    val target = uniqueTarget(directory, displayName)
                    val sourceUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val copied = runCatching {
                        context.contentResolver.openInputStream(sourceUri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } != null
                    }.getOrElse { failure ->
                        lastFailure = failure.message
                        false
                    }
                    if (copied) {
                        files += LocalFolderFile(
                            relativePath = if (relativeDirectory.isEmpty()) {
                                target.name
                            } else {
                                "$relativeDirectory/${target.name}"
                            },
                            localPath = target.absolutePath,
                            documentUri = DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, documentId)
                                .toString(),
                        )
                        bytes += target.length()
                    } else {
                        skipped++
                    }
                }
            }
        }
        query.onFailure { failure ->
            skipped++
            lastFailure = failure.message
        }
    }
}

/** Never overwrites a file another file of the same pick already copied. */
private fun uniqueTarget(directory: File, displayName: String): File {
    val safe = displayName.safeFileName()
    var candidate = File(directory, safe)
    if (!candidate.exists()) return candidate

    val extension = safe.substringAfterLast('.', "")
    val base = if (extension.isEmpty()) safe else safe.removeSuffix(".$extension")
    var index = 2
    while (candidate.exists()) {
        candidate = File(
            directory,
            if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension",
        )
        index++
    }
    return candidate
}

/** Deletes copies from picks that finished long ago; nothing reads them again. */
private fun pruneOldUploads(root: File) {
    val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
    root.listFiles()?.forEach { directory ->
        if (directory.lastModified() < cutoff) directory.deleteRecursively()
    }
}

private fun documentName(context: Context, treeUri: Uri, documentId: String): String? = runCatching {
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    context.contentResolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

private fun String.safeFileName(): String =
    substringAfterLast('/').substringAfterLast('\\').replace(':', '_').ifBlank { "berkas" }

/** Nesting guard only; real trees are rarely deeper and deeper ones are reported. */
private const val MAX_DEPTH = 24

/** Generous ceiling: a storage folder may hold any number of documents. */
private const val MAX_FILES = 5_000

/** Per-pick copy budget, further capped to half of the free cache space. */
private const val MAX_BYTES = 4L * 1024 * 1024 * 1024

private const val MAX_CACHE_AGE_MILLIS = 24L * 60 * 60 * 1000
