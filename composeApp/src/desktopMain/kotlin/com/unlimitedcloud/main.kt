package com.unlimitedcloud

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.unlimitedcloud.di.appModule
import org.jetbrains.skia.Image
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

fun main() {
    if (GlobalContext.getOrNull() == null) {
        startKoin { modules(appModule) }
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Unlimited Cloud",
            icon = remember { loadWindowIcon() },
            state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
        ) {
            App()
        }
    }
}

/** Loads the packaged PNG so the window/dock/taskbar shows the brand mark. */
private fun loadWindowIcon(): Painter? = runCatching {
    val stream = object {}.javaClass.getResourceAsStream("/icon.png") ?: return null
    val bytes = stream.use { it.readBytes() }
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()
