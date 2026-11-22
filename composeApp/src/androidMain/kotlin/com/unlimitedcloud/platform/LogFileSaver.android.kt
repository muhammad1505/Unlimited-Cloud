package com.unlimitedcloud.platform

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberLogFileSaver(
    onResult: (LogExportResult) -> Unit,
): (String, String) -> Unit {
    val context = AndroidPlatform.requireContext()
    // The Launcher callback is registered once, so the current lambda is read
    // through rememberUpdatedState instead of being captured on first composition.
    val result by rememberUpdatedState(onResult)
    val pending = remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val content = pending.value
        pending.value = null
        when {
            content == null || uri == null -> result(LogExportResult.Cancelled)
            else -> result(writeText(context, uri, content))
        }
    }

    return remember(launcher) {
        { fileName, content ->
            pending.value = content
            launcher.launch(fileName)
        }
    }
}

private fun writeText(context: Context, uri: Uri, content: String): LogExportResult = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { output ->
        output.write(content.toByteArray())
    } ?: error("tujuan tidak bisa dibuka")
}.fold(
    onSuccess = { LogExportResult.Saved(location = null) },
    onFailure = { LogExportResult.Failed(it.message ?: "gagal menulis berkas") },
)
