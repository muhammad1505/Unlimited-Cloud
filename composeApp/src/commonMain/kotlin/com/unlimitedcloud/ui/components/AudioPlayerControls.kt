package com.unlimitedcloud.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Transport row shared by the Android and desktop audio previews: one round
 * play button and a seek bar with the elapsed time under it.
 *
 * Dragging only updates a local fraction and seeks once the finger/mouse is
 * released, because seeking on every pixel of movement stutters playback.
 */
@Composable
fun AudioPlayerControls(
    positionMs: Int,
    durationMs: Int,
    playing: Boolean,
    enabled: Boolean,
    onSeek: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember(durationMs) { mutableStateOf<Float?>(null) }
    val progress = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragFraction != null && durationMs > 0) {
        (progress * durationMs).toInt()
    } else {
        positionMs
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledIconButton(
            onClick = onTogglePlay,
            enabled = enabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = if (playing) AppIcons.Pause else AppIcons.Play,
                contentDescription = if (playing) "Jeda audio" else "Putar audio",
                modifier = Modifier.size(26.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = progress,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    val fraction = dragFraction
                    dragFraction = null
                    if (fraction != null && durationMs > 0) {
                        onSeek((fraction * durationMs).toInt().coerceIn(0, durationMs))
                    }
                },
                enabled = enabled && durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatClock(shownMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatClock(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatClock(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
