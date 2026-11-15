package dev.tobins.mealplanner.app

import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp

/** Thickness of the ring, and the gap between it and the control it surrounds. */
private val RingWidth = 3.dp
private val RingGap = 2.dp

/** The corners are rounded like the control up to this, so a round icon button gets a circle and a wide button a pill. */
private val RingCorner = 20.dp

/**
 * Draws a ring in the theme's primary colour around whichever control has keyboard focus.
 * Material's own focus cue is a faint tint of the control, barely visible on a light screen;
 * the ring sits outside the control, so it also shows on one filled with the primary colour.
 * It is meant for the root of the app: the ring is found through the focus system, so every
 * clickable, button, switch and text field gets it without changes at its call site, and it
 * only appears when focus came from a keyboard (a tap does not focus a control).
 */
@Composable
fun Modifier.focusRing(): Modifier {
    val color = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf<Rect?>(null) }
    return onFocusedBoundsChanged { focused = it?.takeIf { coordinates -> coordinates.isAttached }?.boundsInRoot() }
        .drawWithContent {
            drawContent()
            focused?.let { bounds ->
                val rect = bounds.inflate((RingGap + RingWidth / 2).toPx())
                drawRoundRect(
                    color = color,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(minOf(RingCorner.toPx(), rect.minDimension / 2)),
                    style = Stroke(RingWidth.toPx()),
                )
            }
        }
}
