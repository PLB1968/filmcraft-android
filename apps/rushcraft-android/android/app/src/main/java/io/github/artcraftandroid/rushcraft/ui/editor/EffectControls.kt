package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.rushcraft.core.EffectInfo
import io.github.artcraftandroid.rushcraft.core.KeyframeInfo
import io.github.artcraftandroid.rushcraft.core.ParamInfo
import io.github.artcraftandroid.rushcraft.core.ParamType
import io.github.artcraftandroid.rushcraft.core.ProClip
import io.github.artcraftandroid.rushcraft.core.TransitionControls
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcDialog
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.Glyph
import io.github.artcraftandroid.rushcraft.ui.GlyphIcon
import io.github.artcraftandroid.rushcraft.ui.Inter
import io.github.artcraftandroid.rushcraft.ui.LucideIcons
import io.github.artcraftandroid.rushcraft.ui.glyph
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Effect Controls: the selected clip's Motion, Opacity (with its blend mode), Time Remapping, its
 * sound's Volume and every effect applied to it, each parameter with its value at the playhead.
 * A parameter's stopwatch turns keyframing on; then changes make keyframes at the playhead, and
 * its row shows the clip's keyframe timeline: tap a keyframe to go to it, drag it to move it,
 * long-press it for Linear, Bezier or Hold.
 */
