package com.unlimitedcloud.ui.util

import kotlin.math.roundToInt

/** Human readable byte size with binary units, e.g. `1.4 GB`. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    if (index == 0) return "$bytes B"
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) {
        "${rounded.toInt()} ${units[index]}"
    } else {
        "$rounded ${units[index]}"
    }
}

/** Two-letter avatar initials; falls back to the product initials. */
fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "UC"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

fun formatProgress(progress: Float?): String =
    if (progress == null) "…" else "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"
