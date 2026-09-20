package com.emilyhsu.innercircle.ui.web

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.ui.components.InnerCircleLogo
import com.emilyhsu.innercircle.ui.theme.Ic
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the floating button currently is, shared with [exitSwipe] without triggering recomposition. */
class ExitButtonBounds {
    var rect: Rect? = null
}

private val BUTTON_SIZE = 52.dp
private val BUTTON_MARGIN = 8.dp

/** A swipe must start in this left fraction of the screen... */
private const val SWIPE_START_ZONE = 0.22f

/** ...and travel this fraction of the screen width, mostly sideways, to count. */
private const val SWIPE_DISTANCE = 0.40f

/**
 * The draggable "back to InnerCircle" button. Tap to leave; drag to move it, and it settles against
 * the nearest side and remembers where it was left ([onPositionSaved] gets fractions of the free space).
 *
 * [areaWidthPx] / [areaHeightPx] are the space it can move in; [savedPosition] gives the last saved
 * position as fractions (0..1).
 */
@Composable
fun FloatingExitButton(
    areaWidthPx: Float,
    areaHeightPx: Float,
    savedPosition: () -> Pair<Float, Float>,
    onPositionSaved: (x: Float, y: Float) -> Unit,
    onExit: () -> Unit,
    bounds: ExitButtonBounds? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { BUTTON_SIZE.toPx() }
    val marginPx = with(density) { BUTTON_MARGIN.toPx() }
    val maxX = (areaWidthPx - sizePx).coerceAtLeast(0f)
    val maxY = (areaHeightPx - sizePx).coerceAtLeast(0f)
    val scope = rememberCoroutineScope()
    val latestExit by rememberUpdatedState(onExit)

    // Re-derived if the available space changes (rotation), so the button never ends up off-screen.
    val position = remember(maxX, maxY) {
        val (fx, fy) = savedPosition()
        val x = if (fx >= 0.5f) maxX - marginPx else marginPx
        Animatable(Offset(x.coerceIn(0f, maxX), (fy * maxY).coerceIn(0f, maxY)), Offset.VectorConverter)
    }

    Box(
        modifier
            .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
            .size(BUTTON_SIZE)
            .onGloballyPositioned { bounds?.rect = it.boundsInParent() }
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = "Back to InnerCircle"
                role = Role.Button
                onClick(label = "Go back to InnerCircle") { latestExit(); true }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { latestExit() }) }
            .pointerInput(maxX, maxY) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        val next = Offset(
                            (position.value.x + drag.x).coerceIn(0f, maxX),
                            (position.value.y + drag.y).coerceIn(0f, maxY),
                        )
                        scope.launch { position.snapTo(next) }
                    },
                    onDragEnd = {
                        scope.launch {
                            val onRight = position.value.x + sizePx / 2f >= areaWidthPx / 2f
                            val settleX = if (onRight) maxX - marginPx else marginPx
                            position.animateTo(
                                Offset(settleX.coerceIn(0f, maxX), position.value.y),
                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                            )
                            onPositionSaved(if (onRight) 1f else 0f, if (maxY > 0f) position.value.y / maxY else 0f)
                        }
                    },
                )
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        InnerCircleLogo(size = BUTTON_SIZE)
    }
}

/**
 * Detects "swipe right to leave" without getting in the way: it only *watches* touches (on the
 * initial pass, never consuming them) so taps, scrolls and Instagram's own gestures keep working.
 * It reacts only to a single finger that starts in the left [SWIPE_START_ZONE] of the screen and
 * moves mostly sideways to the right. [onProgress] reports 0..1 toward the trigger so the UI can
 * show a hint, and [onExit] fires once when the swipe is long enough.
 */
fun Modifier.exitSwipe(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onExit: () -> Unit,
    ignoreStartsOn: ExitButtonBounds? = null,
): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val startZone = size.width * SWIPE_START_ZONE
        val threshold = size.width * SWIPE_DISTANCE
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // A drag that begins on the floating button is the user moving it, not leaving.
                val onButton = ignoreStartsOn?.rect?.contains(down.position) == true
                var tracking = down.position.x <= startZone && !onButton
                val origin = down.position
                var finished = false
                while (!finished) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        finished = true
                    } else if (tracking) {
                        val dx = change.position.x - origin.x
                        val dy = abs(change.position.y - origin.y)
                        when {
                            event.changes.size > 1 -> tracking = false            // a second finger: pinch/zoom
                            dx < -slop -> tracking = false                        // went left: not our gesture
                            max(dx, dy) > slop * 2 && dy > 0.6f * dx.coerceAtLeast(1f) -> tracking = false // vertical scroll
                            else -> {
                                onProgress(((dx - slop * 2) / (threshold - slop * 2)).coerceIn(0f, 1f))
                                if (dx >= threshold) {
                                    event.changes.forEach { it.consume() }        // don't let it click through
                                    tracking = false
                                    finished = true
                                    onProgress(0f)
                                    onExit()
                                }
                            }
                        }
                    }
                }
                onProgress(0f)
            }
        }
    }

/**
 * The bubble that slides in from the left edge while a leave-swipe is in progress. It is fully on
 * screen by about two thirds of the way to the trigger. A ring rather than a shadow: a shadow inside
 * a fading layer gets clipped to a visible rectangle.
 */
@Composable
fun SwipeExitHint(progress: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val eased = (progress * 1.5f).coerceIn(0f, 1f)
    Box(
        modifier
            .offset { IntOffset(with(density) { (-52.dp.toPx() + eased * 76.dp.toPx()).roundToInt() }, 0) }
            .graphicsLayer { alpha = eased }
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .border(1.dp, Ic.Divider, CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        InnerCircleLogo(size = BUTTON_SIZE)
    }
}
