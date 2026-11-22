package com.unlimitedcloud.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

@Composable
actual fun rememberLocalFilePicker(onPicked: (String?) -> Unit): () -> Unit {
    val context = AndroidPlatform.requireContext()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onPicked(uri?.let { copyToCache(context, it) })
    }
    return remember(launcher) { { launcher.launch("*/*") } }
}

private fun copyToCache(context: Context, uri: Uri): String? = runCatching {
    val name = displayName(context, uri) ?: "upload-${System.currentTimeMillis()}.bin"
    val target = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    target.absolutePath
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
