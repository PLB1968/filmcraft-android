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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.filmcraft.core.ClipKind
import io.github.artcraftandroid.filmcraft.core.ProClip
import io.github.artcraftandroid.filmcraft.core.ProMarker
import io.github.artcraftandroid.filmcraft.core.ProTimeline
import io.github.artcraftandroid.filmcraft.core.ProTrack
import io.github.artcraftandroid.filmcraft.core.ProTransition
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
            TimelineBar(ed, p, compact) { items -> menu = ProMenu(Offset(with(density) { 160.dp.toPx() }, 0f), items) }
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
                // the playhead moved (playback, a frame step, a jump): page the view so it shows, put
                // near the left edge going forward and near the right going back (a touch on the
                // timeline only seeks to where the finger is, which is already showing)
                val shown = remember { longArrayOf(ed.playhead) }
                if (ed.playhead != shown[0]) {
                    val t = ed.playhead
                    val back = t < shown[0]
                    val x = xOf(t)
                    SideEffect {
                        shown[0] = t
                        if (x < 0f || x > viewport * 0.92f) setScroll(t - ((if (back) viewport * 0.9f else viewport * 0.1f) / pps() * TPS).roundToLong())
                    }
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
                            onMarker = { at ->
                                val near = with(density) { 16.dp.toPx() }
                                p.markers.minByOrNull { abs(xOf(it.start) - at.x) }?.takeIf { abs(xOf(it.start) - at.x) < near }?.let { editingMarker = it }
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
                            onCut = { at, left, right ->
                                val pos = at + Offset(headerPx, tracksTop)
                                // the crossfades already on this cut (the picture's and its sound's)
                                val partners = p.clips.filter { left.link != null && it.link == left.link }.map { it.id }.toSet() + left.id
                                val existing = p.transitions.filter { tr -> tr.from?.let { it in partners } == true && tr.to != null }
                                fun lengths(video: Boolean, audio: Boolean) {
                                    menu = ProMenu(
                                        pos,
                                        listOf(0.5 to "0.5 seconds", 1.0 to "1 second", 2.0 to "2 seconds").map { (secs, label) ->
                                            label to { ed.crossfade(left, video, audio, secs) }
                                        },
                                    )
                                }
                                menu = ProMenu(
                                    pos,
                                    buildList<Pair<String, () -> Unit>> {
                                        add("Crossfade Video…" to { lengths(video = true, audio = false) })
                                        add("Crossfade Audio…" to { lengths(video = false, audio = true) })
                                        add("Crossfade Video and Audio…" to { lengths(video = true, audio = true) })
                                        if (existing.isNotEmpty()) add("Remove Crossfade" to { existing.forEach { ed.removeTransition(it.id) } })
                                        if (right.id != left.id) add("Select ${right.name.ifBlank { "Next Clip" }}" to { ed.proSelect(right.id) })
                                    },
                                )
                            },
                            onTransition = { at, tr ->
                                menu = ProMenu(
                                    at + Offset(headerPx, tracksTop),
                                    listOf(("Remove ${tr.name.ifBlank { "Crossfade" }}") to { ed.removeTransition(tr.id) }),
                                )
                            },
                            onGap = { at, track, t ->
                                menu = ProMenu(at + Offset(headerPx, tracksTop), listOf("Close Gap" to { ed.closeGap(track.id, t) }))
                            },
                            onAddMedia = { at, track, t ->
                                val where = "${track.code} at ${TimelineMath.timecode(t, num, den)}"
                                menu = ProMenu(
                                    at + Offset(headerPx, tracksTop),
                                    listOf(
                                        "Add Photos & Videos ($where)" to { ed.addMediaAt(PickKind.PhotosAndVideos, track, t) },
                                        "Add from Files ($where)" to { ed.addMediaAt(PickKind.FilesVisual, track, t) },
                                        "Add Sounds ($where)" to { ed.addMediaAt(PickKind.FilesAudio, track, t) },
                                    ),
                                )
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

/**
 * The bar over the timeline: the playhead's timecode, Snap, Linked Selection, Add Marker, Add
 * Tracks, zoom and In/Out. The buttons share the width, so the bar fits a phone held upright.
 */
@Composable
private fun TimelineBar(ed: EditorController, p: ProTimeline?, compact: Boolean, onMenu: (List<Pair<String, () -> Unit>>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Touch).background(Fc.Panel).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            TimelineMath.timecode(ed.playhead, ed.rateNum, ed.rateDen),
            // tabular digits: the timecode keeps its width as it counts, so the buttons beside it stay put
            style = TextStyle(fontFamily = Inter, fontSize = if (compact) 13.sp else 16.sp, color = Fc.AccentLight, fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 6.dp).testTag("pro_timecode"),
        )
        if (!compact) {
            Text("${ed.proTool.label} (${ed.proTool.key})", style = FcType.Caption, color = Fc.Secondary, maxLines = 1, modifier = Modifier.padding(end = 8.dp).testTag("pro_tool_name"))
        }
        val snapping = p?.snapping == true
        val linked = p?.linkedSelection == true
        val slot = Modifier.weight(1f).widthIn(max = Touch).fillMaxHeight()
        BarToggle(ed, "snap", ProGlyphs.Magnet, "Snap in Timeline", snapping, "pro_snap", slot) { ed.setSnapping(!snapping) }
        BarToggle(ed, "linked", ProGlyphs.Link, "Linked Selection", linked, "pro_linked_selection", slot) { ed.setLinkedSelection(!linked) }
        BarButton(ed, "addMarker", ProGlyphs.Marker, "Add Marker", "pro_add_marker", slot) { ed.addMarker() }
        BarButton(ed, "addTracks", LucideIcons.Plus.glyph(), "Add Tracks", "pro_add_tracks", slot) {
            onMenu(listOf("Add Video Track" to { ed.addTracks(1, 0) }, "Add Audio Track" to { ed.addTracks(0, 1) }, "Add Video and Audio Track" to { ed.addTracks(1, 1) }))
        }
        BarButton(ed, "zoomFit", ProGlyphs.Fit, "Zoom to Sequence", "pro_zoom_fit", slot) {
            ed.proPxPerSecond = 0f
            ed.proScroll = 0L
        }
        BarButton(ed, "markIn", ProGlyphs.MarkIn, "Mark In", "pro_mark_in", slot) { ed.markIn() }
        BarButton(ed, "markOut", ProGlyphs.MarkOut, "Mark Out", "pro_mark_out", slot) { ed.markOut() }
    }
}

@Composable
private fun BarToggle(ed: EditorController, help: String, glyph: Glyph, description: String, on: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Fc.Accent.copy(alpha = 0.35f) else Color.Transparent)
            .helpClick(ed, help, role = Role.Switch, onClick = onClick)
            .testTag(tag)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (on) Fc.AccentLight else Fc.Icon, size = 20.dp)
    }
}

