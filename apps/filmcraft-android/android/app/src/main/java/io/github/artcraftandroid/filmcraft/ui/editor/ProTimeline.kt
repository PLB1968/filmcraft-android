package io.github.artcraftandroid.filmcraft.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.filmcraft.core.ClipKind
import io.github.artcraftandroid.filmcraft.core.ProClip
import io.github.artcraftandroid.filmcraft.core.ProMarker
import io.github.artcraftandroid.filmcraft.core.ProTimeline
import io.github.artcraftandroid.filmcraft.core.ProTrack
import io.github.artcraftandroid.filmcraft.core.TrackSwitches
import io.github.artcraftandroid.filmcraft.core.markerColors
import io.github.artcraftandroid.filmcraft.ui.DialogButton
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcDialog
import io.github.artcraftandroid.filmcraft.ui.FcType
import io.github.artcraftandroid.filmcraft.ui.Glyph
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import io.github.artcraftandroid.filmcraft.ui.Inter
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.glyph
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The smallest touch target Pro mode draws. */
private val Touch = 44.dp

/** The marker label colours, by the engine's names (Premiere's label palette). */
internal fun markerColor(name: String): Color = when (name) {
    "Violet" -> Color(0xFFA48BE0)
    "Iris" -> Color(0xFF8E9BFF)
    "Caribbean" -> Color(0xFF1FC5A5)
    "Lavender" -> Color(0xFFE59AE8)
    "Cerulean" -> Color(0xFF3FA9F5)
    "Forest" -> Color(0xFF4F9A43)
    "Rose" -> Color(0xFFF56B8C)
    "Mango" -> Color(0xFFF9A43A)
    "Purple" -> Color(0xFF8E44D6)
    "Blue" -> Color(0xFF3A6FF7)
    "Teal" -> Color(0xFF2CB1C4)
    "Magenta" -> Color(0xFFE23DB1)
    "Tan" -> Color(0xFFCBB08E)
    "Green" -> Color(0xFF5BC236)
    "Brown" -> Color(0xFF9C6B3E)
    "Yellow" -> Color(0xFFE8D33F)
    else -> Color(0xFF5BC236)
}

/** A clip's fill and label colours in Pro mode (the desktop's: video violet-blue, audio green). */
private fun proClipColors(kind: ClipKind): Pair<Color, Color> = when (kind) {
    ClipKind.VIDEO -> Color(0xFF4A5BB5) to Color(0xFFE6E9FF)
    ClipKind.IMAGE -> Color(0xFF8A5BB5) to Color(0xFFF3E6FF)
    ClipKind.TEXT -> Fc.TextClip.fill to Fc.TextClip.content
    ClipKind.AUDIO -> Color(0xFF2E8A4E) to Color(0xFFE2FFE9)
    ClipKind.BLANK -> Fc.BlankClip.fill to Fc.BlankClip.content
    ClipKind.MISSING -> Fc.MissingClip.fill to Fc.MissingClip.content
}

/** A track row as laid out: the track and its place in the stack (px). */
private data class ProRow(val track: ProTrack, val top: Float, val height: Float) {
    val bottom: Float get() = top + height
}

/** A context menu (long press, the desktop's right-click) at [at] (px in the panel). */
private data class ProMenu(val at: Offset, val items: List<Pair<String, () -> Unit>>)

/**
 * Pro mode's timeline panel: a bar of timeline switches, the timecode ruler with In/Out and
 * markers, and the tracks: V1, V2… above A1, A2…, each with its header. One finger pans (or moves a
 * selected clip), two pinch to zoom, a long press opens a context menu.
 */
