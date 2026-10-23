package io.github.artcraftandroid.filmcraft.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import io.github.artcraftandroid.filmcraft.ui.FcDialog
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcIconButton
import io.github.artcraftandroid.filmcraft.ui.FcSize
import io.github.artcraftandroid.filmcraft.ui.FcType
import io.github.artcraftandroid.filmcraft.ui.Glyph
import io.github.artcraftandroid.filmcraft.ui.Glyphs
import io.github.artcraftandroid.filmcraft.ui.Inter
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.glyph
import kotlin.math.max
import kotlin.math.min

/**
 * Pro mode: the desktop editor's panels. Tablets (either way up) and phones on their side get
 * the desktop layout, Source and Program monitors side by side over the timeline with Effect
 * Controls beside them; phones upright get the Program monitor over the timeline, with the
 * Source monitor and Effect Controls as tabs in its place.
 */
@Composable
fun ProEditor(ed: EditorController) {
    BackHandler {
        if (ed.proSelection.isNotEmpty()) ed.proSelect(null) else ed.closeEditor()
    }
    // a hardware keyboard's shortcuts, as on the desktop
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Fc.Black)
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
            .statusBarsPadding()
            .navigationBarsPadding()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { proShortcut(ed, it) }
            .testTag("screen_pro_editor"),
    ) {
        val desktop = maxWidth >= 600.dp || maxWidth > maxHeight
        if (desktop) DesktopLayout(ed) else PortraitLayout(ed)
        ed.titleAt?.let { at -> TitleDialog(ed, at) }
        ed.busy?.let { text ->
            Box(Modifier.fillMaxSize().background(Fc.Scrim).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Fc.Accent)
                    Spacer(Modifier.height(16.dp))
                    Text(text, style = FcType.Body, color = Fc.Title)
                }
            }
        }
    }
}

/** The editor's top bar in Pro mode: Home, the mode switch, undo/redo and Export. */
@Composable
private fun ProTopBar(ed: EditorController) {
    val st = ed.status
    Row(Modifier.fillMaxWidth().height(FcSize.TopBar).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FcIconButton(LucideIcons.House.glyph(), "Home", { ed.closeEditor() }, size = 44.dp, modifier = Modifier.testTag("editor_home_button"))
        Text(ed.project.name, style = FcType.Label, color = Fc.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        ModeSwitch(ed)
        Spacer(Modifier.width(4.dp))
        FcIconButton(LucideIcons.Undo2.glyph(), "Undo", { ed.undo() }, enabled = st?.canUndo == true, size = 44.dp, modifier = Modifier.testTag("editor_top_bar_undo_button"))
        FcIconButton(LucideIcons.Redo2.glyph(), "Redo", { ed.redo() }, enabled = st?.canRedo == true, size = 44.dp, modifier = Modifier.testTag("editor_top_bar_redo_button"))
        Box(
            Modifier
                .padding(start = 4.dp, end = 8.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (ed.duration > 0L) Color.White else Color.White.copy(alpha = 0.4f))
                .clickable(role = Role.Button) { ed.export() }
                .padding(horizontal = 14.dp)
                .testTag("editor_export_button"),
            contentAlignment = Alignment.Center,
        ) {
            Text("Export", style = FcType.Button, color = Color.Black)
        }
    }
}

/** The Simple / Pro switch in the editor's top bar (both modes edit the same project). */
@Composable
fun ModeSwitch(ed: EditorController) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Fc.Raised).padding(2.dp).testTag("editor_mode_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, pro) in listOf("Simple" to false, "Pro" to true)) {
            val on = ed.proMode == pro
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) Fc.Accent else Color.Transparent)
                    .clickable(role = Role.Tab) { ed.switchProMode(pro) }
                    .padding(horizontal = 12.dp)
                    .testTag("editor_mode_${label.lowercase()}")
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = FcType.Caption, color = if (on) Color.White else Fc.Body)
            }
        }
    }
}

