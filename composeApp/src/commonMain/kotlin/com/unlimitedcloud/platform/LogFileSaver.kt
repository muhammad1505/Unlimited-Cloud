package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/** Outcome of an export attempt from the log screen. */
sealed interface LogExportResult {
    /** Text was written; [location] is a filesystem path when the platform exposes one. */
    data class Saved(val location: String?) : LogExportResult

    /** The user dismissed the save dialog. */
    data object Cancelled : LogExportResult

    data class Failed(val message: String) : LogExportResult
}

/**
 * Returns a callback that asks the user where to put a text file and writes it there.
 *
 * Each platform shows its own dialog instead of writing into the app directory,
 * so the file ends up somewhere the user can actually reach: SAF `CreateDocument`
 * on Android (e.g. Download) and `JFileChooser` on desktop.
 */
@Composable
expect fun rememberLogFileSaver(
    onResult: (LogExportResult) -> Unit,
): (fileName: String, content: String) -> Unit