@Composable
fun ProTimelinePanel(ed: EditorController, compact: Boolean, modifier: Modifier = Modifier) {
    val p = ed.pro
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf<ProMenu?>(null) }
    var renaming by remember { mutableStateOf<ProTrack?>(null) }
    var editingMarker by remember { mutableStateOf<ProMarker?>(null) }
    // the tracks' vertical scroll (px)
    var vScroll by remember { mutableFloatStateOf(0f) }
    val header = if (compact) 132.dp else 216.dp
    Box(modifier.background(Fc.Black).testTag("pro_timeline")) {
        Column(Modifier.fillMaxSize()) {
            TimelineBar(ed, p) { items -> menu = ProMenu(Offset(with(density) { 160.dp.toPx() }, 0f), items) }
            if (p == null) return@Column
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val headerPx = with(density) { header.toPx() }
                val touchPx = with(density) { Touch.toPx() }
                val viewport = max(1f, with(density) { maxWidth.toPx() } - headerPx)
                val visible = max(0f, with(density) { maxHeight.toPx() } - touchPx)
                val num = p.rateNum
                val den = p.rateDen
                val span = max(p.duration, 10 * TPS)
                // no zoom yet (or Zoom to Sequence): the whole sequence across the view
                if (ed.proPxPerSecond <= 0f) {
                    SideEffect { ed.proPxPerSecond = TimelineMath.clampZoom((viewport * 0.92f / (span.toDouble() / TPS)).toFloat(), viewport, span, num, den) }
                }
                // the view is read when used, so a gesture that outlives a frame sees where it is now
                fun pps(): Float = ed.proPxPerSecond.takeIf { it > 0f } ?: (viewport / (span.toFloat() / TPS))
                fun xOf(t: Long): Float = ((t - ed.proScroll).toDouble() / TPS * pps()).toFloat()
                fun tOf(x: Float): Long = (ed.proScroll + x / pps() * TPS).roundToLong().coerceAtLeast(0L)
                fun setScroll(t: Long) {
                    ed.proScroll = t.coerceIn(0L, max(0L, p.duration))
                }
                fun zoom(k: Float, anchorX: Float) {
                    val keep = tOf(anchorX)
                    val np = TimelineMath.clampZoom(pps() * k, viewport, span, num, den)
                    ed.proPxPerSecond = np
                    setScroll((keep - anchorX / np * TPS).roundToLong())
                }
                // playback pages the view along with the playhead
                if (ed.playing) {
                    val x = xOf(ed.playhead)
                    if (x < 0f || x > viewport * 0.92f) SideEffect { setScroll(ed.playhead - (viewport * 0.1f / pps() * TPS).roundToLong()) }
                }
                val gap = with(density) { 6.dp.toPx() }
                val rows = run {
                    var y = 0f
                    val out = ArrayList<ProRow>()
                    for (t in p.videoTracks.asReversed() + p.audioTracks) {
                        val dp = ed.trackHeights[t.id] ?: if (t.video) PRO_VIDEO_TRACK else PRO_AUDIO_TRACK
                        val h = with(density) { dp.dp.toPx() }
                        // the gap between video and audio
                        if (!t.video && t.index == 0u) y += gap
                        out += ProRow(t, y, h)
                        y += h
                    }
                    out
                }
                val stackHeight = rows.lastOrNull()?.bottom ?: 0f
                val maxV = max(0f, stackHeight - visible)
                if (vScroll > maxV) SideEffect { vScroll = maxV }
                fun panV(dy: Float) {
                    vScroll = (vScroll - dy).coerceIn(0f, maxV)
                }
                // where the tracks area starts in this panel
                val tracksTop = touchPx * 2
                Column(Modifier.fillMaxSize()) {
                    // the ruler, under the header column's corner
                    Row(Modifier.fillMaxWidth().height(Touch)) {
                        Box(Modifier.width(header).fillMaxHeight().background(Fc.Panel).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(inOutLabel(p), style = FcType.Badge, color = Fc.Secondary, maxLines = 1)
                        }
                        Ruler(
                            ed, p, ::xOf, ::tOf,
                            onMenu = { at, t ->
                                val near = with(density) { 16.dp.toPx() }
                                val marker = p.markers.minByOrNull { abs(xOf(it.start) - at.x) }?.takeIf { abs(xOf(it.start) - at.x) < near }
                                if (marker != null) {
                                    editingMarker = marker
                                } else {
                                    menu = ProMenu(
                                        at + Offset(headerPx, touchPx),
                                        listOf(
                                            "Mark In" to { ed.markIn(t) },
                                            "Mark Out" to { ed.markOut(t) },
                                            "Add Marker" to { ed.addMarker(t) },
                                            "Clear In and Out" to { ed.clearInOut() },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    Row(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                        Box(Modifier.width(header).fillMaxHeight().clipToBounds()) {
                            Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).offset { IntOffset(0, -vScroll.roundToInt()) }) {
                                for (r in rows) {
                                    if (!r.track.video && r.track.index == 0u) Spacer(Modifier.height(6.dp).fillMaxWidth().background(Fc.Hairline))
                                    TrackHeader(
                                        ed, r.track, compact, with(density) { r.height.toDp() },
                                        onPan = ::panV,
                                        onMenu = { at -> menu = ProMenu(at + Offset(0f, tracksTop + r.top - vScroll), headerMenu(ed, p, r.track, compact) { renaming = it }) },
                                    )
                                }
                            }
                        }
                        Tracks(
                            ed, p, rows, { vScroll }, ::xOf, ::tOf, ::pps,
                            onPan = { dx, dy ->
                                setScroll(ed.proScroll - (dx / pps() * TPS).roundToLong())
                                panV(dy)
                            },
                            onZoom = ::zoom,
                            onMenu = { at, clip ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = ProMenu(at + Offset(headerPx, tracksTop), if (clip != null) clipMenu(ed, clip) else laneMenu(ed))
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        menu?.let { m ->
            Box(Modifier.offset { IntOffset(m.at.x.roundToInt(), m.at.y.roundToInt()) }.size(1.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }, containerColor = Fc.Panel) {
                    for ((label, action) in m.items) {
                        DropdownMenuItem(
                            text = { Text(label, style = FcType.Body, color = Fc.Body) },
                            onClick = {
                                menu = null
                                action()
                            },
                            modifier = Modifier.testTag("pro_menu_" + label.lowercase().replace(Regex("[^a-z0-9]+"), "_")),
                        )
                    }
                }
            }
        }
    }
    renaming?.let { t ->
        RenameTrackDialog(t) { name ->
            renaming = null
            if (name != null) ed.setTrack(t.id, TrackSwitches(name = name))
        }
    }
    editingMarker?.let { m -> MarkerDialog(ed, m) { editingMarker = null } }
}

private fun inOutLabel(p: ProTimeline): String {
    val i = p.markIn
    val o = p.markOut
    return when {
        i != null && o != null && o > i -> "In/Out " + TimelineMath.timecode(o - i, p.rateNum, p.rateDen)
        i != null -> "In " + TimelineMath.timecode(i, p.rateNum, p.rateDen)
        o != null -> "Out " + TimelineMath.timecode(o, p.rateNum, p.rateDen)
        else -> ""
    }
}

/** The bar over the timeline: the playhead's timecode, Snap, Linked Selection, Add Marker, Add Tracks, zoom. */
@Composable
private fun TimelineBar(ed: EditorController, p: ProTimeline?, onMenu: (List<Pair<String, () -> Unit>>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Touch).background(Fc.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            TimelineMath.timecode(ed.playhead, ed.rateNum, ed.rateDen),
            style = TextStyle(fontFamily = Inter, fontSize = 18.sp, color = Fc.AccentLight),
            modifier = Modifier.padding(end = 12.dp).testTag("pro_timecode"),
        )
        val snapping = p?.snapping == true
        val linked = p?.linkedSelection == true
        BarToggle(ProGlyphs.Magnet, "Snap in Timeline", snapping, "pro_snap") { ed.setSnapping(!snapping) }
        BarToggle(ProGlyphs.Link, "Linked Selection", linked, "pro_linked_selection") { ed.setLinkedSelection(!linked) }
        BarButton(ProGlyphs.Marker, "Add Marker", "pro_add_marker") { ed.addMarker() }
        BarButton(LucideIcons.Plus.glyph(), "Add Tracks", "pro_add_tracks") {
            onMenu(listOf("Add Video Track" to { ed.addTracks(1, 0) }, "Add Audio Track" to { ed.addTracks(0, 1) }, "Add Video and Audio Track" to { ed.addTracks(1, 1) }))
        }
        BarButton(ProGlyphs.Fit, "Zoom to Sequence", "pro_zoom_fit") {
            ed.proPxPerSecond = 0f
            ed.proScroll = 0L
        }
        BarButton(ProGlyphs.MarkIn, "Mark In", "pro_mark_in") { ed.markIn() }
        BarButton(ProGlyphs.MarkOut, "Mark Out", "pro_mark_out") { ed.markOut() }
    }
}

@Composable
private fun BarToggle(glyph: Glyph, description: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Touch)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Fc.Accent.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(role = Role.Switch, onClick = onClick)
            .testTag(tag)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (on) Fc.AccentLight else Fc.Icon, size = 22.dp)
    }
}

@Composable
private fun BarButton(glyph: Glyph, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(Touch).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).testTag(tag).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Fc.Icon, size = 22.dp)
    }
}

