package com.unlimitedcloud.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
        // Copying a tree of up to 500 files takes long enough that doing it on
        // the main thread froze the UI (and could trip an ANR).
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
 * paths, mirroring the subfolders into nested cache directories. Bounded by
 * [MAX_FILES] and [MAX_DEPTH] so a huge tree cannot fill the cache or stall the
 * picker.
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

    val collected = mutableListOf<LocalFolderFile>()
    copyChildren(
        context = context,
        treeUri = treeUri,
        parentDocumentId = rootId,
        targetDirectory = targetDirectory,
        relativeDirectory = "",
        collected = collected,
        depth = 0,
    )
    return LocalFolderSelection(name = name, files = collected)
}

/**
 * Recurses into subfolders and records each file's path relative to the picked
 * root, so the upload can recreate `Liburan/Pantai/foto.jpg` instead of putting
 * everything in one folder.
 */
private fun copyChildren(
    context: Context,
    treeUri: Uri,
    parentDocumentId: String,
    targetDirectory: File,
    relativeDirectory: String,
    collected: MutableList<LocalFolderFile>,
    depth: Int,
) {
    if (depth > MAX_DEPTH || collected.size >= MAX_FILES) return
    val childrenUri = runCatching {
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    }.getOrNull() ?: return

    runCatching {
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
            while (cursor.moveToNext() && collected.size < MAX_FILES) {
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2).orEmpty()

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childDirectory = displayName.safeFileName()
                    copyChildren(
                        context = context,
                        treeUri = treeUri,
                        parentDocumentId = documentId,
                        targetDirectory = targetDirectory,
                        relativeDirectory = if (relativeDirectory.isEmpty()) {
                            childDirectory
                        } else {
                            "$relativeDirectory/$childDirectory"
                        },
                        collected = collected,
                        depth = depth + 1,
                    )
                    continue
                }

                val directory = File(targetDirectory, relativeDirectory).apply { mkdirs() }
                val target = uniqueTarget(directory, displayName)
                val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val copied = runCatching {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } != null
                }.getOrDefault(false)
                if (copied) {
                    collected += LocalFolderFile(
                        relativePath = if (relativeDirectory.isEmpty()) {
                            target.name
                        } else {
                            "$relativeDirectory/${target.name}"
                        },
                        localPath = target.absolutePath,
                    )
                }
            }
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

private const val MAX_DEPTH = 8
private const val MAX_FILES = 500
private const val MAX_CACHE_AGE_MILLIS = 24L * 60 * 60 * 1000