@Composable
private fun BarButton(ed: EditorController, help: String, glyph: Glyph, description: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).helpClick(ed, help, onClick = onClick).testTag(tag).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Fc.Icon, size = 20.dp)
    }
}

/** The timecode ruler: tap or drag to move the playhead, long press for In/Out and markers. */
@Composable
private fun Ruler(
    ed: EditorController,
    p: ProTimeline,
    xOf: (Long) -> Float,
    tOf: (Float) -> Long,
    onMarker: (Offset) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val tAt by rememberUpdatedState(tOf)
    val markerAt by rememberUpdatedState(onMarker)
    // the last tap (time, place), for a double tap on a marker
    var lastTap by remember { mutableStateOf<Pair<Long, Offset>?>(null) }
    Canvas(
        modifier
            .background(Fc.Panel)
            .clipToBounds()
            .pointerInput(Unit) {
                // a touch moves the playhead there and a drag scrubs; a double tap on a marker opens it
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val double = lastTap?.let { (t, at) -> down.uptimeMillis - t < viewConfiguration.doubleTapTimeoutMillis && (at - down.position).getDistance() < 24.dp.toPx() } == true
                    if (double) {
                        lastTap = null
                        markerAt(down.position)
                    } else {
                        ed.seek(tAt(down.position.x))
                    }
                    var moved = false
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            c.consume()
                            ed.seek(tAt(c.position.x))
                        }
                    }
                    if (!double) lastTap = if (moved) null else down.uptimeMillis to down.position
                    ed.settle()
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
        val style = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = Fc.RulerLabel, fontFeatureSettings = "tnum")
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
                    val down = awaitFirstDown()
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
                        .helpClick(ed, "patch", role = Role.Switch) { ed.setSourcePatch(t.id, !t.sourcePatched) }
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
                    .helpClick(ed, "target", role = Role.Switch) { ed.setTargeted(t.id, !t.targeted) }
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
            HeaderSwitch(ed, "lock", if (t.locked) ProGlyphs.Lock else ProGlyphs.Unlock, "Lock ${t.code}", t.locked, "pro_lock_${t.code}") {
                ed.setTrack(t.id, TrackSwitches(locked = !t.locked))
            }
            if (t.video) {
                HeaderSwitch(ed, "output", if (t.output) ProGlyphs.Eye else ProGlyphs.EyeOff, "Toggle track output ${t.code}", !t.output, "pro_output_${t.code}") {
                    ed.setTrack(t.id, TrackSwitches(output = !t.output))
                }
            } else {
                HeaderLetter(ed, "mute", "M", "Mute ${t.code}", t.muted, Color(0xFF3FAE5A), "pro_mute_${t.code}") { ed.setTrack(t.id, TrackSwitches(muted = !t.muted)) }
            }
            if (!compact) {
                if (!t.video) HeaderLetter(ed, "solo", "S", "Solo ${t.code}", t.solo, Color(0xFFE8C33F), "pro_solo_${t.code}") { ed.setTrack(t.id, TrackSwitches(solo = !t.solo)) }
                HeaderSwitch(ed, "sync", if (t.syncLock) ProGlyphs.SyncLock else ProGlyphs.SyncOff, "Sync lock ${t.code}", t.syncLock, "pro_sync_${t.code}") {
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
private fun HeaderSwitch(ed: EditorController, help: String, glyph: Glyph, description: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Touch)
            .helpClick(ed, help, role = Role.Switch, onClick = onClick)
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
private fun HeaderLetter(ed: EditorController, help: String, letter: String, description: String, on: Boolean, lit: Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Touch)
            .helpClick(ed, help, role = Role.Switch, onClick = onClick)
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

/** What a one-finger drag on a clip does, by the tool and where it began. */
private enum class DragKind { Move, Trim, Ripple, Roll, Stretch, Slip, Slide, Forward }

/** A drag on the tracks: what it does, to which clip (and edge), and the clips it takes along. */
private data class ToolDrag(val kind: DragKind, val clip: ProClip, val head: Boolean, val partner: ProClip? = null, val group: List<ProClip> = emptyList())

/** The clips on [c]'s track that touch it: the one ending where it starts, the one starting where it ends. */
private fun neighbours(p: ProTimeline, c: ProClip): Pair<ProClip?, ProClip?> {
    val frame = TimelineMath.tickOfFrame(1, p.rateNum, p.rateDen)
    val same = p.clips.filter { it.track == c.track && it.id != c.id }
    val prev = same.firstOrNull { abs(it.start + it.duration - c.start) < frame }
    val next = same.firstOrNull { abs(it.start - (c.start + c.duration)) < frame }
    return prev to next
}

/** Footage time of timeline time [t] in clip [c]. */
private fun mediaAt(c: ProClip, t: Long): Long = c.sourceIn + ((t - c.start) * c.speed).roundToLong()

/**
 * How far a drag of [dx] px moves [d]'s edit, frame-exact, kept inside the footage and the
 * neighbours the engine would stop at, and (with Snap on) stuck to the nearest edit, the
 * playhead, In/Out or a marker within [reach] px.
 */
private fun dragDelta(p: ProTimeline, playhead: Long, d: ToolDrag, dx: Float, pps: Float, reach: Float): Long {
    val frame = TimelineMath.tickOfFrame(1, p.rateNum, p.rateDen)
    val c = d.clip
    var delta = TimelineMath.snap(abs((dx / pps * TPS).roundToLong()), p.rateNum, p.rateDen) * (if (dx < 0) -1 else 1)
    // the edge (or edges) that moves, for snapping
    val edges: List<Long> = when (d.kind) {
        DragKind.Trim, DragKind.Ripple, DragKind.Stretch, DragKind.Roll -> listOf(if (d.head) c.start else c.start + c.duration)
        DragKind.Slide, DragKind.Forward, DragKind.Move -> listOf(c.start, c.start + c.duration)
        DragKind.Slip -> emptyList()
    }
    if (p.snapping && edges.isNotEmpty()) {
        val moving = (listOf(c) + d.group + listOfNotNull(d.partner)).map { it.id }.toSet()
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
        var best = Double.MAX_VALUE
        for (pt in points) for (e in edges) {
            val dd = abs((pt - (e + delta)).toDouble())
            if (dd < within && dd < best) {
                best = dd
                delta = pt - e
            }
        }
    }
    val media = c.mediaDuration
    val (prev, next) = neighbours(p, c)
    fun headRoom() = if (media > 0) -(c.sourceIn / c.speed).roundToLong() else Long.MIN_VALUE / 4
    fun tailRoom() = if (media > 0) ((media - c.sourceIn) / c.speed).roundToLong() - c.duration else Long.MAX_VALUE / 4
    when (d.kind) {
        DragKind.Trim, DragKind.Ripple, DragKind.Stretch -> {
            if (d.head) {
                delta = delta.coerceAtMost(c.duration - frame)
                if (d.kind != DragKind.Stretch) delta = delta.coerceAtLeast(headRoom())
                if (d.kind == DragKind.Trim && prev == null) delta = delta.coerceAtLeast(-c.start)
            } else {
                delta = delta.coerceAtLeast(frame - c.duration)
                if (d.kind != DragKind.Stretch) delta = delta.coerceAtMost(tailRoom())
            }
        }
        DragKind.Roll -> {
            val o = d.partner
            if (o != null) {
                val (l, r) = if (d.head) o to c else c to o
                delta = delta.coerceAtLeast(frame - l.duration).coerceAtMost(r.duration - frame)
            }
        }
        DragKind.Slip -> if (media > 0) delta = -((-delta).coerceAtLeast(headRoom()).coerceAtMost(tailRoom()))
        DragKind.Slide -> {
            prev?.let { delta = delta.coerceAtLeast(frame - it.duration) }
            next?.let { delta = delta.coerceAtMost(it.duration - frame) }
        }
        DragKind.Forward, DragKind.Move -> delta = delta.coerceAtLeast(-(d.group.minOfOrNull { it.start } ?: c.start))
    }
    return delta
}

/** Where each clip a drag changes would sit: (start, duration) by clip id. */
private fun dragGhosts(p: ProTimeline, d: ToolDrag, delta: Long): Map<ULong, Pair<Long, Long>> {
    val c = d.clip
    val out = HashMap<ULong, Pair<Long, Long>>()
    fun head(x: ProClip, k: Long) = (x.start + k) to (x.duration - k)
    fun tail(x: ProClip, k: Long) = x.start to (x.duration + k)
    when (d.kind) {
        DragKind.Trim, DragKind.Stretch -> out[c.id] = if (d.head) head(c, delta) else tail(c, delta)
        DragKind.Ripple -> {
            out[c.id] = if (d.head) c.start to (c.duration - delta) else tail(c, delta)
            val shift = if (d.head) -delta else delta
            for (o in p.clips.filter { it.track == c.track && it.start >= c.start + c.duration }) out[o.id] = (o.start + shift) to o.duration
        }
        DragKind.Roll -> {
            val o = d.partner
            if (o == null) {
                out[c.id] = if (d.head) head(c, delta) else tail(c, delta)
            } else if (d.head) {
                out[o.id] = tail(o, delta)
                out[c.id] = head(c, delta)
            } else {
                out[c.id] = tail(c, delta)
                out[o.id] = head(o, delta)
            }
        }
        DragKind.Slide -> {
            val (prev, next) = neighbours(p, c)
            out[c.id] = (c.start + delta) to c.duration
            prev?.let { out[it.id] = tail(it, delta) }
            next?.let { out[it.id] = head(it, delta) }
        }
        DragKind.Forward -> for (o in d.group) out[o.id] = (o.start + delta) to o.duration
        DragKind.Slip, DragKind.Move -> {}
    }
    return out
}

/** The Program monitor's frames for a drag: the edit's frame, or two side by side (roll, slip, slide). */
private fun dragView(p: ProTimeline, d: ToolDrag, delta: Long): TrimView? {
    val frame = TimelineMath.tickOfFrame(1, p.rateNum, p.rateDen)
    val c = d.clip
    fun pic(x: ProClip, mediaTime: Long, label: String): TrimFrame {
        val t = if (x.mediaDuration > 0) mediaTime.coerceIn(0L, max(0L, x.mediaDuration - frame)) else mediaTime.coerceAtLeast(0L)
        return TrimFrame(if (x.kind == ClipKind.AUDIO) null else x.item, t, label)
    }
    val tc = { t: Long -> TimelineMath.timecode(t, p.rateNum, p.rateDen) }
    val sign = if (delta >= 0) "+" else "-"
    val by = "$sign${tc(abs(delta))}"
    return when (d.kind) {
        DragKind.Trim, DragKind.Ripple, DragKind.Stretch -> {
            val f = if (d.head) pic(c, c.sourceIn + (delta * c.speed).roundToLong(), "In") else pic(c, c.sourceIn + ((c.duration + delta - frame) * c.speed).roundToLong(), "Out")
            TrimView(listOf(f), "${if (d.kind == DragKind.Ripple) "Ripple" else if (d.kind == DragKind.Stretch) "Rate Stretch" else "Trim"} $by")
        }
        DragKind.Roll -> {
            val o = d.partner ?: return TrimView(listOf(pic(c, mediaAt(c, if (d.head) c.start + delta else c.start + c.duration + delta - frame), if (d.head) "In" else "Out")), "Trim $by")
            val (l, r) = if (d.head) o to c else c to o
            val cut = r.start + delta
            TrimView(listOf(pic(l, mediaAt(l, cut - frame), "Out"), pic(r, mediaAt(r, cut), "In")), "Rolling Edit $by")
        }
        DragKind.Slip -> {
            val inT = c.sourceIn - (delta * c.speed).roundToLong()
            TrimView(listOf(pic(c, inT, "In"), pic(c, inT + ((c.duration - frame) * c.speed).roundToLong(), "Out")), "Slip $by")
        }
        DragKind.Slide -> {
            val (prev, next) = neighbours(p, c)
            val left = prev?.let { pic(it, mediaAt(it, c.start + delta - frame), "Out") } ?: TrimFrame(null, 0, "Out")
            val right = next?.let { pic(it, mediaAt(it, c.start + c.duration + delta), "In") } ?: TrimFrame(null, 0, "In")
            TrimView(listOf(left, right), "Slide $by")
        }
        DragKind.Forward, DragKind.Move -> null
    }
}

/** The tracks' clips, the In/Out band and the playhead; and what each tool does on them. */
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
    onAddMedia: (Offset, ProTrack, Long) -> Unit,
    onCut: (Offset, ProClip, ProClip) -> Unit,
    onGap: (Offset, ProTrack, Long) -> Unit,
    onTransition: (Offset, ProTransition) -> Unit,
    modifier: Modifier,
) {
    val transitionAt by rememberUpdatedState(onTransition)
    val cutAt by rememberUpdatedState(onCut)
    val gapAt by rememberUpdatedState(onGap)
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val addMediaAt by rememberUpdatedState(onAddMedia)
    // the last tap (time, place), for a double tap
    var lastTap by remember { mutableStateOf<Pair<Long, Offset>?>(null) }
    // a drag in progress: what it does, by how much, and to where (y in the track stack)
    var drag by remember { mutableStateOf<ToolDrag?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // the gesture reads these as they are now, not as they were when it began
    val pNow by rememberUpdatedState(p)
    val rowsNow by rememberUpdatedState(rows)
    val tAt by rememberUpdatedState(tOf)
    val xAt by rememberUpdatedState(xOf)
    val ppsNow by rememberUpdatedState(pps)
    val vNow by rememberUpdatedState(vScroll)
    val pan by rememberUpdatedState(onPan)
    val zoom by rememberUpdatedState(onZoom)
    val menuAt by rememberUpdatedState(onMenu)
    val snapReach = with(density) { 10.dp.toPx() }
    val edgeZone = with(density) { 18.dp.toPx() }

    fun rowAt(rows: List<ProRow>, y: Float) = rows.firstOrNull { y >= it.top && y < it.bottom }
    fun frameAt(x: Float): Long = TimelineMath.snap(tAt(x), pNow.rateNum, pNow.rateDen)

    /** What a drag beginning at [x] on [c] does with the current tool (null: the view pans). */
    fun startDrag(p: ProTimeline, c: ProClip, row: ProRow, x: Float): ToolDrag? {
        if (row.track.locked) return null
        val x0 = xAt(c.start)
        val x1 = xAt(c.start + c.duration)
        val zone = minOf(edgeZone, (x1 - x0) / 3f)
        val edge: Boolean? = when {
            x - x0 <= zone -> true
            x1 - x <= zone -> false
            else -> null
        }
        val (prev, next) = neighbours(p, c)
        return when (ed.proTool) {
            ProTool.Selection -> when {
                !c.selected -> null
                edge != null -> ToolDrag(DragKind.Trim, c, edge)
                else -> ToolDrag(DragKind.Move, c, false)
            }
            ProTool.RippleEdit -> edge?.let { ToolDrag(DragKind.Ripple, c, it) }
            ProTool.RollingEdit -> edge?.let { ToolDrag(DragKind.Roll, c, it, partner = if (it) prev else next) }
            ProTool.RateStretch -> edge?.let { ToolDrag(DragKind.Stretch, c, it) }
            ProTool.Slip -> ToolDrag(DragKind.Slip, c, false)
            ProTool.Slide -> ToolDrag(DragKind.Slide, c, false)
            ProTool.TrackSelectForward -> {
                val t = tAt(x)
                ToolDrag(DragKind.Forward, c, false, group = p.clips.filter { it.track == c.track && it.start + it.duration > t })
            }
            ProTool.Hand, ProTool.Zoom, ProTool.Razor, ProTool.Type -> null
        }
    }

    /** Make a finished drag one edit. */
    fun commit(p: ProTimeline, d: ToolDrag, dx: Float, y: Float) {
        val c = d.clip
        if (d.kind == DragKind.Move) {
            val from = (p.videoTracks + p.audioTracks).firstOrNull { it.id == c.track }
            val to = rowAt(rowsNow, y)?.track?.takeIf { from != null && it.video == from.video } ?: from
            val start = landing(p, ed.playhead, c, dx, ppsNow(), snapReach)
            if (to != null && (start != c.start || to.id != c.track)) ed.proMove(c.id, to.id, start)
            return
        }
        val delta = dragDelta(p, ed.playhead, d, dx, ppsNow(), snapReach)
        if (delta == 0L) return
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        when (d.kind) {
            DragKind.Trim -> ed.proTrim(c.id, d.head, delta, ripple = false)
            DragKind.Ripple -> ed.proTrim(c.id, d.head, delta, ripple = true)
            DragKind.Roll -> {
                val o = d.partner
                when {
                    o == null -> ed.proTrim(c.id, d.head, delta, ripple = false)
                    d.head -> ed.proRoll(o.id, c.id, delta)
                    else -> ed.proRoll(c.id, o.id, delta)
                }
            }
            DragKind.Stretch -> ed.proRateStretch(c.id, d.head, delta)
            // dragging right shows earlier footage, as on the desktop
            DragKind.Slip -> ed.proSlip(c.id, -delta)
            DragKind.Slide -> ed.proSlide(c.id, delta)
            DragKind.Forward -> ed.proShift(d.group.map { it.id }, delta)
            DragKind.Move -> {}
        }
    }

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
                    val x = down.position.x
                    when (result) {
                        // a long press: the context menu, or the tool's second action
                        null -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            when (ed.proTool) {
                                ProTool.Zoom -> zoom(0.5f, x)
                                ProTool.Razor -> ed.proRazor(frameAt(x), null, null)
                                ProTool.TrackSelectForward -> ed.proSelectForward(null, tAt(x))
                                else -> {
                                    val row = hitRow
                                    val onRow = if (row == null) emptyList() else p0.clips.filter { it.track == row.track.id }.sortedBy { it.start }
                                    val frame = TimelineMath.tickOfFrame(1, p0.rateNum, p0.rateDen)
                                    // a cut (one clip ending where the next begins) within reach of the finger
                                    val cut = onRow.zipWithNext().filter { (a, b) -> abs(a.start + a.duration - b.start) < frame }
                                        .minByOrNull { (a, _) -> abs(xAt(a.start + a.duration) - x) }
                                        ?.takeIf { (a, _) -> abs(xAt(a.start + a.duration) - x) < 16.dp.toPx() }
                                    val t = tAt(x)
                                    // empty space with a clip on each side
                                    val gap = row != null && hit == null && onRow.any { it.start + it.duration <= t } && onRow.any { it.start > t }
                                    // a crossfade or fade band under the finger (whether or not it still sits on a cut)
                                    val band = if (row == null) null else p0.transitions.firstOrNull { tr ->
                                        tr.track == row.track.id && x >= xAt(tr.start) - 8.dp.toPx() && x <= xAt(tr.start + tr.duration) + 8.dp.toPx()
                                    }
                                    when {
                                        band != null && row?.track?.locked != true && (cut == null || band.from != cut.first.id) -> transitionAt(down.position, band)
                                        cut != null && row?.track?.locked != true -> cutAt(down.position, cut.first, cut.second)
                                        gap && row != null && !row.track.locked -> gapAt(down.position, row.track, t)
                                        else -> menuAt(down.position, hit)
                                    }
                                }
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                        }
                        // a tap
                        // a second tap close by and soon after: add media there (the tools that act on a
                        // tap keep their single taps)
                        "up" -> if (
                            ed.proTool !in listOf(ProTool.Zoom, ProTool.Razor, ProTool.Type) &&
                            hitRow != null &&
                            lastTap?.let { (t, at) -> down.uptimeMillis - t < viewConfiguration.doubleTapTimeoutMillis && (at - down.position).getDistance() < 40.dp.toPx() } == true
                        ) {
                            lastTap = null
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            addMediaAt(down.position, hitRow.track, frameAt(x))
                        } else {
                            lastTap = down.uptimeMillis to down.position
                            when (ed.proTool) {
                            ProTool.Zoom -> zoom(2f, x)
                            ProTool.Hand -> {}
                            ProTool.Razor -> if (hit != null && hitRow?.track?.locked != true) ed.proRazor(frameAt(x), hit.id, null)
                            ProTool.TrackSelectForward -> ed.proSelectForward(hitRow?.track?.id, tAt(x))
                            ProTool.Type -> ed.titleAt = frameAt(x)
                            else -> ed.proSelect(hit?.id)
                            }
                        }
                        else -> {
                            val d = if (!multi && hit != null && hitRow != null) startDrag(p0, hit, hitRow, x) else null
                            if (d != null) {
                                drag = d
                                dragDx = 0f
                                dragY = stackY
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    multi = true
                                    drag = null
                                    ed.showTrim(null)
                                    val z = ev.calculateZoom()
                                    val dd = ev.calculatePan()
                                    if (z != 1f) zoom(z, ev.calculateCentroid().x)
                                    pan(dd.x, dd.y)
                                } else if (drag != null) {
                                    val c = pressed.first()
                                    dragDx += c.positionChange().x
                                    dragY = c.position.y + vNow()
                                    val dr = drag
                                    if (dr != null) ed.showTrim(dragView(pNow, dr, dragDelta(pNow, ed.playhead, dr, dragDx, ppsNow(), snapReach)))
                                } else if (!multi) {
                                    val dd = pressed.first().positionChange()
                                    pan(dd.x, dd.y)
                                }
                                ev.changes.forEach { it.consume() }
                            }
                            val dr = drag
                            drag = null
                            ed.showTrim(null)
                            if (dr != null) commit(pNow, dr, dragDx, dragY)
                        }
                    }
                }
            }
            .semantics { contentDescription = "Timeline tracks, ${ed.proTool.label} tool" },
    ) {
        val w = size.width
        val v = vScroll()
        val dr = drag
        val ghosts = dr?.let { dragGhosts(p, it, dragDelta(p, ed.playhead, it, dragDx, pps(), snapReach)) }.orEmpty()
        val tracks = p.videoTracks + p.audioTracks
        val moveTarget = dr?.takeIf { it.kind == DragKind.Move }?.let { d -> rowAt(rows, dragY)?.takeIf { r -> r.track.video == tracks.firstOrNull { it.id == d.clip.track }?.video } }
        val moveShift = dr?.takeIf { it.kind == DragKind.Move }?.let { landing(p, ed.playhead, it.clip, dragDx, pps(), snapReach) - it.clip.start } ?: 0L
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
                val moves = dr != null && dr.kind == DragKind.Move && (c.id == dr.clip.id || c.selected)
                val ghost = ghosts[c.id]
                drawClip(c, r, xOf(c.start), xOf(c.start + c.duration), measurer, labelStyle, alpha = if (moves || ghost != null) 0.35f else 1f)
                if (moves && dr != null) {
                    val row = if (c.id == dr.clip.id) (moveTarget ?: r) else r
                    drawClip(c, row, xOf(c.start + moveShift), xOf(c.start + moveShift + c.duration), measurer, labelStyle, alpha = 0.9f, ghost = true)
                }
                if (ghost != null) drawClip(c, r, xOf(ghost.first), xOf(ghost.first + ghost.second), measurer, labelStyle, alpha = 0.9f, ghost = true)
                if (dr != null && dr.kind == DragKind.Slip && c.id == dr.clip.id) {
                    drawClip(c, r, xOf(c.start), xOf(c.start + c.duration), measurer, labelStyle, alpha = 0.9f, ghost = true)
                }
            }
            // crossfades and fades: a light band with a diagonal, over the cut
            for (tr in p.transitions) {
                val r = rowOf[tr.track] ?: continue
                val x0 = xOf(tr.start)
                val x1 = xOf(tr.start + tr.duration)
                if (x1 < 0f || x0 > w) continue
                val top = r.top + 2.dp.toPx()
                val bottom = r.bottom - 2.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.28f), Offset(x0, top), Size(max(2f, x1 - x0), bottom - top))
                drawLine(Color.White.copy(alpha = 0.8f), Offset(x0, bottom), Offset(x1, top), 1.5.dp.toPx())
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
    // the clip's keyframes, along its foot
    if (c.keyframes.isNotEmpty() && c.duration > 0 && h > 20.dp.toPx()) {
        val y = top + h - 5.dp.toPx()
        for (k in c.keyframes) {
            val x = x0 + (k - c.start).toFloat() / c.duration * (x1 - x0)
            if (x >= x0 && x <= x1) drawKeyframe(Offset(x, y), "linear", Color.White.copy(alpha = alpha), radius = 3.5.dp.toPx())
        }
    }
    // the name sits at the clip's head and scrolls with it
    val left = x0 + 4.dp.toPx()
    val room = (x1 - left - 4.dp.toPx()).toInt()
    if (room > 12.dp.toPx() && h > 14.dp.toPx()) {
        drawText(
            measurer,
            c.name,
            Offset(left, top + 2.dp.toPx()),
            style.copy(color = content.copy(alpha = alpha * dim)),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            size = Size(room.toFloat(), max(1f, h - 2.dp.toPx())),
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
