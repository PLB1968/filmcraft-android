package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcType
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Media being dragged onto the timeline from the Project panel or the Source monitor: project
 * item [item] ([duration] long, its Source In to Out when it has one), the finger at [at] (window
 * coordinates).
 */
data class MediaDrag(val item: ULong, val label: String, val duration: Long, val at: Offset)

/** Where a drop lands: [track] at [time] (frame-exact, snapped with Snap on). */
data class DropSpot(val track: ULong, val time: Long)

/** Holds a layout's coordinates for code that runs outside composition (gestures, drops). */
class CoordsHolder {
    var coords: LayoutCoordinates? = null
}

/**
 * A Project panel row or the Source monitor's picture, as a drag source: a tap runs [onTap]; a
 * long press then a drag picks up [drag] (the editor's host follows the finger and drops it on
 * the timeline); a long press let go where it was runs [onHold]. A drag without the long press
 * is left to what holds this (a list scrolls).
 */
@Composable
internal fun Modifier.mediaDragSource(
    ed: EditorController,
    enabled: Boolean,
    drag: () -> MediaDrag?,
    onTap: () -> Unit,
    onHold: () -> Unit,
): Modifier {
    val here = remember { CoordsHolder() }
    val haptic = LocalHapticFeedback.current
    // the gesture outlives a recomposition: it calls these as they are now
    val dragNow by rememberUpdatedState(drag)
    val tapNow by rememberUpdatedState(onTap)
    val holdNow by rememberUpdatedState(onHold)
    return onGloballyPositioned { here.coords = it }.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            val slop = viewConfiguration.touchSlop
            // before the long press: a tap, or a scroll (not ours)
            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var r = "up"
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    if ((c.position - down.position).getDistance() > slop) {
                        r = "moved"
                        break
                    }
                }
                r
            }
            when (early) {
                "up" -> tapNow()
                "moved" -> {}
                else -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    // held: a drag picks it up, letting go opens the menu
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) {
                            holdNow()
                            break
                        }
                        if ((c.position - down.position).getDistance() > slop) {
                            val d = dragNow()
                            val at = here.coords?.takeIf { it.isAttached }?.localToWindow(c.position)
                            if (d != null && at != null) {
                                c.consume()
                                ed.startMediaDrag(d.copy(at = at))
                            }
                            break
                        }
                    }
                }
            }
        }
    }
}

/**
 * The editor's root: follows a media drag wherever the finger goes (its source may have left the
 * screen, as the Project tab gives way to the Timeline) and drops it when the finger lifts.
 */
internal fun Modifier.mediaDragHost(ed: EditorController, host: CoordsHolder): Modifier =
    onGloballyPositioned { host.coords = it }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val d = ed.mediaDrag
                val c = ev.changes.firstOrNull()
                if (d != null && c != null) {
                    host.coords?.takeIf { it.isAttached }?.let { ed.moveMediaDrag(it.localToWindow(c.position)) }
                    // the timeline under the finger doesn't scroll meanwhile
                    ev.changes.forEach { it.consume() }
                }
                if (ev.changes.none { it.pressed }) {
                    if (ed.mediaDrag != null) ed.dropMedia()
                    break
                }
            }
        }
    }

/** What's being dragged, under the finger: its name on a pill. */
@Composable
internal fun MediaDragGhost(ed: EditorController, host: CoordsHolder) {
    val d = ed.mediaDrag ?: return
    val coords = host.coords?.takeIf { it.isAttached } ?: return
    val local = coords.windowToLocal(d.at)
    val density = LocalDensity.current
    val lift = with(density) { 56.dp.toPx() }
    val over = ed.dropProbe?.invoke(d.at) != null
    Box(
        Modifier
            .offset { IntOffset((local.x - with(density) { 60.dp.toPx() }).roundToInt(), (local.y - lift).roundToInt()) }
            .widthIn(max = 200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (over) Fc.Accent else Fc.Raised)
            .border(1.dp, Fc.AccentLight, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(d.label, style = FcType.Label, color = Fc.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