/** The timecode ruler: tap or drag to move the playhead, long press for In/Out and markers. */
@Composable
private fun Ruler(
    ed: EditorController,
    p: ProTimeline,
    xOf: (Long) -> Float,
    tOf: (Float) -> Long,
    onMenu: (Offset, Long) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val tAt by rememberUpdatedState(tOf)
    val menuAt by rememberUpdatedState(onMenu)
    Canvas(
        modifier
            .background(Fc.Panel)
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val t0 = tAt(down.position.x)
                    val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var m = false
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) m = true
                            if (m) {
                                c.consume()
                                ed.seek(tAt(c.position.x))
                            }
                        }
                        m
                    }
                    when (moved) {
                        null -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuAt(down.position, t0)
                            // swallow the rest of the press
                            while (awaitPointerEvent().changes.any { it.pressed }) Unit
                        }
                        false -> ed.seek(t0, draft = false)
                        true -> ed.settle()
                    }
                }
            }
            .semantics { contentDescription = "Timecode ruler" },
    ) {
        val num = p.rateNum
        val den = p.rateDen
        val pps = ed.proPxPerSecond.takeIf { it > 0f } ?: 1f
        // In to Out
        val i = p.markIn
        val o = p.markOut
        if (i != null || o != null) {
            val x0 = i?.let(xOf) ?: 0f
            val x1 = o?.let(xOf) ?: size.width
            drawRect(Fc.Accent.copy(alpha = 0.35f), Offset(x0, size.height - 8.dp.toPx()), Size(max(0f, x1 - x0), 8.dp.toPx()))
        }
        val step = TimelineMath.rulerStep(pps, 96.dp.toPx(), num, den)
        val first = (ed.proScroll / step.ticks) * step.ticks
        var t = first
        val style = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = Fc.RulerLabel)
        while (true) {
            val x = xOf(t)
            if (x > size.width) break
            drawLine(Fc.RulerTick, Offset(x, size.height * 0.55f), Offset(x, size.height), 1.dp.toPx())
            val label = if (step.frames > 0) TimelineMath.rulerLabel(t, step, num, den) else TimelineMath.timecode(t, num, den)
            drawText(measurer, label, Offset(x + 3.dp.toPx(), 4.dp.toPx()), style, maxLines = 1)
            // minor ticks
            for (k in 1 until 4) {
                val mx = xOf(t + step.ticks * k / 4)
                drawLine(Fc.RulerTick.copy(alpha = 0.5f), Offset(mx, size.height * 0.78f), Offset(mx, size.height), 1.dp.toPx())
            }
            t += step.ticks
        }
        for (m in p.markers) drawMarker(xOf(m.start), xOf(m.start + m.duration), markerColor(m.color))
        // the playhead's head
        val px = xOf(ed.playhead)
        val head = Path().apply {
            moveTo(px - 7.dp.toPx(), size.height * 0.45f)
            lineTo(px + 7.dp.toPx(), size.height * 0.45f)
            lineTo(px + 7.dp.toPx(), size.height * 0.7f)
            lineTo(px, size.height)
            lineTo(px - 7.dp.toPx(), size.height * 0.7f)
            close()
        }
        drawPath(head, Fc.AccentLight)
    }
}