@Composable
internal fun EffectControlsPanel(ed: EditorController, modifier: Modifier) {
    // a picked crossfade or fade shows its own settings
    val picked = ed.proTransition
    if (picked != null) {
        LaunchedEffect(picked, ed.pro?.revision) { ed.loadTransition() }
        val c = ed.transitionControls?.takeIf { it.id == picked }
        Column(modifier.background(Fc.Layer1).testTag("pro_effect_controls")) {
            Row(Modifier.fillMaxWidth().height(28.dp).background(Fc.Panel).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Effect Controls", style = FcType.Caption, color = Fc.Title)
                Spacer(Modifier.width(8.dp))
                Text(c?.name ?: "", style = FcType.Caption, color = Fc.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (c != null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TransitionSection(ed, c)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        return
    }
    val p = ed.pro
    val sel = ed.proSelection
    val clip = sel.firstOrNull { c -> p?.videoTracks?.any { it.id == c.track } == true } ?: sel.firstOrNull()
    // values are read at the playhead: again when it stops somewhere new (not while playing)
    val at = if (ed.playing) -1L else ed.playhead
    LaunchedEffect(clip?.id, p?.revision, at) { if (at >= 0L || clip == null) ed.loadEffects(clip?.id) }
    Column(modifier.background(Fc.Layer1).testTag("pro_effect_controls")) {
        Row(Modifier.fillMaxWidth().height(28.dp).background(Fc.Panel).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Effect Controls", style = FcType.Caption, color = Fc.Title)
            Spacer(Modifier.width(8.dp))
            Text(clip?.name ?: "", style = FcType.Caption, color = Fc.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (clip == null || p == null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Select a clip in the timeline", style = FcType.BodySmall, color = Fc.Detail)
            }
            return@Column
        }
        val effects = if (ed.effectsClip == clip.id) ed.effects else emptyList()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            for (e in effects) EffectSection(ed, clip, e)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EffectSection(ed: EditorController, clip: ProClip, e: EffectInfo) {
    var open by remember(e.clip, e.id, e.index) { mutableStateOf(e.fixed) }
    Column(Modifier.fillMaxWidth().testTag("pro_fx_${e.id}")) {
        Row(Modifier.fillMaxWidth().height(44.dp).background(Fc.Panel).clickable(role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(if (open) LucideIcons.ChevronDown.glyph() else LucideIcons.ChevronRight.glyph(), Fc.Secondary, size = 18.dp, modifier = Modifier.padding(start = 8.dp))
            // the fx switch
            Box(
                Modifier
                    .size(44.dp)
                    .helpClick(ed, "fx", role = Role.Switch) { ed.toggleEffect(e) }
                    .testTag("pro_fx_switch_${e.id}")
                    .semantics {
                        contentDescription = "Turn ${e.name} ${if (e.enabled) "off" else "on"}"
                        selected = e.enabled
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("fx", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = if (e.enabled) Fc.AccentLight else Fc.Disabled))
            }
            Text(e.name, style = FcType.Label.copy(fontSize = 15.sp), color = if (e.enabled) Fc.Title else Fc.Detail, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!e.fixed) {
                Box(
                    Modifier.size(44.dp).helpClick(ed, "removeEffect") { ed.removeEffect(e) }.semantics { contentDescription = "Remove ${e.name}" },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(LucideIcons.Trash2.glyph(), Fc.Secondary, size = 18.dp) }
            }
        }
        if (open) {
            for (prm in e.params) ParamRow(ed, clip, e, prm)
        }
    }
}

/**
 * A crossfade or fade, like any effect: its type, its span over the cut (a ramp from the first
 * frame to the last, with a keyframe at each end), length, alignment, Reverse and its own settings.
 */
@Composable
private fun TransitionSection(ed: EditorController, c: TransitionControls) {
    val frame = TimelineMath.tickOfFrame(1, ed.rateNum, ed.rateDen)
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp).testTag("pro_transition_${c.id}")) {
        // its type
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(44.dp))
            Text("Type", style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.weight(1f))
            var open by remember { mutableStateOf(false) }
            Box {
                Text(
                    c.name,
                    style = FcType.BodySmall,
                    color = Fc.AccentLight,
                    maxLines = 1,
                    modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 8.dp, vertical = 12.dp).testTag("pro_transition_type"),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Fc.Panel) {
                    for (t in c.choices) {
                        DropdownMenuItem(text = { Text(t.name, style = FcType.Body, color = if (t.id == c.effect) Fc.AccentLight else Fc.Body) }, onClick = {
                            open = false
                            if (t.id != c.effect) ed.changeTransition(c.id, t.id)
                        })
                    }
                }
            }
        }
        // where it sits: a ramp with a keyframe at its first and last frame
        TransitionRamp(ed, c, Modifier.fillMaxWidth().height(44.dp).padding(start = 44.dp))
        // its length, as hot text in seconds
        val seconds = ParamInfo(
            id = "duration", name = "Duration", kind = ParamType.NUMBER, value = listOf(c.duration.toDouble() / TPS),
            min = frame.toDouble() / TPS, max = 30.0, softMin = 0.0, softMax = 5.0, unit = "s", decimals = 2u,
            choices = emptyList(), animatable = false, animated = false, keyframes = emptyList(),
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(44.dp))
            Text("Duration", style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.weight(1f))
            SmallButton(ed, "transitionShorter", LucideIcons.Minus.glyph(), "One frame shorter", "pro_transition_shorter", c.duration > frame) {
                ed.editTransition(c.id, duration = c.duration - frame)
            }
            ValueEditor(ed, "transition", seconds) { _, v, merge, begin ->
                val ticks = TimelineMath.snap(((v.firstOrNull() ?: 0.0) * TPS).toLong(), ed.rateNum, ed.rateDen).coerceAtLeast(frame)
                ed.editTransition(c.id, duration = ticks, merge = merge, begin = begin)
            }
            SmallButton(ed, "transitionLonger", LucideIcons.Plus.glyph(), "One frame longer", "pro_transition_longer", true) {
                ed.editTransition(c.id, duration = c.duration + frame)
            }
        }
        // its alignment on the cut (a fade from or to nothing has only its own)
        if (c.from != null && c.to != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(44.dp))
                Text("Alignment", style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.weight(1f))
                for ((id, label) in listOf("center" to "Center", "start" to "Start", "end" to "End")) {
                    Text(
                        label,
                        style = FcType.BodySmall,
                        color = if (c.align == id) Color.White else Fc.Secondary,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (c.align == id) Fc.Accent else Color.Transparent)
                            .helpClick(ed, "transitionAlign", role = Role.RadioButton) { ed.editTransition(c.id, align = id) }
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                            .testTag("pro_transition_align_$id"),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(44.dp))
            Text("Reverse", style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.weight(1f))
            Switch(
                checked = c.reverse,
                onCheckedChange = { ed.editTransition(c.id, reverse = it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Fc.Accent),
                modifier = Modifier.testTag("pro_transition_reverse"),
            )
        }
        // its own settings
        for (p in c.params) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(44.dp))
                Text(p.name, style = FcType.BodySmall, color = Fc.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ValueEditor(ed, "transition", p) { prm, v, merge, begin -> ed.setTransitionParam(c.id, prm, v, merge, begin) }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Reset",
                style = FcType.BodySmall,
                color = Fc.AccentLight,
                modifier = Modifier.heightIn(min = 44.dp).helpClick(ed, "transitionReset") { ed.resetTransition(c.id) }.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * The transition over its clips: the outgoing clip's tail and the incoming clip's head, the cut,
 * and the transition as a ramp from 0 to 100 % with a keyframe at each end. Tap to go to its
 * start, middle or end.
 */
@Composable
private fun TransitionRamp(ed: EditorController, c: TransitionControls, modifier: Modifier) {
    val end = c.start + c.duration
    // a little of each clip on either side
    val pad = (c.duration / 2).coerceAtLeast(1L)
    val from = c.start - pad
    val span = (end + pad - from).coerceAtLeast(1L)
    val cut = when (c.align) {
        "start" -> c.start
        "end" -> end
        else -> c.start + c.duration / 2
    }
    Canvas(
        modifier
            .pointerInput(c.id, c.start, c.duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val t = from + (down.position.x / size.width * span).toLong()
                    // to the nearest of its start, middle and end
                    ed.seek(listOf(c.start, c.start + c.duration / 2, end).minBy { abs(it - t) }, draft = false)
                }
            }
            .semantics { contentDescription = "${c.name} over the cut" },
    ) {
        fun x(t: Long) = (t - from).toFloat() / span * size.width
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        // the two clips
        drawRect(Color(0xFF4A5BB5).copy(alpha = 0.55f), Offset(0f, top), Size(x(cut), bottom - top))
        drawRect(Color(0xFF5B8A5B).copy(alpha = 0.55f), Offset(x(cut), top), Size(size.width - x(cut), bottom - top))
        drawLine(Color.Black, Offset(x(cut), top), Offset(x(cut), bottom), 2.dp.toPx())
        // the ramp and its keyframes
        val a = Offset(x(c.start), if (c.reverse) top else bottom)
        val b = Offset(x(end), if (c.reverse) bottom else top)
        drawRect(Color.White.copy(alpha = 0.18f), Offset(a.x, top), Size(b.x - a.x, bottom - top))
        drawLine(Fc.AccentLight, a, b, 2.dp.toPx())
        drawKeyframe(a, "linear", Color.White, radius = 5.dp.toPx())
        drawKeyframe(b, "linear", Color.White, radius = 5.dp.toPx())
        // the playhead
        val px = x(ed.playhead)
        if (px in 0f..size.width) drawLine(Fc.AccentLight, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
    }
}

/** A parameter: its stopwatch, name and value; with its keyframes when it is animated. */
@Composable
private fun ParamRow(ed: EditorController, clip: ProClip, e: EffectInfo, p: ParamInfo) {
    var reset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp).testTag("pro_param_${e.id}_${p.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.animatable) {
                Box(
                    Modifier
                        .size(44.dp)
                        .helpClick(ed, "stopwatch", role = Role.Switch) { ed.toggleAnimation(e, p) }
                        .testTag("pro_stopwatch_${e.id}_${p.id}")
                        .semantics {
                            contentDescription = "Toggle animation of ${p.name}"
                            selected = p.animated
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(ProGlyphs.Stopwatch, if (p.animated) Fc.AccentLight else Fc.Secondary, size = 20.dp)
                }
            } else {
                Spacer(Modifier.width(44.dp))
            }
            Box(Modifier.weight(1f)) {
                Text(
                    p.name,
                    style = FcType.BodySmall,
                    color = Fc.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClickLabel = "Reset ${p.name}") { reset = true },
                )
                DropdownMenu(expanded = reset, onDismissRequest = { reset = false }, containerColor = Fc.Panel) {
                    DropdownMenuItem(text = { Text("Reset ${p.name}", style = FcType.Body, color = Fc.Body) }, onClick = {
                        reset = false
                        ed.resetParam(e, p)
                    })
                }
            }
            ValueEditor(ed, e.id, p) { prm, v, merge, begin -> ed.setParam(e, prm, v, merge, begin) }
        }
        if (p.animated) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                val onKey = p.keyframes.any { abs(it.time - ed.playhead) < TimelineMath.tickOfFrame(1, ed.rateNum, ed.rateDen) }
                SmallButton(ed, "kfPrev", LucideIcons.ChevronLeft.glyph(), "Go to Previous Keyframe", "pro_kf_prev_${p.id}", p.keyframes.any { it.time < ed.playhead }) { ed.goToKeyframe(p, next = false) }
                SmallButton(ed, "kfToggle", ProGlyphs.Keyframe, if (onKey) "Remove Keyframe" else "Add Keyframe", "pro_kf_toggle_${p.id}", true, lit = onKey) { ed.toggleKeyframe(e, p) }
                SmallButton(ed, "kfNext", LucideIcons.ChevronRight.glyph(), "Go to Next Keyframe", "pro_kf_next_${p.id}", p.keyframes.any { it.time > ed.playhead }) { ed.goToKeyframe(p, next = true) }
                KeyframeStrip(ed, clip, e, p, Modifier.weight(1f).height(32.dp))
            }
        }
    }
}

