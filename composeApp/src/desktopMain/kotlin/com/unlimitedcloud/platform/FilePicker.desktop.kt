package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.swing.JFileChooser

@Composable
actual fun rememberLocalFilePicker(onPicked: (List<String>) -> Unit): () -> Unit {
    val chooser = remember {
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
            dialogTitle = "Pilih berkas untuk diunggah"
        }
    }
    return remember(chooser) {
        {
            val result = chooser.showOpenDialog(null)
            onPicked(
                if (result == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFiles.orEmpty().map { file -> file.absolutePath }
                } else {
                    emptyList()
                },
            )
        }
    }
}
