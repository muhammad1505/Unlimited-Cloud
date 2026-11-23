package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

@Composable
actual fun rememberFileSharer(onResult: (ShareResult) -> Unit): (ShareRequest) -> Unit {
    val result by rememberUpdatedState(onResult)
    return remember { { request -> result(copyToClipboard(request)) } }
}

/**
 * Desktop has no share sheet, so the file is put on the clipboard as a file
 * list. File managers, chat clients and mail apps can paste it directly.
 */
private fun copyToClipboard(request: ShareRequest): ShareResult = runCatching {
    val file = File(request.localPath)
    require(file.isFile) { "Berkas belum tersedia di perangkat" }

    val selection = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any = listOf(file)
    }

    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    ShareResult.Shared
}.getOrElse { failure ->
    ShareResult.Failed(failure.message ?: "Clipboard tidak tersedia di perangkat ini")
}
