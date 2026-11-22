package com.unlimitedcloud.log

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

actual fun platformTimestamp(): String = LocalTime.now().format(timestampFormat)