@Composable
private fun DesktopLayout(ed: EditorController) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        ProTopBar(ed)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val w = maxWidth
            val h = maxHeight
            // Effect Controls: a side panel, drag its edge to resize
            val side = (if (ed.proSideWidth > 0f) with(density) { ed.proSideWidth.toDp() } else min(320f, w.value * 0.28f).dp).coerceIn(200.dp, max(200f, w.value * 0.5f).dp)
            val monitors = (if (ed.proMonitorHeight > 0f) with(density) { ed.proMonitorHeight.toDp() } else h * 0.45f).coerceIn(120.dp, max(120f, (h - 180.dp).value).dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(monitors)) {
                        SourceMonitor(Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(2.dp).fillMaxHeight().background(Fc.Hairline))
                        ProgramMonitor(ed, Modifier.weight(1f).fillMaxHeight())
                    }
                    Divider(horizontal = true, "Resize monitors") { d ->
                        val now = if (ed.proMonitorHeight > 0f) ed.proMonitorHeight else with(density) { monitors.toPx() }
                        ed.proMonitorHeight = (now + d).coerceIn(with(density) { 120.dp.toPx() }, with(density) { max(120f, (h - 180.dp).value).dp.toPx() })
                    }
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        ProToolBar(ed, vertical = true)
                        ProTimelinePanel(ed, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Divider(horizontal = false, "Resize Effect Controls") { d ->
                    val now = if (ed.proSideWidth > 0f) ed.proSideWidth else with(density) { side.toPx() }
                    ed.proSideWidth = (now - d).coerceIn(with(density) { 200.dp.toPx() }, with(density) { max(200f, w.value * 0.5f).dp.toPx() })
                }
                EffectControls(ed, Modifier.width(side).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun PortraitLayout(ed: EditorController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ProTopBar(ed)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val monitor = maxHeight * 0.38f
            Column(Modifier.fillMaxSize()) {
                ProgramMonitor(ed, Modifier.fillMaxWidth().height(monitor))
                Row(Modifier.fillMaxWidth().height(44.dp).background(Fc.Panel)) {
                    for ((i, label) in listOf("Timeline", "Source", "Effect Controls").withIndex()) {
                        val on = tab == i
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(role = Role.Tab) { tab = i }
                                .testTag("pro_tab_${label.lowercase().replace(' ', '_')}")
                                .semantics { selected = on },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = FcType.Caption, color = if (on) Fc.Title else Fc.Detail)
                            if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(2.dp).background(Fc.AccentLight))
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        ProToolBar(ed, vertical = false)
                        ProTimelinePanel(ed, compact = true, modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                    1 -> SourceMonitor(Modifier.fillMaxWidth().weight(1f))
                    else -> EffectControls(ed, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

/** A drag handle between panels: [horizontal] lies along the width (it resizes heights). */
@Composable
private fun Divider(horizontal: Boolean, description: String, onDrag: (Float) -> Unit) {
    Box(
        (if (horizontal) Modifier.fillMaxWidth().height(14.dp) else Modifier.width(14.dp).fillMaxHeight())
            .background(Fc.Black)
            .pointerInput(horizontal) {
                if (horizontal) {
                    detectVerticalDragGestures { change, d ->
                        change.consume()
                        onDrag(d)
                    }
                } else {
                    detectHorizontalDragGestures { change, d ->
                        change.consume()
                        onDrag(d)
                    }
                }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (horizontal) 32.dp else 4.dp, if (horizontal) 4.dp else 32.dp).clip(CircleShape).background(Fc.Detail))
    }
}

@Composable
private fun PanelTitle(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth().height(28.dp).background(Fc.Panel).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = FcType.Caption, color = Fc.Title)
        Spacer(Modifier.weight(1f))
        Text(trailing, style = FcType.Caption, color = Fc.AccentLight, maxLines = 1)
    }
}

/** The Program monitor: the sequence at the playhead, its timecode and the transport. */
@Composable
private fun ProgramMonitor(ed: EditorController, modifier: Modifier) {
    Column(modifier.background(Fc.Black).testTag("pro_program_monitor")) {
        PanelTitle("Program", TimelineMath.timecode(ed.duration, ed.rateNum, ed.rateDen))
        val trim = ed.trimView
        if (trim != null) TrimMonitor(ed, trim, Modifier.fillMaxWidth().weight(1f)) else Preview(ed, Modifier.fillMaxWidth().weight(1f))
        ProTransport(ed)
    }
}

@Composable
private fun ProTransport(ed: EditorController) {
    val has = ed.duration > 0L
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Fc.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            TimelineMath.timecode(ed.playhead, ed.rateNum, ed.rateDen),
            style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = Fc.AccentLight),
            modifier = Modifier.padding(horizontal = 8.dp).testTag("pro_program_timecode"),
        )
        TransportButton(ProGlyphs.MarkIn, "Mark In", "pro_program_mark_in", has) { ed.markIn() }
        TransportButton(ProGlyphs.MarkOut, "Mark Out", "pro_program_mark_out", has) { ed.markOut() }
        TransportButton(ProGlyphs.GoToIn, "Go to In", "pro_program_go_to_in", ed.pro?.markIn != null) { ed.goToIn() }
        TransportButton(Glyphs.StepBack, "Step Back 1 Frame", "editor_previous_frame_button", has) { ed.step(-1) }
        TransportButton(if (ed.playing) Glyphs.Pause else Glyphs.Play, if (ed.playing) "Stop" else "Play", "editor_play_button", has) { ed.togglePlay() }
        TransportButton(Glyphs.StepForward, "Step Forward 1 Frame", "editor_next_frame_button", has) { ed.step(1) }
        TransportButton(ProGlyphs.GoToOut, "Go to Out", "pro_program_go_to_out", ed.pro?.markOut != null) { ed.goToOut() }
        TransportButton(ProGlyphs.Marker, "Add Marker", "pro_program_add_marker", has) { ed.addMarker() }
        TransportButton(LucideIcons.ChevronLeft.glyph(), "Go to Previous Marker", "pro_program_prev_marker", ed.pro?.markers?.isNotEmpty() == true) { ed.goToMarker(next = false) }
        TransportButton(LucideIcons.ChevronRight.glyph(), "Go to Next Marker", "pro_program_next_marker", ed.pro?.markers?.isNotEmpty() == true) { ed.goToMarker(next = true) }
    }
}

