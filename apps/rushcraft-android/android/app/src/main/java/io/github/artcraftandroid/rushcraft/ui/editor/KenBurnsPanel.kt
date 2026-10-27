package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.rushcraft.core.ClipInfo
import io.github.artcraftandroid.rushcraft.core.ClipKind
import io.github.artcraftandroid.rushcraft.core.KbInfo
import io.github.artcraftandroid.rushcraft.core.KbMove
import io.github.artcraftandroid.rushcraft.core.KbView
import io.github.artcraftandroid.rushcraft.core.KenBurns
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcSize
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.LucideIcons
import io.github.artcraftandroid.rushcraft.ui.PanelAction
import io.github.artcraftandroid.rushcraft.ui.SelectChip
import io.github.artcraftandroid.rushcraft.ui.ToolPanel
import io.github.artcraftandroid.rushcraft.ui.glyph
import kotlinx.coroutines.launch

private val KB_PRESETS = listOf(
    KenBurns.ZOOM_IN to "Zoom in", KenBurns.ZOOM_OUT to "Zoom out", KenBurns.PAN_LEFT to "Pan left", KenBurns.PAN_RIGHT to "Pan right",
    KenBurns.PAN_UP to "Pan up", KenBurns.PAN_DOWN to "Pan down", KenBurns.DIAGONAL to "Diagonal", KenBurns.ZOOM_CORNER to "Into a corner", KenBurns.RANDOM to "Random",
)

private val START_COLOR = Color(0xFF3DDC84)
private val END_COLOR = Color(0xFFFF5252)

/**
 * Ken Burns (Pro mode): the selected photo or video's slow move, from the green Start view of
 * its picture to the red End view. Pick a preset, or drag a view to move it and pinch it to zoom;
 * choose Smooth (eased) or Even, and the part of the clip the move takes.
 */
