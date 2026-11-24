package com.unlimitedcloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
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
        val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))
        var windowVisible by remember { mutableStateOf(true) }
        val icon = remember { loadWindowIcon() }
        val canHideToTray = isTraySupported && icon != null

        fun showWindow() {
            windowState.isMinimized = false
            windowVisible = true
        }

        if (isTraySupported && icon != null) {
            Tray(
                icon = icon,
                tooltip = "Unlimited Cloud",
                onAction = ::showWindow,
            ) {
                Item("Buka Unlimited Cloud", onClick = ::showWindow)
                Separator()
                Item("Keluar", onClick = ::exitApplication)
            }
        }

        Window(
            onCloseRequest = {
                // With a tray the session keeps running in the background; without
                // one, hiding the only window would make the app unreachable.
                if (canHideToTray) windowVisible = false else exitApplication()
            },
            visible = windowVisible,
            title = "Unlimited Cloud",
            icon = icon,
            state = windowState,
        ) {
            App()
        }
    }
}

/** Loads the packaged PNG so the window, launcher and tray show the brand mark. */
private fun loadWindowIcon(): Painter? = runCatching {
    val stream = object {}.javaClass.getResourceAsStream("/icon.png") ?: return null
    val bytes = stream.use { it.readBytes() }
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()
