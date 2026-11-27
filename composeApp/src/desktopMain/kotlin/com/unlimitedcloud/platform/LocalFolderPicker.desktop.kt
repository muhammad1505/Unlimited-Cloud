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
                    // One extra entry tells whether the ceiling was hit, so the
                    // upload can say that files were left behind instead of
                    // pretending the folder held only [MAX_FOLDER_FILES] files.
                    val scanned = folder.childFiles(MAX_FOLDER_FILES + 1)
                    LocalFolderSelection(
                        name = folder.name,
                        files = scanned.take(MAX_FOLDER_FILES),
                        truncated = scanned.size > MAX_FOLDER_FILES,
                    )
                },
            )
        }
    }
}

/** Every file under [root], keeping the subfolder as a `/`-separated prefix. */
private fun File.childFiles(limit: Int): List<LocalFolderFile> =
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

private const val MAX_FOLDER_FILES = 5_000
