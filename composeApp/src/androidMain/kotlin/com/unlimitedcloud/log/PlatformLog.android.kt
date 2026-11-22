package com.unlimitedcloud.log

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun platformLog(tag: String, message: String) {
    Log.d(tag, message)
}

// java.time rather than SimpleDateFormat: lines are recorded from the TDLib
// receive coroutine and the UI coroutine, and SimpleDateFormat is not thread safe.
actual fun platformTimestamp(): String = LocalTime.now().format(timestampFormat)
