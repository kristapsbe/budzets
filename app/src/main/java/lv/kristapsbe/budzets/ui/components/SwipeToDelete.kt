package lv.kristapsbe.budzets.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Past this share of the row's width a swipe deletes. Stricter than Material's 56 dp, because a
 * swipe meant to change month can land on a row.
 */
private const val DELETE_FRACTION = 0.4f

/** Or a fling this fast after [MIN_FLING_DISTANCE]. Material's is 125 dp/s, nearly any flick. */
private val MIN_FLING_SPEED = 1000.dp
private val MIN_FLING_DISTANCE = 56.dp

/**
 * A row that deletes when swiped toward the start.
 *
 * Not Material's `SwipeToDismissBox`, which claims a drag in either direction even with one turned
 * off. A swipe toward the end has to reach the [MonthPager] around the list, so this claims only a
 * move toward the start and leaves the rest unconsumed.
 *
 * @param enabled false claims nothing, so a swipe on this row changes month in both directions.
 * @param confirmsDelete [onDelete] asks first rather than deleting, so the row slides back behind
 *   the dialog. Otherwise it stays off screen until the list drops it.
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    confirmsDelete: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Plain remember, not saved: a restored (undone) row must come back settled, not with a saved
    // position that would hide it again.
    val offset = remember { Animatable(0f) }
    val swiping by remember { derivedStateOf { offset.value != 0f } }
    val scope = rememberCoroutineScope()
    val currentOnDelete by rememberUpdatedState(onDelete)
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1f else 1f

    Box(
        modifier.pointerInput(enabled, towardStart, confirmsDelete) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                // Not requiring it unconsumed: the row's own click consumes the down.
                val down = awaitFirstDown(requireUnconsumed = false)
                val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    if (overSlop * towardStart > 0f) change.consume()
                } ?: return@awaitEachGesture

                val width = size.width.toFloat()
                val velocity = VelocityTracker()
                velocity.addPointerInputChange(first)
                var distance = (first.positionChangeIgnoreConsumed().x * towardStart).coerceIn(0f, width)
                val finished = followToLift(first.id) { change ->
                    // Includes the lift: that's how the tracker learns the finger had stopped.
                    velocity.addPointerInputChange(change)
                    val moved = change.positionChange().x
                    if (moved != 0f) {
                        distance = (distance + moved * towardStart).coerceIn(0f, width)
                        scope.launch { offset.snapTo(distance * towardStart) }
                    }
                }

                val speed = velocity.calculateVelocity().x * towardStart
                val delete = finished &&
                    (distance >= width * DELETE_FRACTION || (distance >= MIN_FLING_DISTANCE.toPx() && speed >= MIN_FLING_SPEED.toPx()))
                scope.launch {
                    if (delete) {
                        offset.animateTo(width * towardStart, tween(DISMISS_MILLIS))
                        currentOnDelete()
                        if (confirmsDelete) offset.animateTo(0f)
                    } else {
                        offset.animateTo(0f)
                    }
                }
            }
        },
    ) {
        if (swiping) {
            Row(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/**
 * Follows a finger until it lifts, consuming everything it reports, whether it moved or not.
 *
 * Not `horizontalDrag`, which leaves any change with no sideways movement unconsumed, and a resting
 * finger reports little else. The [MonthPager] and the list around a row never give up a drag they
 * lost: one unconsumed change hands it to them, measured from where the finger first came down, so
 * the page would jump by everything the row had travelled. `drag` isn't enough either, because a
 * change with no movement at all still arrives once the row has shifted under the finger.
 *
 * @param onChange sees every change for the finger, including the lift.
 * @return true when the last finger lifted, false if something else took the gesture.
 */
private suspend fun AwaitPointerEventScope.followToLift(pointerId: PointerId, onChange: (PointerInputChange) -> Unit): Boolean {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointer } ?: return false
        if (change.isConsumed) return false
        onChange(change)
        if (change.changedToUpIgnoreConsumed()) {
            pointer = event.changes.firstOrNull { it.pressed }?.id ?: return true
        }
        // Every finger: a second one left unconsumed would hand the gesture over just the same.
        event.changes.forEach { it.consume() }
    }
}

private const val DISMISS_MILLIS = 200