@Composable
fun KenBurnsPanel(ed: EditorController, c: ClipInfo, close: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var info by remember(c.id) { mutableStateOf<KbInfo?>(null) }
    // the move being shaped here (applied as each change finishes), and which view the fingers move
    var move by remember(c.id) { mutableStateOf<KbMove?>(null) }
    var editingEnd by remember(c.id) { mutableStateOf(false) }
    var preset by remember(c.id) { mutableStateOf<KenBurns?>(null) }
    var amount by remember { mutableDoubleStateOf(0.2) }
    LaunchedEffect(c.id, ed.timeline?.revision) {
        val i = ed.kenBurnsInfo(c.id)
        info = i
        if (i != null) move = i.current ?: move
    }
    val whole = KbView(0.5, 0.5, 1.0)
    fun apply(m: KbMove) {
        move = m
        ed.setKenBurns(c.id, m)
    }
    fun pick(kind: KenBurns) {
        preset = kind
        scope.launch {
            val p = ed.kenBurnsPreset(kind, amount, c.id) ?: return@launch
            val old = move
            apply(if (old != null) p.copy(smooth = old.smooth, from = old.from, to = old.to) else p)
        }
    }
    ToolPanel(
        "Ken Burns", close, modifier, tag = "ken_burns_panel",
        actions = {
            PanelAction(LucideIcons.Ban.glyph(), "Remove the Ken Burns move", {
                preset = null
                move = null
                ed.kenBurns(c.id, KenBurns.NONE, amount)
            }, enabled = move != null, tag = "ken_burns_remove")
        },
    ) {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        val i = info
        if (i == null) {
            Text("Select a photo or video clip.", style = FcType.Body, color = Fc.Detail, modifier = pad)
            return@ToolPanel
        }
        Text("Presets", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        Spacer(Modifier.height(8.dp))
        ChipRow(KB_PRESETS, preset, "ken_burns_preset") { pick(it) }
        ValueSlider("Zoom", "%.0f %%".format(amount * 100), ((amount - 0.05) / 0.45).toFloat(), { amount = 0.05 + it * 0.45 }, { preset?.let { pick(it) } })
        Spacer(Modifier.height(12.dp))
        Row(pad.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectChip("Start view", !editingEnd, { editingEnd = false }, tag = "ken_burns_start")
            SelectChip("End view", editingEnd, { editingEnd = true }, tag = "ken_burns_end")
            Spacer(Modifier.weight(1f))
            SelectChip("Play", false, {
                ed.seek(c.start, draft = false)
                ed.play()
            }, tag = "ken_burns_play")
        }
        Spacer(Modifier.height(8.dp))
        // the picture, with the two views on it: drag to move the one picked, pinch to zoom it
        val m = move ?: KbMove(whole, KbView(0.5, 0.5, 0.83), true, 0.0, 1.0)
        KbPicture(ed, c, i, m, editingEnd, Modifier.then(pad)) { changed, done ->
            preset = null
            move = changed
            if (done) apply(changed)
        }
        Text(
            if (editingEnd) "Drag to move the red End view, pinch to zoom it." else "Drag to move the green Start view, pinch to zoom it.",
            style = FcType.BodySmall, color = Fc.Detail, modifier = pad.padding(vertical = 6.dp),
        )
        Text("Pace", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        Spacer(Modifier.height(8.dp))
        Row(pad.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("Smooth", m.smooth, { apply(m.copy(smooth = true)) }, tag = "ken_burns_smooth")
            SelectChip("Even", !m.smooth, { apply(m.copy(smooth = false)) }, tag = "ken_burns_even")
        }
        var from by remember(c.id, m.from) { mutableDoubleStateOf(m.from) }
        var to by remember(c.id, m.to) { mutableDoubleStateOf(m.to) }
        ValueSlider("Starts", "%.0f %%".format(from * 100), (from / 0.95).toFloat(), { from = (it * 0.95).toDouble().coerceAtMost(to - 0.05) }, { apply(m.copy(from = from)) })
        ValueSlider("Ends", "%.0f %%".format(to * 100), ((to - 0.05) / 0.95).toFloat(), { to = (0.05 + it * 0.95).toDouble().coerceAtLeast(from + 0.05) }, { apply(m.copy(to = to)) })
        Text("Starts and Ends: how far into the clip the move begins and finishes.", style = FcType.BodySmall, color = Fc.Detail, modifier = pad)
        Spacer(Modifier.height(16.dp))
    }
}

/** A view's box on the picture, as fractions (left, top, width, height) of it. */
private fun box(v: KbView, i: KbInfo): FloatArray {
    val aspect = i.frameW / i.frameH.coerceAtLeast(1.0)
    val widest = if (i.pictureW / i.pictureH.coerceAtLeast(1.0) > aspect) i.pictureH * aspect else i.pictureW
    val w = (v.size * widest / i.pictureW.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
    val h = (v.size * widest / aspect / i.pictureH.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
    val x = v.x.coerceIn(w / 2, 1 - w / 2)
    val y = v.y.coerceIn(h / 2, 1 - h / 2)
    return floatArrayOf((x - w / 2).toFloat(), (y - h / 2).toFloat(), w.toFloat(), h.toFloat())
}

/** The clip's picture with its Start (green) and End (red) views; the fingers move and zoom the one being edited. */
@Composable
private fun KbPicture(ed: EditorController, c: ClipInfo, i: KbInfo, m: KbMove, editingEnd: Boolean, modifier: Modifier, change: (KbMove, Boolean) -> Unit) {
    val mNow by rememberUpdatedState(m)
    val endNow by rememberUpdatedState(editingEnd)
    val changeNow by rememberUpdatedState(change)
    @Suppress("UNUSED_VARIABLE") val version = ed.media.version
    val ratio = (i.pictureW / i.pictureH.coerceAtLeast(1.0)).toFloat().coerceIn(0.3f, 3f)
    Box(modifier.fillMaxWidth().heightIn(max = 260.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).clip(RoundedCornerShape(6.dp)).background(Fc.ThumbPlaceholder).testTag("ken_burns_picture")) {
            val thumb = when (c.kind) {
                ClipKind.IMAGE -> ed.media.frame(c.path, 0.0, 240, still = true)
                ClipKind.VIDEO -> ed.media.frame(c.path, c.sourceIn.toDouble() / TPS, 240, still = false)
                else -> null
            }
            if (thumb != null) Image(thumb.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(
                Modifier.fillMaxSize().pointerInput(i) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var cur = mNow
                        do {
                            val ev = awaitPointerEvent()
                            val pan = ev.calculatePan()
                            val zoom = ev.calculateZoom()
                            val v = if (endNow) cur.end else cur.start
                            val nv = KbView(
                                v.x + pan.x / size.width,
                                v.y + pan.y / size.height,
                                (v.size / zoom).coerceIn(0.2, 1.0),
                            )
                            // kept inside the picture, as the engine keeps it
                            val b = box(nv, i)
                            val kept = KbView((b[0] + b[2] / 2).toDouble(), (b[1] + b[3] / 2).toDouble(), nv.size)
                            cur = if (endNow) cur.copy(end = kept) else cur.copy(start = kept)
                            changeNow(cur, false)
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                        changeNow(cur, true)
                    }
                },
            ) {
                // the picture outside both views dimmed a little; each view outlined, the edited one bolder
                for ((v, colour, active) in listOf(Triple(m.start, START_COLOR, !editingEnd), Triple(m.end, END_COLOR, editingEnd))) {
                    val b = box(v, i)
                    val o = Offset(b[0] * size.width, b[1] * size.height)
                    val s = Size(b[2] * size.width, b[3] * size.height)
                    if (active) drawRect(colour.copy(alpha = 0.12f), o, s)
                    drawRect(colour, o, s, style = Stroke((if (active) 3f else 1.5f).dp.toPx()))
                }
                // the way the view travels
                val a = box(m.start, i)
                val z = box(m.end, i)
                drawLine(
                    Color.White.copy(alpha = 0.7f),
                    Offset((a[0] + a[2] / 2) * size.width, (a[1] + a[3] / 2) * size.height),
                    Offset((z[0] + z[2] / 2) * size.width, (z[1] + z[3] / 2) * size.height),
                    1.5.dp.toPx(),
                )
            }
        }
    }
}
