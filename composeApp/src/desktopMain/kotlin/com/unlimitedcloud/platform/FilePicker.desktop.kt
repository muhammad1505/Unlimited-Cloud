package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit {
    val chooser = remember {
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            dialogTitle = "Pilih foto grup penyimpanan"
            fileFilter = FileNameExtensionFilter(
                "Gambar (png, jpg, webp)",
                "png",
                "jpg",
                "jpeg",
                "webp",
            )
        }
    }
    return remember(chooser) {
        {
            val picked = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else {
                null
            }
            onPicked(picked)
        }
    }
}

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
