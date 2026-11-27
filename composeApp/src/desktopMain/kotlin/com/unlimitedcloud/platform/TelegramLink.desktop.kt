package com.unlimitedcloud.platform

import java.awt.Desktop
import java.net.URI

actual fun openTelegramChat(chatId: Long): Boolean {
    val appLink = "tg://openmessage?chat_id=$chatId"
    val webLink = "https://t.me/c/${internalSupergroupId(chatId)}"
    return browse(appLink) || browse(webLink)
}

private fun browse(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    Desktop.getDesktop().browse(URI(url))
    true
}.getOrDefault(false)

/** Mirrors Telegram's own `-100` prefix arithmetic for private groups. */
private fun internalSupergroupId(chatId: Long): Long = -chatId - 1_000_000_000_000L
