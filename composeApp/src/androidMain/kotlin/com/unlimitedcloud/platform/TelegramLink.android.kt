package com.unlimitedcloud.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

actual fun openTelegramChat(chatId: Long): Boolean {
    val context = AndroidPlatform.requireContext()
    return launch(context, "tg://openmessage?chat_id=$chatId") ||
        launch(context, "https://t.me/c/${internalSupergroupId(chatId)}")
}

private fun launch(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)

/**
 * `t.me/c` links use the supergroup id without Telegram's `-100` prefix, which
 * is exactly what the chat id carries for a supergroup.
 */
internal fun internalSupergroupId(chatId: Long): Long = -chatId - SUPERGROUP_CHAT_ID_PREFIX

private const val SUPERGROUP_CHAT_ID_PREFIX = 1_000_000_000_000L