private fun DrawScope.drawMarker(x: Float, end: Float, c: Color) {
    val w = 6.dp.toPx()
    val top = size.height * 0.2f
    val path = Path().apply {
        moveTo(x - w, top)
        lineTo(x + w, top)
        lineTo(x + w, top + w * 1.4f)
        lineTo(x, top + w * 2.4f)
        lineTo(x - w, top + w * 1.4f)
        close()
    }
    drawPath(path, c)
    if (end > x + 1f) drawRect(c.copy(alpha = 0.5f), Offset(x, top), Size(end - x, w * 1.4f))
}

/** A track's header: targeting, source patching, lock, output or mute/solo, sync lock; drag its foot to resize. */
@Composable
private fun TrackHeader(ed: EditorController, t: ProTrack, compact: Boolean, height: androidx.compose.ui.unit.Dp, onPan: (Float) -> Unit, onMenu: (Offset) -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val menuAt by rememberUpdatedState(onMenu)
    val pan by rememberUpdatedState(onPan)
    val heightNow by rememberUpdatedState(height)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(if (t.targeted) Color(0xFF2A2F3A) else Fc.Panel)
            .border(0.5.dp, Fc.Hairline)
            .pointerInput(t.id) {
                // a long press opens the track's menu; a drag scrolls the tracks
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!c.pressed || c.isConsumed) break
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                break
                            }
                        }
                    }
                    when {
                        up == null -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuAt(down.position)
                            // before the buttons see it, so the release doesn't also press one
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                        }
                        moved -> while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            pan(c.positionChange().y)
                            c.consume()
                        }
                    }
                }
            }
            .testTag("pro_track_header_${t.code}"),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                // source patching: the Source monitor's V1 / A1 goes here when lit
                Box(
                    Modifier
                        .width(36.dp)
                        .height(Touch)
                        .clickable(role = Role.Switch) { ed.setSourcePatch(t.id, !t.sourcePatched) }
                        .testTag("pro_patch_${t.code}")
                        .semantics {
                            contentDescription = "Source patching for ${t.code}"
                            selected = t.sourcePatched
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(width = 26.dp, height = 22.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (t.sourcePatched) Fc.Accent else Color.Transparent)
                            .border(1.dp, if (t.sourcePatched) Fc.Accent else Fc.Divider, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (t.video) "V" else "A", style = FcType.Badge, color = if (t.sourcePatched) Color.White else Fc.Detail)
                    }
                }
            }
            // the track's name; tap to target it
            Box(
                Modifier
                    .width(if (compact) Touch else 48.dp)
                    .height(Touch)
                    .clickable(role = Role.Switch) { ed.setTargeted(t.id, !t.targeted) }
                    .testTag("pro_target_${t.code}")
                    .semantics {
                        contentDescription = "Target ${t.code}"
                        selected = t.targeted
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 38.dp, height = 24.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (t.targeted) Fc.AccentLight else Fc.Raised)
                        .then(if (compact && t.sourcePatched) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t.code, style = FcType.Caption, color = if (t.targeted) Color.White else Fc.Body)
                }
            }
            HeaderSwitch(if (t.locked) ProGlyphs.Lock else ProGlyphs.Unlock, "Lock ${t.code}", t.locked, "pro_lock_${t.code}") {
                ed.setTrack(t.id, TrackSwitches(locked = !t.locked))
            }
            if (t.video) {
                HeaderSwitch(if (t.output) ProGlyphs.Eye else ProGlyphs.EyeOff, "Toggle track output ${t.code}", !t.output, "pro_output_${t.code}") {
                    ed.setTrack(t.id, TrackSwitches(output = !t.output))
                }
            } else {
                HeaderLetter("M", "Mute ${t.code}", t.muted, Color(0xFF3FAE5A), "pro_mute_${t.code}") { ed.setTrack(t.id, TrackSwitches(muted = !t.muted)) }
            }
            if (!compact) {
                if (!t.video) HeaderLetter("S", "Solo ${t.code}", t.solo, Color(0xFFE8C33F), "pro_solo_${t.code}") { ed.setTrack(t.id, TrackSwitches(solo = !t.solo)) }
                HeaderSwitch(if (t.syncLock) ProGlyphs.SyncLock else ProGlyphs.SyncOff, "Sync lock ${t.code}", t.syncLock, "pro_sync_${t.code}") {
                    ed.setTrack(t.id, TrackSwitches(syncLock = !t.syncLock))
                }
            }
        }
        // the foot: drag to resize the track
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(10.dp)
                .pointerInput(t.id) {
                    var h = 0f
                    detectVerticalDragGestures(onDragStart = { h = with(density) { heightNow.toPx() } }) { change, dy ->
                        change.consume()
                        h += dy
                        ed.setTrackHeight(t.id, with(density) { h.toDp().value.roundToInt() })
                    }
                }
                .testTag("pro_resize_${t.code}")
                .semantics { contentDescription = "Resize ${t.code}" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 24.dp, height = 3.dp).clip(CircleShape).background(Fc.Divider))
        }
    }
}

