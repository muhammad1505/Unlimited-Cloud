package com.unlimitedcloud.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

/** Both pickers copy what the user chose into the cache before uploading. */
actual val pickedFilesAreAppCopies: Boolean = true

@Composable
actual fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit {
    val context = AndroidPlatform.requireContext()
    val result by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            result(null)
            return@rememberLauncherForActivityResult
        }
        // The picked image is copied so TDLib has a real path to upload from.
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "group-photo").apply { mkdirs() }
                copyToCache(context, uri, directory)
            }
            result(path)
        }
    }

    return remember(launcher) { { launcher.launch("image/*") } }
}

@Composable
actual fun rememberLocalFilePicker(onPicked: (List<String>) -> Unit): () -> Unit {
    val context = AndroidPlatform.requireContext()
    // rememberUpdatedState so a lambda captured by the launcher still reflects
    // the latest upload folder after a recomposition.
    val result by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) {
            result(emptyList())
            return@rememberLauncherForActivityResult
        }
        // Copying a handful of documents can take seconds; doing it on the main
        // thread froze the UI and could trip an ANR.
        scope.launch {
            val paths = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "uploads").apply { mkdirs() }
                uris.mapNotNull { uri -> copyToCache(context, uri, directory) }
            }
            result(paths)
        }
    }

    return remember(launcher) { { launcher.launch("*/*") } }
}

/** Copies one picked document into the cache under a name no sibling has taken. */
private fun copyToCache(context: Context, uri: Uri, directory: File): String? = runCatching {
    val name = displayName(context, uri) ?: "upload-${System.currentTimeMillis()}.bin"
    val target = uniqueTarget(directory, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    target.absolutePath
}.getOrNull()

/** Never overwrites a file another pick (or the same pick) already copied. */
private fun uniqueTarget(directory: File, displayName: String): File {
    val safe = displayName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(':', '_')
        .ifBlank { "berkas" }
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

private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
