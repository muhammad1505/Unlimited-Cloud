package com.unlimitedcloud.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.ui.theme.BrandGradient
import com.unlimitedcloud.ui.util.initials

/** The app mark: a gradient squircle with a cloud and an upload arrow. */
@Composable
fun BrandMark(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val cornerRadius = size * 0.28f
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val corner = cornerRadius.toPx()

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = BrandGradient,
                start = Offset.Zero,
                end = Offset(w, h),
            ),
            cornerRadius = CornerRadius(corner, corner),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = 1.dp.toPx()),
        )

        val cloud = Path().apply {
            moveTo(w * 0.28f, h * 0.70f)
            cubicTo(w * 0.16f, h * 0.70f, w * 0.12f, h * 0.58f, w * 0.20f, h * 0.50f)
            cubicTo(w * 0.21f, h * 0.38f, w * 0.32f, h * 0.30f, w * 0.44f, h * 0.34f)
            cubicTo(w * 0.52f, h * 0.22f, w * 0.70f, h * 0.23f, w * 0.77f, h * 0.37f)
            cubicTo(w * 0.89f, h * 0.38f, w * 0.95f, h * 0.49f, w * 0.90f, h * 0.59f)
            cubicTo(w * 0.87f, h * 0.67f, w * 0.80f, h * 0.70f, w * 0.72f, h * 0.70f)
            close()
        }
        drawPath(cloud, Color.White)

        val arrow = Path().apply {
            moveTo(w * 0.50f, h * 0.40f)
            lineTo(w * 0.64f, h * 0.55f)
            lineTo(w * 0.555f, h * 0.55f)
            lineTo(w * 0.555f, h * 0.66f)
            lineTo(w * 0.445f, h * 0.66f)
            lineTo(w * 0.445f, h * 0.55f)
            lineTo(w * 0.36f, h * 0.55f)
            close()
        }
        drawPath(arrow, Color(0xFF312E81))
    }
}

/** Initial-based avatar with the same brand gradient as the mark. */
@Composable
fun AppAvatar(
    name: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(BrandGradient)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
