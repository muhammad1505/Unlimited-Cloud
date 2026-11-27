package com.unlimitedcloud.platform

import java.awt.EventQueue
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * The desktop app's tray presence: one icon for the whole process, a menu to
 * bring the window back or quit, and a place to surface transfer feedback.
 *
 * Plain AWT instead of the Compose `Tray` composable so the transfer notifier
 * can reach the same icon: the tooltip follows the running batch and a finished
 * upload/download posts a balloon message. Without that the desktop build had no
 * feedback at all once the window was hidden.
 */
internal object DesktopTray {
    private const val APP_NAME = "Unlimited Cloud"
    private const val MAX_TOOLTIP_LENGTH = 120

    private var trayIcon: TrayIcon? = null

    /** Some Linux sessions (GNOME on Wayland) expose no tray at all. */
    val supported: Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    /**
     * Installs the icon once and returns whether the window may be hidden to it.
     * Tray support is not a guarantee that adding succeeds, so the result is
     * reported instead of assumed.
     */
    fun install(image: Image, onOpen: () -> Unit, onQuit: () -> Unit): Boolean {
        if (!supported) return false
        if (trayIcon != null) return true
        return runCatching {
            val menu = PopupMenu()
            menu.add(MenuItem("Buka Unlimited Cloud").apply { addActionListener { onOpen() } })
            menu.addSeparator()
            menu.add(MenuItem("Keluar").apply { addActionListener { onQuit() } })
            val icon = TrayIcon(image, APP_NAME, menu).apply {
                isImageAutoSize = true
                // Double click (and the platform's own click gesture) restores
                // the window, matching the menu's first entry.
                addActionListener { onOpen() }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        }.getOrDefault(false)
    }

    /** Balloon/toast message on platforms that show one; silent elsewhere. */
    fun notify(title: String, message: String) {
        onTray { icon -> icon.displayMessage(title, message, TrayIcon.MessageType.NONE) }
    }

    /** Shows the running batch in the tray tooltip. */
    fun showProgress(title: String, message: String) {
        val text = "$title · $message"
        updateTooltip(if (text.length > MAX_TOOLTIP_LENGTH) text.take(MAX_TOOLTIP_LENGTH - 1) + "…" else text)
    }

    fun clearProgress() = updateTooltip(APP_NAME)

    fun remove() {
        val icon = trayIcon ?: return
        trayIcon = null
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }

    private fun updateTooltip(text: String) {
        onTray { icon -> icon.toolTip = text }
    }

    /**
     * AWT state is changed on the event dispatch thread; the notifier calls in
     * from TDLib's worker threads.
     */
    private fun onTray(action: (TrayIcon) -> Unit) {
        val icon = trayIcon ?: return
        if (EventQueue.isDispatchThread()) {
            runCatching { action(icon) }
        } else {
            EventQueue.invokeLater { runCatching { action(icon) } }
        }
    }
}
