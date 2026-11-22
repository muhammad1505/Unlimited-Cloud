package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.swing.JFileChooser

@Composable
actual fun rememberLocalFilePicker(onPicked: (String?) -> Unit): () -> Unit {
    val chooser = remember {
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            dialogTitle = "Pilih berkas untuk diunggah"
        }
    }
    return remember(chooser) {
        {
            val result = chooser.showOpenDialog(null)
            onPicked(
                if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null,
            )
        }
    }
}
