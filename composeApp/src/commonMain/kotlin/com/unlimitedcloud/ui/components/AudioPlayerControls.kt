package com.unlimitedcloud.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Transport card shared by the Android and desktop audio previews: one round
 * play button next to a slim seek bar and the elapsed time.
 *
 * The bar is drawn instead of using `Slider` so the row stays compact: the
 * Material slider brings a 20dp thumb and a thick track, which made the preview
 * look clumsy inside a dialog. Dragging only previews the position and seeks
 * once the finger/mouse is released, because seeking on every pixel of movement
 * stutters playback.
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
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragFraction != null && durationMs > 0) {
        (fraction * durationMs).toInt()
    } else {
        positionMs
    }

    // Hoisted out of the draw lambda: MaterialTheme is only readable while
    // composing.
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    // One slim row instead of a card with a large button, a separate time row
    // and a tall track: the preview now reads as a player strip, not a panel.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                onClick = onTogglePlay,
                enabled = enabled,
                shape = CircleShape,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (playing) "Jeda audio" else "Putar audio",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(enabled, durationMs) {
                            if (!enabled || durationMs <= 0) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var dragged = fractionAt(down.position.x, size.width.toFloat())
                                dragFraction = dragged
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    dragged = fractionAt(change.position.x, size.width.toFloat())
                                    dragFraction = dragged
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                onSeek((dragged * durationMs).toInt().coerceIn(0, durationMs))
                                dragFraction = null
                            }
                        },
                ) {
                    val centerY = size.height / 2f
                    val barHeight = 2.5.dp.toPx()
                    val thumbRadius = 4.5.dp.toPx()
                    val top = centerY - barHeight / 2f
                    val radius = CornerRadius(barHeight / 2f)
                    val active = fraction.coerceIn(0f, 1f)

                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, barHeight),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, top),
                        size = Size(size.width * active, barHeight),
                        cornerRadius = radius,
                    )
                    if (enabled && size.width > thumbRadius * 2) {
                        drawCircle(
                            color = accent,
                            radius = thumbRadius,
                            center = Offset(
                                x = (size.width * active).coerceIn(thumbRadius, size.width - thumbRadius),
                                y = centerY,
                            ),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatClock(shownMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = formatClock(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun fractionAt(x: Float, width: Float): Float =
    if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

private fun formatClock(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
