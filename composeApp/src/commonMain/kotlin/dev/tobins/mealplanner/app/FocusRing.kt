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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
 * clickable, button, switch and text field gets it without changes at its call site. It only shows
 * while the keyboard is what the user last used: a tap does not focus a control, but a mouse click
 * does, and the ring would stay on the row, chip or tab that was clicked. It is kept inside the window,
 * so that a control at the window's edge (the bottom tabs) does not have it cut off.
 */
@Composable
fun Modifier.focusRing(): Modifier {
    val color = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf<Rect?>(null) }
    var byKeyboard by remember { mutableStateOf(false) }
    return onFocusedBoundsChanged { focused = it?.takeIf { coordinates -> coordinates.isAttached }?.boundsInRoot() }
        // Only watching: neither a key nor a pointer event is consumed here.
        .onPreviewKeyEvent {
            byKeyboard = true
            false
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // A press, not a move: the mouse passing over the window does not hide a ring the keyboard put there.
                    if (event.type == PointerEventType.Press) byKeyboard = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            focused?.takeIf { byKeyboard }?.let { bounds ->
                val inside = Rect(Offset.Zero, size).deflate(RingWidth.toPx() / 2)
                val wanted = bounds.inflate((RingGap + RingWidth / 2).toPx())
                val rect = Rect(maxOf(wanted.left, inside.left), maxOf(wanted.top, inside.top), minOf(wanted.right, inside.right), minOf(wanted.bottom, inside.bottom))
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
