package com.emirrkls.phokarta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One continuous 0–10 control. Threshold feedback is supplied by the caller so
 * product haptics can evolve independently from the visual implementation.
 */
@Composable
fun RatingControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    valueRange: ClosedFloatingPointRange<Float> = 0f..10f,
    onValueChangeFinished: () -> Unit = {},
    onThresholdCrossed: (Int) -> Unit = {},
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val normalized = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    val thumbRing = MaterialTheme.colorScheme.surface
    val thumbOutline = MaterialTheme.colorScheme.outline

    fun updateFromPosition(x: Float) {
        val fraction = (x / widthPx).coerceIn(0f, 1f)
        val raw = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
        val rounded = (raw * 10f).roundToInt() / 10f
        if (rounded.toInt() != value.toInt()) onThresholdCrossed(rounded.toInt())
        onValueChange(rounded)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(if (compact) 38.dp else 54.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, 99)
                setProgress { target ->
                    val rounded = (target.coerceIn(valueRange.start, valueRange.endInclusive) * 10f).roundToInt() / 10f
                    onValueChange(rounded)
                    true
                }
            }
            .pointerInput(valueRange, onValueChange) {
                detectTapGestures(
                    onPress = { position ->
                        updateFromPosition(position.x)
                        tryAwaitRelease()
                        onValueChangeFinished()
                    }
                )
            }
            .pointerInput(valueRange, onValueChange) {
                detectDragGestures(
                    onDragStart = { position -> updateFromPosition(position.x) },
                    onDragEnd = onValueChangeFinished,
                    onDragCancel = onValueChangeFinished,
                    onDrag = { change, _ ->
                        change.consume()
                        updateFromPosition(change.position.x)
                    },
                )
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val trackHeight = (if (compact) 6.dp else 10.dp).toPx()
            val centerY = size.height / 2f
            val radius = trackHeight / 2f
            drawRoundRect(
                color = inactive,
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(radius, radius),
            )
            val activeWidth = size.width * normalized
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = active,
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            val thumbCenter = Offset(activeWidth.coerceIn(0f, size.width), centerY)
            val outerRadius = (if (compact) 12.dp else 16.dp).toPx()
            val innerRadius = (if (compact) 8.dp else 11.dp).toPx()
            drawCircle(thumbOutline, outerRadius, thumbCenter)
            drawCircle(thumbRing, outerRadius - 2.dp.toPx(), thumbCenter)
            drawCircle(active, innerRadius, thumbCenter)
        }
    }
}