@Composable
private fun TransportButton(glyph: Glyph, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FcIconButton(glyph, description, onClick, enabled = enabled, size = 44.dp, iconSize = 22.dp, modifier = Modifier.testTag(tag))
}

/** The Source monitor: the clip opened from the Project panel (three-point editing comes with it). */
@Composable
private fun SourceMonitor(modifier: Modifier) {
    Column(modifier.background(Fc.Black).testTag("pro_source_monitor")) {
        PanelTitle("Source")
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text("No clip open", style = FcType.BodySmall, color = Fc.Detail, textAlign = TextAlign.Center)
        }
    }
}

/** Effect Controls: the selected clip's timing (its effects and keyframes come with the panel's next part). */
@Composable
private fun EffectControls(ed: EditorController, modifier: Modifier) {
    val sel = ed.proSelection
    val p = ed.pro
    Column(modifier.background(Fc.Layer1).testTag("pro_effect_controls")) {
        PanelTitle("Effect Controls")
        val c = sel.firstOrNull { (p?.videoTracks?.any { t -> t.id == it.track }) == true } ?: sel.firstOrNull()
        if (c == null || p == null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Select a clip in the timeline", style = FcType.BodySmall, color = Fc.Detail, textAlign = TextAlign.Center)
            }
            return@Column
        }
        val track = (p.videoTracks + p.audioTracks).firstOrNull { it.id == c.track }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(c.name.ifBlank { "Clip" }, style = FcType.Label, color = Fc.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track?.code ?: "", style = FcType.BodySmall, color = Fc.Secondary)
            Spacer(Modifier.height(12.dp))
            val tc = { t: Long -> TimelineMath.timecode(t, p.rateNum, p.rateDen) }
            InfoRow("Start", tc(c.start))
            InfoRow("End", tc(c.start + c.duration))
            InfoRow("Duration", tc(c.duration))
            InfoRow("Source In", tc(c.sourceIn))
            if (c.speed != 1.0) InfoRow("Speed", "%.0f%%".format(c.speed * 100))
            if (sel.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text("${sel.size} clips selected", style = FcType.BodySmall, color = Fc.Detail)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FcType.BodySmall, color = Fc.Secondary, modifier = Modifier.weight(1f))
        Text(value, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Fc.AccentLight))
    }
}

