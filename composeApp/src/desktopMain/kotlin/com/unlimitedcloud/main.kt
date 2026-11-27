package com.unlimitedcloud

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.di.appModule
import com.unlimitedcloud.platform.DesktopTray
import java.awt.Image as AwtImage
import javax.imageio.ImageIO
import org.jetbrains.skia.Image
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

fun main() {
    // JNA marshals String with the platform encoding unless told otherwise, and
    // Windows with JDK 17 is not UTF-8. Non-ASCII file names reached TDLib
    // mangled, so exactly those uploads failed with "file not found".
    System.setProperty("jna.encoding", "UTF-8")

    if (GlobalContext.getOrNull() == null) {
        startKoin { modules(appModule) }
    }

    application {
        val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))
        var windowVisible by remember { mutableStateOf(true) }
        val icon = remember { loadWindowIcon() }
        val trayImage = remember { loadTrayImage() }
        var trayReady by remember { mutableStateOf(false) }
        // The same Koin instance the signed-in shell uses; the tray menu needs it
        // to offer a sync and to show how many transfers are running.
        val viewModel = remember {
            runCatching { GlobalContext.getOrNull()?.get<CloudFilesViewModel>() }.getOrNull()
        }
        val uiState = viewModel?.uiState?.collectAsState()?.value

        fun showWindow() {
            windowState.isMinimized = false
            windowVisible = true
        }

        // Close TDLib before the process disappears so its local database is
        // flushed instead of being abandoned mid-write.
        fun quit() {
            runCatching { GlobalContext.getOrNull()?.get<TelegramEngine>()?.stop() }
            exitApplication()
        }

        // The tray outlives the window: closing only hides it, and the menu (or
        // a click on the icon) brings it back. Transfers keep running meanwhile.
        DisposableEffect(trayImage) {
            trayReady = trayImage?.let { image ->
                DesktopTray.install(
                    image = image,
                    onOpen = ::showWindow,
                    onQuit = ::quit,
                    onSync = { viewModel?.syncGallery() },
                )
            } ?: false
            onDispose { DesktopTray.remove() }
        }

        // Keep the menu line and the sync entry in step with the window.
        LaunchedEffect(
            uiState?.activeTransfers?.size,
            uiState?.gallerySyncing,
            uiState?.galleryFolders?.size,
        ) {
            DesktopTray.updateStatus(
                activeTransfers = uiState?.activeTransfers?.size ?: 0,
                syncing = uiState?.gallerySyncing ?: false,
                hasFolders = uiState?.galleryFolders?.isNotEmpty() == true,
            )
        }

        Window(
            onCloseRequest = {
                // With a tray the session keeps running in the background; without
                // one, hiding the only window would make the app unreachable.
                if (trayReady) windowVisible = false else quit()
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

/** AWT image for the tray; the packaged PNG is already a square brand mark. */
private fun loadTrayImage(): AwtImage? = runCatching {
    val stream = object {}.javaClass.getResourceAsStream("/icon.png") ?: return null
    stream.use { ImageIO.read(it) }
}.getOrNull()
