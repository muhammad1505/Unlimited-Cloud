package com.unlimitedcloud.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.File

@Composable
actual fun rememberLocalFolderPicker(onPicked: (LocalFolderSelection?) -> Unit): () -> Unit {
    val context = AndroidPlatform.requireContext()
    val result by rememberUpdatedState(onPicked)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            result(null)
            return@rememberLauncherForActivityResult
        }
        result(runCatching { copyFolderToCache(context, uri) }.getOrNull())
    }

    return remember(launcher) { { launcher.launch(null) } }
}

/**
 * Copies the picked SAF tree into the cache so TDLib can upload from plain
 * paths. Bounded by [MAX_FILES] and [MAX_DEPTH] so a huge tree cannot fill the
 * cache or stall the picker.
 */
private fun copyFolderToCache(context: Context, treeUri: Uri): LocalFolderSelection? {
    val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: return null
    val name = documentName(context, treeUri, rootId)?.ifBlank { "folder" } ?: "folder"
    val targetDirectory = File(context.cacheDir, "uploads/${name.safeFileName()}").apply { mkdirs() }

    val collected = mutableListOf<String>()
    copyChildren(context, treeUri, rootId, targetDirectory, collected, depth = 0)
    return LocalFolderSelection(name = name, files = collected)
}

private fun copyChildren(
    context: Context,
    treeUri: Uri,
    parentDocumentId: String,
    targetDirectory: File,
    collected: MutableList<String>,
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
                    copyChildren(
                        context = context,
                        treeUri = treeUri,
                        parentDocumentId = documentId,
                        targetDirectory = targetDirectory,
                        collected = collected,
                        depth = depth + 1,
                    )
                    continue
                }

                val target = File(targetDirectory, displayName.safeFileName())
                val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val copied = runCatching {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } != null
                }.getOrDefault(false)
                if (copied) collected += target.absolutePath
            }
        }
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

private const val MAX_DEPTH = 4
private const val MAX_FILES = 200
