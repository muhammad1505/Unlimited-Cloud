package com.unlimitedcloud.platform

/**
 * Opens the private storage group in Telegram.
 *
 * The app has no username to link to, so the chat id is turned into the
 * `tg://openmessage` deep link with the web link as a fallback. Returns `false`
 * when no Telegram client (or browser) accepted either link.
 */
expect fun openTelegramChat(chatId: Long): Boolean
