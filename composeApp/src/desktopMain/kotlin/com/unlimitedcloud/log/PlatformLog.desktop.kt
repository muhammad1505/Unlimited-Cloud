package com.unlimitedcloud.log

actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}