@Composable
private fun SmallButton(ed: EditorController, help: String, glyph: Glyph, description: String, tag: String, enabled: Boolean, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).helpClick(ed, help, enabled, onClick = onClick).testTag(tag).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (!enabled) Fc.Disabled else if (lit) Fc.AccentLight else Fc.Icon, size = 18.dp)
    }
}

private fun fmt(v: Double, decimals: Int): String = if (v.isNaN()) "auto" else "%.${decimals.coerceIn(0, 3)}f".format(v)

/** A parameter's new value: (parameter, value, merge into the drag's undo step, a drag's first change). */
internal typealias SetParam = (ParamInfo, List<Double>, Boolean, Boolean) -> Unit

/**
 * The value: hot text to drag (or tap to type) for numbers, points and angles; a switch; a menu of
 * choices. [owner] names it in test tags (the effect, or the transition); [set] changes it.
 */
@Composable
internal fun ValueEditor(ed: EditorController, owner: String, p: ParamInfo, set: SetParam) {
    when (p.kind) {
        ParamType.NUMBER, ParamType.ANGLE -> HotNumber(ed, owner, p, 0, set)
        ParamType.POINT -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HotNumber(ed, owner, p, 0, set)
            HotNumber(ed, owner, p, 1, set)
        }
        ParamType.TOGGLE -> Switch(
            checked = (p.value.firstOrNull() ?: 0.0) >= 0.5,
            onCheckedChange = { set(p, listOf(if (it) 1.0 else 0.0), false, true) },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Fc.Accent),
            modifier = Modifier.testTag("pro_value_${owner}_${p.id}"),
        )
        ParamType.CHOICE -> {
            var open by remember { mutableStateOf(false) }
            val i = (p.value.firstOrNull() ?: 0.0).toInt()
            Box {
                Text(
                    p.choices.getOrNull(i) ?: "",
                    style = FcType.BodySmall,
                    color = Fc.AccentLight,
                    maxLines = 1,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.DropdownList) { open = true }
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .testTag("pro_value_${owner}_${p.id}"),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Fc.Panel) {
                    p.choices.forEachIndexed { k, name ->
                        DropdownMenuItem(text = { Text(name, style = FcType.Body, color = if (k == i) Fc.AccentLight else Fc.Body) }, onClick = {
                            open = false
                            set(p, listOf(k.toDouble()), false, true)
                        })
                    }
                }
            }
        }
        ParamType.COLOUR -> {
            val c = p.value
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Fc.Divider, RoundedCornerShape(4.dp)).background(
                    if (c.size >= 3) Color(c[0].toFloat().coerceIn(0f, 1f), c[1].toFloat().coerceIn(0f, 1f), c[2].toFloat().coerceIn(0f, 1f)) else Color.Black,
                ),
            )
        }
        ParamType.OTHER -> Text("–", style = FcType.BodySmall, color = Fc.Detail)
    }
}