@Composable
private fun HeaderSwitch(glyph: Glyph, description: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Touch)
            .clickable(role = Role.Switch, onClick = onClick)
            .testTag(tag)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (on) Fc.AccentLight else Fc.Secondary, size = 20.dp)
    }
}

@Composable
private fun HeaderLetter(letter: String, description: String, on: Boolean, lit: Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Touch)
            .clickable(role = Role.Switch, onClick = onClick)
            .testTag(tag)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(if (on) lit else Fc.Raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = FcType.Caption, color = if (on) Color.Black else Fc.Body)
        }
    }
}

private fun headerMenu(ed: EditorController, p: ProTimeline, t: ProTrack, compact: Boolean, rename: (ProTrack) -> Unit): List<Pair<String, () -> Unit>> {
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += "Rename…" to { rename(t) }
    items += (if (t.targeted) "Untarget ${t.code}" else "Target ${t.code}") to { ed.setTargeted(t.id, !t.targeted) }
    items += (if (t.sourcePatched) "Unpatch Source" else "Patch Source to ${t.code}") to { ed.setSourcePatch(t.id, !t.sourcePatched) }
    items += (if (t.syncLock) "Sync Lock Off" else "Sync Lock On") to { ed.setTrack(t.id, TrackSwitches(syncLock = !t.syncLock)) }
    if (!t.video) items += (if (t.solo) "Solo Off" else "Solo Track") to { ed.setTrack(t.id, TrackSwitches(solo = !t.solo)) }
    if (compact && t.video) items += (if (t.output) "Hide Track Output" else "Show Track Output") to { ed.setTrack(t.id, TrackSwitches(output = !t.output)) }
    items += "Height: Small" to { ed.setTrackHeight(t.id, PRO_TRACK_MIN) }
    items += "Height: Medium" to { ed.setTrackHeight(t.id, if (t.video) PRO_VIDEO_TRACK + 24 else PRO_AUDIO_TRACK + 24) }
    items += "Height: Large" to { ed.setTrackHeight(t.id, 120) }
    items += (if (t.video) "Add Video Track" else "Add Audio Track") to { if (t.video) ed.addTracks(1, 0) else ed.addTracks(0, 1) }
    val many = if (t.video) p.videoTracks.size > 1 else p.audioTracks.size > 1
    if (many) items += "Delete Track" to { ed.deleteTrack(t.id) }
    return items
}

