package com.unlimitedcloud.platform

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberFileSharer(onResult: (ShareResult) -> Unit): (ShareRequest) -> Unit {
    val context = AndroidPlatform.requireContext()
    val result by rememberUpdatedState(onResult)
    // Downloads finish on a background dispatcher; the chooser must be started
    // from the main thread.
    val mainHandler = remember(context) { Handler(Looper.getMainLooper()) }
    return remember(context, mainHandler) {
        { request -> mainHandler.post { result(share(context, request)) } }
    }
}

/**
 * Copies the file out of TDLib's private directory into a FileProvider-backed
 * cache folder and starts the system chooser. The grant only covers the copied
 * cache file, so other apps never see the rest of the app storage.
 */
private fun share(context: Context, request: ShareRequest): ShareResult = runCatching {
    val source = File(request.localPath)
    if (!source.isFile) error("Berkas belum tersedia di perangkat")

    val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
    pruneOldShares(shareDirectory)
    val fileName = request.fileName.ifBlank { source.name }
    // A unique name per share: overwriting the file another app was still
    // reading through its FileProvider URI truncated that share mid-read.
    val target = uniqueShareTarget(shareDirectory, fileName)
    source.copyTo(target, overwrite = true)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        target,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = request.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Bagikan $fileName").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
    ShareResult.Shared
}.getOrElse { failure ->
    ShareResult.Failed(failure.message ?: "Tidak bisa membagikan berkas")
}

/** Removes copies from earlier shares; nothing reads them after the chooser closes. */
private fun pruneOldShares(directory: File) {
    val cutoff = System.currentTimeMillis() - SHARE_CACHE_MILLIS
    directory.listFiles()?.forEach { file -> if (file.lastModified() < cutoff) file.delete() }
}

private fun uniqueShareTarget(directory: File, fileName: String): File {
    val safe = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "berkas" }
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

private const val SHARE_CACHE_MILLIS = 60L * 60 * 1000
