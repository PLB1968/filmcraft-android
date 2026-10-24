package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.rushcraft.core.Anim
import io.github.artcraftandroid.rushcraft.core.ClipInfo
import io.github.artcraftandroid.rushcraft.core.Corner
import io.github.artcraftandroid.rushcraft.core.FieldKind
import io.github.artcraftandroid.rushcraft.core.KenBurns
import io.github.artcraftandroid.rushcraft.core.TitleField
import io.github.artcraftandroid.rushcraft.core.TitleTemplate
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcSize
import io.github.artcraftandroid.rushcraft.ui.FcSlider
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.Inter
import io.github.artcraftandroid.rushcraft.ui.SelectChip
import io.github.artcraftandroid.rushcraft.ui.ToolPanel

/** Colours a title's colour fields offer. */
private val TITLE_COLORS = listOf("#ffffff", "#111827", "#dc2626", "#f97316", "#facc15", "#22c55e", "#22d3ee", "#1d4ed8", "#a855f7")

/**
 * Titles (Pro mode): the selected title's fields (its text, colours, sizes, what shows), then
 * the templates to add at the playhead: lower thirds, titles, end cards, credits and callouts,
 * each placed above the main track with an entrance and an exit.
 */
@Composable
fun TitlesPanel(ed: EditorController, close: () -> Unit, modifier: Modifier = Modifier) {
    val sel = ed.selectedClip
    var fields by remember { mutableStateOf<List<TitleField>>(emptyList()) }
    LaunchedEffect(sel?.id, ed.timeline?.revision) { fields = sel?.let { ed.titleFields(it.id) }.orEmpty() }
    val templates = remember { ed.titleTemplates() }
    ToolPanel("Titles", close, modifier, tag = "titles_panel") {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        if (sel != null && fields.isNotEmpty()) {
            Text("Selected title", style = FcType.Label, color = Fc.Secondary, modifier = pad)
            Spacer(Modifier.height(8.dp))
            for (f in fields) TitleFieldRow(ed, sel.id, f)
            Spacer(Modifier.height(16.dp))
        }
        Text("Add at the playhead", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        for ((category, list) in templates.groupBy { it.category }) {
            Spacer(Modifier.height(12.dp))
            Text(category, style = FcType.Caption, color = Fc.Detail, modifier = pad)
            Spacer(Modifier.height(8.dp))
            for (row in list.chunked(2)) {
                Row(pad.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (t in row) TemplateTile(ed, t, Modifier.weight(1f))
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A template: its preview on a dark card, its name under it; a tap adds it. */
@Composable
private fun TemplateTile(ed: EditorController, t: TitleTemplate, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(null, t.id) { value = ed.titleThumbnail(t.id, 360) }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { ed.addTitleTemplate(t.id) }
            .semantics { contentDescription = "Add ${t.name}: ${t.description}" }
            .testTag("title_${t.id.substringAfter(':')}"),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2F3A)), contentAlignment = Alignment.Center) {
            img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Spacer(Modifier.height(6.dp))
        Text(t.name, style = FcType.Caption, color = Fc.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One field of the selected title. */
@Composable
private fun TitleFieldRow(ed: EditorController, clip: ULong, f: TitleField) {
    val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
    when (f.kind) {
        FieldKind.TEXT -> {
            // typed here, not reread while typing (the timeline refreshes under it)
            var text by remember(clip, f.id) { mutableStateOf(f.value) }
            var first by remember(clip, f.id) { mutableStateOf(true) }
            Text(f.name, style = FcType.Caption, color = Fc.Detail, modifier = pad)
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                text,
                {
                    text = it
                    ed.setTitleField(clip, f.id, it, merge = !first)
                    first = false
                },
                textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = Fc.Title),
                cursorBrush = SolidColor(Fc.Title),
                maxLines = 8,
                modifier = pad.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Fc.Raised).padding(12.dp).testTag("title_field_${f.id}"),
            )
            Spacer(Modifier.height(10.dp))
        }
        FieldKind.COLOR -> {
            Text(f.name, style = FcType.Caption, color = Fc.Detail, modifier = pad)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FcSize.PanelPadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in TITLE_COLORS) {
                    val on = c.equals(f.value, ignoreCase = true)
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(c)))
                            .border(if (on) 3.dp else 1.dp, if (on) Fc.Title else Fc.Divider, CircleShape)
                            .clickable(role = Role.RadioButton) { ed.setTitleField(clip, f.id, c, merge = false) }
                            .semantics { contentDescription = "${f.name} $c" },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        FieldKind.SLIDER -> {
            val lo = f.min
            val hi = if (f.max > f.min) f.max else f.min + 1.0
            val now = f.value.toDoubleOrNull() ?: lo
            var shown by remember(clip, f.id, f.value) { mutableFloatStateOf(((now - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)) }
            var first by remember { mutableStateOf(true) }
            Row(pad.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.width(96.dp), maxLines = 1)
                FcSlider(
                    shown,
                    { v ->
                        shown = v
                        ed.setTitleField(clip, f.id, (lo + v * (hi - lo)).toString(), merge = !first)
                        first = false
                    },
                    onDone = { first = true },
                    modifier = Modifier.weight(1f),
                    description = f.name,
                )
                Text("%.0f".format(lo + shown * (hi - lo)), style = FcType.BodySmall, color = Fc.AccentLight, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
        FieldKind.CHECKBOX -> {
            val on = f.value == "true"
            Row(pad.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.weight(1f))
                SelectChip(if (on) "Shown" else "Hidden", on, { ed.setTitleField(clip, f.id, (!on).toString(), merge = false) })
            }
        }
    }
}

private val ANIMS = listOf(
    Anim.NONE to "None", Anim.FADE to "Fade", Anim.SLIDE_RIGHT to "Slide →", Anim.SLIDE_LEFT to "Slide ←",
    Anim.SLIDE_UP to "Slide ↑", Anim.SLIDE_DOWN to "Slide ↓", Anim.POP to "Pop", Anim.ZOOM to "Zoom", Anim.SPIN to "Spin",
)
private val KEN_BURNS = listOf(KenBurns.NONE to "None", KenBurns.ZOOM_IN to "Zoom in", KenBurns.ZOOM_OUT to "Zoom out", KenBurns.PAN_LEFT to "Pan left", KenBurns.PAN_RIGHT to "Pan right")
private val CORNERS = listOf(Corner.TOP_LEFT to "Top left", Corner.TOP_RIGHT to "Top right", Corner.BOTTOM_LEFT to "Bottom left", Corner.BOTTOM_RIGHT to "Bottom right", Corner.CENTER to "Centre")

/**
 * Motion (Pro mode): the selected clip's entrance and exit and how long they take, a Ken Burns
 * move, picture-in-picture in a corner, and keyframing by dragging on the Program monitor.
 */
@Composable
fun MotionPanel(ed: EditorController, c: ClipInfo, close: () -> Unit, modifier: Modifier = Modifier) {
    var enter by remember(c.id) { mutableStateOf(Anim.NONE) }
    var exit by remember(c.id) { mutableStateOf(Anim.NONE) }
    var seconds by remember { mutableDoubleStateOf(0.5) }
    var burns by remember(c.id) { mutableStateOf(KenBurns.NONE) }
    var amount by remember { mutableDoubleStateOf(0.2) }
    var corner by remember(c.id) { mutableStateOf<Corner?>(null) }
    var size by remember { mutableDoubleStateOf(0.35) }
    var recording by remember(c.id) { mutableStateOf(false) }
    LaunchedEffect(c.id, ed.timeline?.revision) { recording = ed.motionPath(c.id)?.recording == true }
    ToolPanel("Motion", close, modifier, tag = "motion_panel") {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        val animate = { ed.animateClip(c.id, enter, exit, seconds) }
        Text("Animate in", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        Spacer(Modifier.height(8.dp))
        ChipRow(ANIMS, enter, "motion_in") { enter = it; animate() }
        Spacer(Modifier.height(12.dp))
        Text("Animate out", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        Spacer(Modifier.height(8.dp))
        ChipRow(ANIMS, exit, "motion_out") { exit = it; animate() }
        ValueSlider("Length", "%.1f s".format(seconds), ((seconds - 0.2) / 1.8).toFloat(), { seconds = 0.2 + it * 1.8 }, { if (enter != Anim.NONE || exit != Anim.NONE) animate() })
        Spacer(Modifier.height(16.dp))
        Text("Ken Burns", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        Spacer(Modifier.height(8.dp))
        ChipRow(KEN_BURNS, burns, "motion_kb") { burns = it; ed.kenBurns(c.id, it, amount) }
        ValueSlider("Amount", "%.0f %%".format(amount * 100), ((amount - 0.05) / 0.45).toFloat(), { amount = 0.05 + it * 0.45 }, { if (burns != KenBurns.NONE) ed.kenBurns(c.id, burns, amount) })
        Spacer(Modifier.height(16.dp))
        Text("Picture in picture", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        Spacer(Modifier.height(8.dp))
        ChipRow(CORNERS, corner, "motion_pip") { corner = it; ed.pictureInPicture(c.id, it, size) }
        ValueSlider("Size", "%.0f %%".format(size * 100), ((size - 0.15) / 0.75).toFloat(), { size = 0.15 + it * 0.75 }, { corner?.let { ed.pictureInPicture(c.id, it, size) } })
        Spacer(Modifier.height(16.dp))
        Text("Keyframes", style = FcType.Label, color = Fc.Secondary, modifier = pad)
        Spacer(Modifier.height(8.dp))
        Row(pad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Turn on, move the playhead, then drag, pinch or twist the clip on the Program monitor: each change is a keyframe.",
                style = FcType.BodySmall, color = Fc.Detail, modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            SelectChip(if (recording) "On" else "Off", recording, { ed.setMotionRecording(c.id, !recording); recording = !recording }, tag = "motion_keyframe")
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A row of chips that scrolls sideways. */
@Composable
private fun <T> ChipRow(items: List<Pair<T, String>>, selected: T?, tag: String, pick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FcSize.PanelPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, (v, label) -> SelectChip(label, v == selected, { pick(v) }, tag = "${tag}_$i") }
    }
}

/** A labelled slider showing its value; [done] when the finger lifts. */
@Composable
private fun ValueSlider(label: String, value: String, fraction: Float, change: (Float) -> Unit, done: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.width(72.dp), maxLines = 1)
        FcSlider(fraction.coerceIn(0f, 1f), change, onDone = done, modifier = Modifier.weight(1f), description = label)
        Text(value, style = FcType.BodySmall, color = Fc.AccentLight, modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}