/**
 * One number of a parameter as Premiere's hot text: drag sideways to change it (one undo step a
 * drag), tap to type it.
 */
@Composable
private fun HotNumber(@Suppress("UNUSED_PARAMETER") ed: EditorController, owner: String, p: ParamInfo, part: Int, set: SetParam) {
    var shown by remember(p.value, part) { mutableStateOf(p.value.getOrNull(part) ?: 0.0) }
    var typing by remember { mutableStateOf(false) }
    val now by rememberUpdatedState(p)
    // a pixel's worth of change: the slider range over about 300 px, else a unit
    val step = when (p.kind) {
        ParamType.NUMBER -> max((p.softMax - p.softMin) / 300.0, 1e-3)
        ParamType.ANGLE -> 0.5
        else -> 1.0
    }
    fun send(v: Double, begin: Boolean) {
        val cur = now.value.toMutableList()
        while (cur.size <= part) cur.add(0.0)
        cur[part] = v
        set(now, cur, true, begin)
    }
    Text(
        fmt(shown, p.decimals.toInt()) + (if (p.kind == ParamType.NUMBER && p.unit.isNotEmpty()) " ${p.unit}" else if (p.kind == ParamType.ANGLE) "°" else ""),
        style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Fc.AccentLight, fontFeatureSettings = "tnum"),
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(onClickLabel = "Type ${p.name}") { typing = true }
            .pointerInput(p.id, part) {
                var first = true
                detectHorizontalDragGestures(onDragStart = { first = true }) { change, dx ->
                    change.consume()
                    val base = if (shown.isNaN()) 0.0 else shown
                    shown = (base + dx * step).coerceIn(now.min, now.max)
                    send(shown, first)
                    first = false
                }
            }
            .padding(horizontal = 6.dp, vertical = 12.dp)
            .testTag("pro_value_${owner}_${p.id}_$part"),
    )
    if (typing) {
        var text by remember { mutableStateOf(if (shown.isNaN()) "" else fmt(shown, p.decimals.toInt())) }
        FcDialog(p.name, "Set", onConfirm = {
            typing = false
            text.replace(',', '.').trim().toDoubleOrNull()?.let {
                shown = it.coerceIn(p.min, p.max)
                send(shown, begin = true)
            }
        }, onDismiss = { typing = false }) {
            BasicTextField(
                text,
                { text = it },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = Fc.Title),
                cursorBrush = SolidColor(Fc.Title),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Fc.Raised).padding(12.dp).testTag("pro_value_field"),
            )
        }
    }
}