/** The tool bar: a column beside the timeline on the desktop layout, a row above it on a phone upright. */
@Composable
private fun ProToolBar(ed: EditorController, vertical: Boolean) {
    val tools = ProTool.entries
    val content: @Composable () -> Unit = {
        for (t in tools) {
            val on = ed.proTool == t
            Box(
                Modifier
                    .size(44.dp)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Fc.Accent else Color.Transparent)
                    .clickable(role = Role.RadioButton) { ed.proTool = t }
                    .testTag("pro_tool_${t.name.lowercase()}")
                    .semantics {
                        contentDescription = "${t.label} tool (${t.key})"
                        selected = on
                    },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(toolGlyph(t), if (on) Color.White else Fc.Icon, size = 22.dp)
            }
        }
    }
    if (vertical) {
        Column(Modifier.width(48.dp).fillMaxHeight().background(Fc.Panel).verticalScroll(rememberScrollState()).testTag("pro_tool_bar"), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(Modifier.fillMaxWidth().height(48.dp).background(Fc.Panel).horizontalScroll(rememberScrollState()).testTag("pro_tool_bar"), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

private fun toolGlyph(t: ProTool): Glyph = when (t) {
    ProTool.Selection -> ProGlyphs.Selection
    ProTool.TrackSelectForward -> ProGlyphs.TrackSelectForward
    ProTool.RippleEdit -> ProGlyphs.RippleEdit
    ProTool.RollingEdit -> ProGlyphs.RollingEdit
    ProTool.RateStretch -> ProGlyphs.RateStretch
    ProTool.Razor -> LucideIcons.Scissors.glyph()
    ProTool.Slip -> ProGlyphs.Slip
    ProTool.Slide -> ProGlyphs.Slide
    ProTool.Hand -> ProGlyphs.Hand
    ProTool.Zoom -> ProGlyphs.Zoom
    ProTool.Type -> LucideIcons.Type.glyph()
}

/** The desktop's shortcuts on a hardware keyboard; true when the key was one. */
private fun proShortcut(ed: EditorController, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    if (ctrl) {
        return when (e.key) {
            Key.Z -> {
                if (shift) ed.redo() else ed.undo()
                true
            }
            else -> false
        }
    }
    if (e.isAltPressed) return false
    val tool = ProTool.entries.firstOrNull { e.key == keyOf(it.key) }
    if (tool != null && !shift) {
        ed.proTool = tool
        return true
    }
    when (e.key) {
        Key.J -> ed.shuttleBack()
        Key.K -> ed.shuttleStop()
        Key.L -> ed.shuttleForward()
        Key.Spacebar -> ed.togglePlay()
        Key.I -> ed.markIn()
        Key.O -> ed.markOut()
        Key.M -> ed.addMarker()
        Key.S -> ed.setSnapping(ed.pro?.snapping != true)
        Key.DirectionLeft -> ed.step(if (shift) -5 else -1)
        Key.DirectionRight -> ed.step(if (shift) 5 else 1)
        Key.Delete, Key.Backspace -> if (ed.proSelection.isNotEmpty()) ed.deleteSelection(ripple = shift)
        Key.Equals, Key.Plus, Key.NumPadAdd -> ed.proPxPerSecond = (ed.proPxPerSecond * 1.5f).coerceIn(2f, 100_000f)
        Key.Minus, Key.NumPadSubtract -> ed.proPxPerSecond = (ed.proPxPerSecond / 1.5f).coerceIn(2f, 100_000f)
        Key.Backslash -> ed.proPxPerSecond = 0f
        else -> return false
    }
    return true
}

private fun keyOf(c: Char): Key = when (c) {
    'V' -> Key.V
    'A' -> Key.A
    'B' -> Key.B
    'N' -> Key.N
    'R' -> Key.R
    'C' -> Key.C
    'Y' -> Key.Y
    'U' -> Key.U
    'H' -> Key.H
    'Z' -> Key.Z
    else -> Key.T
}

/** The Program monitor during a trim: the edit's frame, or the two sides of it, with how far it moved. */
@Composable
private fun TrimMonitor(ed: EditorController, v: TrimView, modifier: Modifier) {
    Column(modifier.background(Fc.Black).testTag("pro_trim_monitor")) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            v.frames.forEachIndexed { i, f ->
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0B0B0B)), contentAlignment = Alignment.Center) {
                    val pic = ed.trimPictures.getOrNull(i)
                    if (pic != null) {
                        Image(pic, contentDescription = f.label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        Text(if (f.item == null) "No picture" else "", style = FcType.BodySmall, color = Fc.Detail)
                    }
                    Text(
                        f.label,
                        style = FcType.Caption,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Fc.Scrim).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            v.caption,
            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Fc.AccentLight),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** The Type tool: a new title at [at] (on a free video track, as the phone's text). */
@Composable
private fun TitleDialog(ed: EditorController, at: Long) {
    var text by remember(at) { mutableStateOf("") }
    FcDialog("New title at ${TimelineMath.timecode(at, ed.rateNum, ed.rateDen)}", "Add", onConfirm = { ed.addTitle(at, text) }, onDismiss = { ed.titleAt = null }, confirmEnabled = text.isNotBlank()) {
        BasicTextField(
            text,
            { text = it },
            textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = Fc.Title),
            cursorBrush = SolidColor(Fc.Title),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Fc.Raised).padding(12.dp).testTag("pro_title_field"),
        )
    }
}
