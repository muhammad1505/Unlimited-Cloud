package com.unlimitedcloud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.log.LogEntry
import com.unlimitedcloud.log.formatLogForExport
import com.unlimitedcloud.log.logExportFileName
import com.unlimitedcloud.log.platformCopyToClipboard
import com.unlimitedcloud.platform.LogExportResult
import com.unlimitedcloud.platform.rememberLogFileSaver
import kotlinx.coroutines.delay

/**
 * In-app view of [com.unlimitedcloud.log.LogStore]: the same lines that go to
 * logcat, so a user without `adb` can still see what the app did. Only the
 * app's own steps reach this list -- Telegram traffic such as incoming messages
 * is never echoed, so what is shown here is the app, not the account.
 *
 * The list follows the tail automatically; newest line is at the bottom, which
 * is where the eye lands when something just failed. Copy and export use the
 * same plain-text rendering, so a pasted log and a saved log match.
 */
@Composable
fun LogScreen(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var feedback by remember { mutableStateOf<String?>(null) }

    val saveLog = rememberLogFileSaver { result ->
        feedback = when (result) {
            is LogExportResult.Saved -> result.location?.let { "Log disimpan ke $it" } ?: "Log disimpan"
            LogExportResult.Cancelled -> null
            is LogExportResult.Failed -> "Gagal menyimpan log: ${result.message}"
        }
    }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    // Feedback is a transient confirmation, not a permanent banner.
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(4000)
            feedback = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Kembali") }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = "Log diagnostik",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${entries.size} baris terakhir · hanya di perangkat ini",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    feedback = if (platformCopyToClipboard(formatLogForExport(entries))) {
                        "Log disalin ke clipboard"
                    } else {
                        "Clipboard tidak tersedia di perangkat ini"
                    }
                },
                enabled = entries.isNotEmpty(),
            ) {
                Text("Salin")
            }
            TextButton(
                onClick = { saveLog(logExportFileName(), formatLogForExport(entries)) },
                enabled = entries.isNotEmpty(),
            ) {
                Text("Ekspor")
            }
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                Text("Bersihkan")
            }
        }

        if (feedback != null) {
            Text(
                text = feedback.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Belum ada log. Login, unggah, unduh, pembuatan topik, dan " +
                            "kesalahan akan muncul di sini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    itemsIndexed(entries) { index, entry ->
                        LogRow(index = index, entry = entry)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LogRow(index: Int, entry: LogEntry) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.time,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(66.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "${entry.tag} · #${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
