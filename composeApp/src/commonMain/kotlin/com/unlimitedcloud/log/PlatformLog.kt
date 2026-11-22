package com.unlimitedcloud.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Writes a diagnostic line to the platform log: logcat on Android, stdout on desktop.
 *
 * Only message types and errors are logged, never request payloads, so phone
 * numbers, codes and passwords stay out of the log.
 */
expect fun platformLog(tag: String, message: String)

/** Wall clock for the current line, formatted `HH:mm:ss` by the platform. */
expect fun platformTimestamp(): String

/** Puts [text] on the system clipboard; `false` when the platform refused. */
expect fun platformCopyToClipboard(text: String): Boolean

/** Tag used by every diagnostic line of the app. */
const val LOG_TAG = "UnlimitedCloud"

/** One diagnostics line kept in memory for the in-app log screen. */
data class LogEntry(
    val time: String,
    val tag: String,
    val message: String,
)

/**
 * Ring buffer behind the Diagnostik screen.
 *
 * Bounded, because a long upload poll can emit hundreds of lines and the buffer
 * must never grow without limit. Oldest line is dropped first.
 */
object LogStore {
    private const val CAPACITY = 400

    private val state = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = state.asStateFlow()

    fun add(tag: String, message: String) {
        val entry = LogEntry(time = platformTimestamp(), tag = tag, message = message)
        state.update { current ->
            when {
                current.size < CAPACITY -> current + entry
                else -> current.drop(current.size - CAPACITY + 1) + entry
            }
        }
    }

    fun clear() {
        state.value = emptyList()
    }
}

/**
 * Single entry point for diagnostics: stores the line for the in-app screen and
 * forwards it to logcat/stdout. Callers pass event names and error messages,
 * never payloads, which keeps credentials out of both sinks.
 */
fun logDiagnostic(tag: String, message: String) {
    LogStore.add(tag, message)
    platformLog(tag, message)
}

/**
 * Plain-text rendering shared by copy and export, so both produce identical
 * output: a short header naming the source and line count, then one line per
 * entry as `HH:mm:ss TAG message`.
 */
fun formatLogForExport(entries: List<LogEntry>): String = buildString {
    appendLine("# Unlimited Cloud · log diagnostik")
    appendLine("# ${entries.size} baris · diekspor ${platformTimestamp()}")
    entries.forEach { entry ->
        appendLine("${entry.time} ${entry.tag} ${entry.message}")
    }
}

/** Timestamped file name for exports, e.g. `unlimited-cloud-log-14-03-11.txt`. */
fun logExportFileName(): String = "unlimited-cloud-log-${platformTimestamp().replace(':', '-')}.txt"
