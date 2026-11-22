package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.File
import javax.swing.JFileChooser

@Composable
actual fun rememberLogFileSaver(
    onResult: (LogExportResult) -> Unit,
): (String, String) -> Unit {
    val result by rememberUpdatedState(onResult)
    val chooser = remember {
        JFileChooser().apply {
            dialogTitle = "Simpan log diagnostik"
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
    }

    return remember(chooser) {
        { fileName, content ->
            chooser.selectedFile = File(fileName)
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val target = chooser.selectedFile
                runCatching { target.writeText(content) }.fold(
                    onSuccess = { result(LogExportResult.Saved(target.absolutePath)) },
                    onFailure = { result(LogExportResult.Failed(it.message ?: "gagal menulis berkas")) },
                )
            } else {
                result(LogExportResult.Cancelled)
            }
        }
    }
}
