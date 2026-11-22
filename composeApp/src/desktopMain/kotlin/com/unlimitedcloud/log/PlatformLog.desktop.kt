package com.unlimitedcloud.log

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun platformLog(tag: String, message: String) {
    println("$tag: $message")
}

actual fun platformTimestamp(): String = LocalTime.now().format(timestampFormat)

actual fun platformCopyToClipboard(text: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit()
        .systemClipboard
        .setContents(StringSelection(text), null)
    true
}.getOrDefault(false)
