package com.unlimitedcloud.log

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import com.unlimitedcloud.platform.AndroidPlatform
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun platformLog(tag: String, message: String) {
    Log.d(tag, message)
}

// java.time rather than SimpleDateFormat: lines are recorded from the TDLib
// receive coroutine and the UI coroutine, and SimpleDateFormat is not thread safe.
actual fun platformTimestamp(): String = LocalTime.now().format(timestampFormat)

actual fun platformCopyToClipboard(text: String): Boolean = runCatching {
    val clipboard = AndroidPlatform.requireContext()
        .getSystemService(ClipboardManager::class.java)
        ?: error("clipboard tidak tersedia")
    clipboard.setPrimaryClip(ClipData.newPlainText("Log diagnostik", text))
    true
}.getOrDefault(false)