private fun clipMenu(ed: EditorController, c: ProClip): List<Pair<String, () -> Unit>> = listOf(
    "Clear" to {
        if (!c.selected) ed.proSelect(c.id)
        ed.deleteSelection(ripple = false)
    },
    "Ripple Delete" to {
        if (!c.selected) ed.proSelect(c.id)
        ed.deleteSelection(ripple = true)
    },
    (if (c.selected) "Deselect" else "Add to Selection") to { ed.proSelect(c.id, add = true) },
    "Add Marker at Playhead" to { ed.addMarker() },
)

private fun laneMenu(ed: EditorController): List<Pair<String, () -> Unit>> = listOf(
    "Deselect All" to { ed.proSelect(null) },
    "Add Video Track" to { ed.addTracks(1, 0) },
    "Add Audio Track" to { ed.addTracks(0, 1) },
    "Add Marker at Playhead" to { ed.addMarker() },
)

/** The tracks' clips, the In/Out band and the playhead; and the gestures on them. */
@Composable
private fun Tracks(
    ed: EditorController,
    p: ProTimeline,
    rows: List<ProRow>,
    vScroll: () -> Float,
    xOf: (Long) -> Float,
    tOf: (Float) -> Long,
    pps: () -> Float,
    onPan: (Float, Float) -> Unit,
    onZoom: (Float, Float) -> Unit,
    onMenu: (Offset, ProClip?) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // a clip being dragged: by how much, and to where (y in the track stack)
    var dragClip by remember { mutableStateOf<ProClip?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // the gesture reads these as they are now, not as they were when it began
    val pNow by rememberUpdatedState(p)
    val rowsNow by rememberUpdatedState(rows)
    val tAt by rememberUpdatedState(tOf)
    val ppsNow by rememberUpdatedState(pps)
    val vNow by rememberUpdatedState(vScroll)
    val pan by rememberUpdatedState(onPan)
    val zoom by rememberUpdatedState(onZoom)
    val menuAt by rememberUpdatedState(onMenu)
    val snapReach = with(density) { 10.dp.toPx() }

    fun rowAt(rows: List<ProRow>, y: Float) = rows.firstOrNull { y >= it.top && y < it.bottom }

    Canvas(
        modifier
            .background(Fc.Black)
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p0 = pNow
                    val stackY = down.position.y + vNow()
                    val hitRow = rowAt(rowsNow, stackY)
                    val hit = hitRow?.let { r ->
                        val t = tAt(down.position.x)
                        p0.clips.firstOrNull { it.track == r.track.id && t >= it.start && t < it.start + it.duration }
                    }
                    var multi = false
                    val result = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var r = "up"
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) {
                                multi = true
                                r = "moved"
                                break
                            }
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                r = "moved"
                                break
                            }
                        }
                        r
                    }
                    when (result) {
                        null -> {
                            menuAt(down.position, hit)
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                        }
                        // a tap selects the clip under it (with its partners), or clears the selection
                        "up" -> ed.proSelect(hit?.id)
                        else -> {
                            // a selected clip on an unlocked track moves; anywhere else the view pans
                            val dragging = !multi && hit != null && hit.selected && hitRow?.track?.locked != true
                            if (dragging) {
                                dragClip = hit
                                dragDx = 0f
                                dragY = stackY
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    multi = true
                                    dragClip = null
                                    val z = ev.calculateZoom()
                                    val d = ev.calculatePan()
                                    if (z != 1f) zoom(z, ev.calculateCentroid().x)
                                    pan(d.x, d.y)
                                } else if (dragClip != null) {
                                    val c = pressed.first()
                                    dragDx += c.positionChange().x
                                    dragY = c.position.y + vNow()
                                } else if (!multi) {
                                    val d = pressed.first().positionChange()
                                    pan(d.x, d.y)
                                }
                                ev.changes.forEach { it.consume() }
                            }
                            val c = dragClip
                            if (c != null) {
                                dragClip = null
                                val pl = pNow
                                val from = (pl.videoTracks + pl.audioTracks).firstOrNull { it.id == c.track }
                                val to = rowAt(rowsNow, dragY)?.track?.takeIf { from != null && it.video == from.video } ?: from
                                val start = landing(pl, ed.playhead, c, dragDx, ppsNow(), snapReach)
                                if (to != null && (start != c.start || to.id != c.track)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    ed.proMove(c.id, to.id, start)
                                }
                            }
                        }
                    }
                }
            }
            .semantics { contentDescription = "Timeline tracks" },
    ) {
        val w = size.width
        val v = vScroll()
        val dragging = dragClip
        val tracks = p.videoTracks + p.audioTracks
        val dragTarget = dragging?.let { d -> rowAt(rows, dragY)?.takeIf { r -> r.track.video == tracks.firstOrNull { it.id == d.track }?.video } }
        val dragShift = dragging?.let { landing(p, ed.playhead, it, dragDx, pps(), snapReach) - it.start } ?: 0L
        translate(0f, -v) {
            // lanes
            for (r in rows) {
                drawRect(if (r.track.targeted) Color(0xFF151820) else Fc.Lane, Offset(0f, r.top), Size(w, r.height))
                drawLine(Fc.Hairline, Offset(0f, r.bottom), Offset(w, r.bottom), 1f)
            }
            val rowOf = rows.associateBy { it.track.id }
            val labelStyle = TextStyle(fontFamily = Inter, fontSize = 11.sp)
            for (c in p.clips) {
                val r = rowOf[c.track] ?: continue
                val moves = dragging != null && (c.id == dragging.id || c.selected)
                drawClip(c, r, xOf(c.start), xOf(c.start + c.duration), measurer, labelStyle, alpha = if (moves) 0.35f else 1f)
                if (moves && dragging != null) {
                    val row = if (c.id == dragging.id) (dragTarget ?: r) else r
                    drawClip(c, row, xOf(c.start + dragShift), xOf(c.start + dragShift + c.duration), measurer, labelStyle, alpha = 0.9f, ghost = true)
                }
            }
            // locked tracks: hatched
            for (r in rows.filter { it.track.locked }) {
                clipRect(0f, r.top, w, r.bottom) {
                    var x = -r.height
                    while (x < w) {
                        drawLine(Color.White.copy(alpha = 0.08f), Offset(x, r.bottom), Offset(x + r.height, r.top), 1.dp.toPx())
                        x += 10.dp.toPx()
                    }
                }
            }
        }
        // In to Out, across the tracks
        val i = p.markIn
        val o = p.markOut
        if (i != null || o != null) {
            val x0 = i?.let(xOf) ?: 0f
            val x1 = o?.let(xOf) ?: w
            drawRect(Fc.Accent.copy(alpha = 0.10f), Offset(x0, 0f), Size(max(0f, x1 - x0), size.height))
        }
        // markers, as lines through the tracks
        for (m in p.markers) {
            val x = xOf(m.start)
            drawLine(markerColor(m.color).copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        // the playhead
        val px = xOf(ed.playhead)
        drawLine(Fc.AccentLight, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
    }
}

/**
 * Where clip [c] dragged by [dx] px lands, frame-exact; with Snap on, its head or tail sticks to
 * the nearest edit, the playhead, In/Out or a marker within [reach] px.
 */
private fun landing(p: ProTimeline, playhead: Long, c: ProClip, dx: Float, pps: Float, reach: Float): Long {
    val start = TimelineMath.snap((c.start + (dx / pps * TPS)).roundToLong().coerceAtLeast(0L), p.rateNum, p.rateDen)
    if (!p.snapping) return start
    val moving = p.clips.filter { it.id == c.id || it.selected || (p.linkedSelection && c.link != null && it.link == c.link) }.map { it.id }.toSet()
    val points = buildList {
        add(0L)
        add(playhead)
        p.markIn?.let(::add)
        p.markOut?.let(::add)
        p.markers.forEach { add(it.start) }
        p.clips.filter { it.id !in moving }.forEach {
            add(it.start)
            add(it.start + it.duration)
        }
    }
    val within = reach / pps * TPS
    var best = start
    var bestD = Double.MAX_VALUE
    for (pt in points) {
        for (edge in longArrayOf(start, start + c.duration)) {
            val d = abs((pt - edge).toDouble())
            if (d < within && d < bestD) {
                bestD = d
                best = start + (pt - edge)
            }
        }
    }
    return best.coerceAtLeast(0L)
}

private fun DrawScope.drawClip(
    c: ProClip,
    r: ProRow,
    x0: Float,
    x1: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    alpha: Float,
    ghost: Boolean = false,
) {
    if (x1 < 0f || x0 > size.width) return
    val (fill, content) = proClipColors(c.kind)
    val top = r.top + 2.dp.toPx()
    val h = r.height - 4.dp.toPx()
    val dim = if (c.enabled) 1f else 0.45f
    drawRoundRect(fill.copy(alpha = alpha * dim), Offset(x0, top), Size(max(1f, x1 - x0), h), CornerRadius(3.dp.toPx()))
    if (c.selected || ghost) drawRoundRect(Color.White.copy(alpha = alpha), Offset(x0, top), Size(max(1f, x1 - x0), h), CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
    val left = max(x0, 0f) + 4.dp.toPx()
    val room = (x1 - left - 4.dp.toPx()).toInt()
    if (room > 12.dp.toPx() && h > 14.dp.toPx()) {
        drawText(
            measurer,
            c.name,
            Offset(left, top + 2.dp.toPx()),
            style.copy(color = content.copy(alpha = alpha * dim)),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            size = IntSize(room, (h - 2.dp.toPx()).toInt().coerceAtLeast(1)),
        )
    }
}

@Composable
private fun RenameTrackDialog(t: ProTrack, onDone: (String?) -> Unit) {
    var name by remember(t.id) { mutableStateOf(t.name) }
    FcDialog("Rename ${t.code}", "Rename", onConfirm = { onDone(name) }, onDismiss = { onDone(null) }, confirmEnabled = name.isNotBlank()) {
        DialogField(name, { name = it }, "pro_rename_field")
    }
}

@Composable
private fun DialogField(value: String, onChange: (String) -> Unit, tag: String, singleLine: Boolean = true) {
    BasicTextField(
        value,
        onChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = Fc.Title),
        cursorBrush = SolidColor(Fc.Title),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Fc.Raised)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(tag),
    )
}

/** Edit a marker: name, comment, colour; or delete it. */
@Composable
private fun MarkerDialog(ed: EditorController, m: ProMarker, onClose: () -> Unit) {
    var name by remember(m.id) { mutableStateOf(m.name) }
    var comment by remember(m.id) { mutableStateOf(m.comment) }
    var color by remember(m.id) { mutableStateOf(m.color) }
    val colors = remember { runCatching { markerColors() }.getOrDefault(emptyList()) }
    FcDialog(
        "Marker at ${TimelineMath.timecode(m.start, ed.rateNum, ed.rateDen)}",
        "Save",
        onConfirm = {
            ed.editMarker(m.id, name, comment, color)
            onClose()
        },
        onDismiss = onClose,
    ) {
        Text("Name", style = FcType.BodySmall, color = Fc.Secondary)
        Spacer(Modifier.height(4.dp))
        DialogField(name, { name = it }, "pro_marker_name")
        Spacer(Modifier.height(12.dp))
        Text("Comment", style = FcType.BodySmall, color = Fc.Secondary)
        Spacer(Modifier.height(4.dp))
        DialogField(comment, { comment = it }, "pro_marker_comment", singleLine = false)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (c in colors) {
                Box(
                    Modifier
                        .size(Touch)
                        .clickable(role = Role.RadioButton) { color = c }
                        .semantics {
                            contentDescription = c
                            selected = c == color
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(markerColor(c))
                            .then(if (c == color) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        DialogButton("Delete Marker", {
            ed.deleteMarker(m.id)
            onClose()
        }, filled = false, destructive = true)
    }
}
