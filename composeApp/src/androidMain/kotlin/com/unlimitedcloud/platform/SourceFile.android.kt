package com.unlimitedcloud.platform

import android.net.Uri
import android.provider.DocumentsContract

actual val sourceFileDeletionSupported: Boolean = true

actual fun deleteSourceFile(localPath: String, documentUri: String?): Boolean {
    // A single picked file only has a one-shot read grant, so its content uri is
    // useless once the picker returns; only a granted folder carries a document
    // uri that is still valid here.
    val uri = documentUri ?: return false
    return runCatching {
        DocumentsContract.deleteDocument(
            AndroidPlatform.requireContext().contentResolver,
            Uri.parse(uri),
        )
    }.getOrDefault(false)
}
