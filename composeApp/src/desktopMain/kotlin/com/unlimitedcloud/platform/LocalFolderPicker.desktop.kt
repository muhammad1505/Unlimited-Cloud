package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser

@Composable
actual fun rememberLocalFolderPicker(onPicked: (LocalFolderSelection?) -> Unit): () -> Unit {
    val chooser = remember {
        JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Pilih folder untuk diunggah"
        }
    }
    return remember(chooser) {
        {
            val result = chooser.showOpenDialog(null)
            val directory = if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            onPicked(
                directory?.let { folder ->
                    LocalFolderSelection(name = folder.name, files = folder.childFiles())
                },
            )
        }
    }
}

/** Every file under [root], keeping the subfolder as a `/`-separated prefix. */
private fun File.childFiles(limit: Int = 500): List<LocalFolderFile> =
    walkTopDown()
        .filter { it.isFile }
        .take(limit)
        .map { file ->
            LocalFolderFile(
                relativePath = file.relativeTo(this).path.replace(File.separatorChar, '/'),
                localPath = file.absolutePath,
            )
        }
        .toList()