/**
 * A parameter's keyframes along the clip: the playhead, a mark per keyframe (a diamond for
 * linear, a circle for Bezier, a square for hold). Tap one to go to it (elsewhere to move the
 * playhead), drag one to move it, long-press one for its interpolation or to delete it.
 */
@Composable
private fun KeyframeStrip(ed: EditorController, clip: ProClip, e: EffectInfo, p: ParamInfo, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    var menuFor by remember { mutableStateOf<KeyframeInfo?>(null) }
    var dragging by remember { mutableStateOf<KeyframeInfo?>(null) }
    var dragDx by remember { mutableStateOf(0f) }
    val now by rememberUpdatedState(p)
    val clipNow by rememberUpdatedState(clip)
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(Fc.Lane)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = clipNow
                        val w = size.width.toFloat()
                        fun tOf(x: Float) = c.start + (x / w * c.duration).roundToLong()
                        fun xOf(t: Long) = ((t - c.start).toFloat() / max(1L, c.duration) * w)
                        val near = now.keyframes.minByOrNull { abs(xOf(it.time) - down.position.x) }?.takeIf { abs(xOf(it.time) - down.position.x) < 16.dp.toPx() }
                        var moved = false
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                    break
                                }
                            }
                        }
                        when {
                            up == null -> {
                                if (near != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuFor = near
                                }
                                while (awaitPointerEvent().changes.any { it.pressed }) Unit
                            }
                            moved && near != null -> {
                                dragging = near
                                dragDx = 0f
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull() ?: break
                                    if (!ch.pressed) break
                                    dragDx += ch.positionChange().x
                                    ch.consume()
                                }
                                val k = dragging
                                dragging = null
                                if (k != null) {
                                    val dt = (dragDx / w * c.duration).roundToLong()
                                    val to = TimelineMath.snap((k.time + dt).coerceIn(c.start, c.start + c.duration - 1), ed.rateNum, ed.rateDen)
                                    if (to != k.time) ed.moveKeyframe(e, now, k, k.mediaTime + ((to - k.time) * c.speed).roundToLong())
                                }
                            }
                            moved -> while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                ed.seek(tOf(ch.position.x).coerceIn(c.start, c.start + c.duration - 1))
                                ch.consume()
                            }
                            near != null -> ed.seek(near.time, draft = false)
                            else -> ed.seek(tOf(down.position.x), draft = false)
                        }
                    }
                }
                .semantics { contentDescription = "Keyframes of ${p.name}" },
        ) {
            val w = size.width
            val c = clip
            fun xOf(t: Long) = ((t - c.start).toFloat() / max(1L, c.duration) * w)
            drawLine(Fc.Divider, Offset(0f, size.height / 2), Offset(w, size.height / 2), 1.dp.toPx())
            val px = xOf(ed.playhead)
            if (px in 0f..w) drawLine(Fc.AccentLight, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
            for (k in p.keyframes) {
                val x = xOf(k.time) + if (dragging == k) dragDx else 0f
                drawKeyframe(Offset(x, size.height / 2), k.interpolation, if (abs(k.time - ed.playhead) < TimelineMath.tickOfFrame(1, ed.rateNum, ed.rateDen)) Fc.AccentLight else Fc.Title)
            }
        }
        DropdownMenu(expanded = menuFor != null, onDismissRequest = { menuFor = null }, containerColor = Fc.Panel) {
            val k = menuFor
            for ((label, id) in listOf("Linear" to "linear", "Bezier" to "bezier", "Auto Bezier" to "autoBezier", "Hold" to "hold", "Ease In" to "easeIn", "Ease Out" to "easeOut")) {
                DropdownMenuItem(text = { Text(label, style = FcType.Body, color = if (k?.interpolation == id) Fc.AccentLight else Fc.Body) }, onClick = {
                    menuFor = null
                    if (k != null) ed.setInterpolation(e, p, k, id)
                })
            }
            DropdownMenuItem(text = { Text("Delete Keyframe", style = FcType.Body, color = Fc.Negative) }, onClick = {
                menuFor = null
                if (k != null) ed.deleteKeyframe(e, p, k)
            })
        }
    }
}

/** A keyframe mark: a diamond (linear and eases), a circle (Bezier kinds), a square (hold). */
internal fun DrawScope.drawKeyframe(at: Offset, interpolation: String, color: Color, radius: Float = 0f) {
    val r = if (radius > 0f) radius else 5.dp.toPx()
    when {
        interpolation == "hold" -> drawRect(color, Offset(at.x - r * 0.8f, at.y - r * 0.8f), Size(r * 1.6f, r * 1.6f))
        interpolation.contains("ezier", ignoreCase = true) -> drawCircle(color, r * 0.85f, at)
        else -> {
            val d = Path().apply {
                moveTo(at.x, at.y - r)
                lineTo(at.x + r, at.y)
                lineTo(at.x, at.y + r)
                lineTo(at.x - r, at.y)
                close()
            }
            drawPath(d, color)
        }
    }
}
