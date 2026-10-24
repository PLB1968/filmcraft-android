@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.artcraftandroid.filmcraft.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.filmcraft.core.BinKind
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.foundation.layout.FlowRow
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
        SideEffect { ed.proDesktop = desktop }
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
        HelpIconButton(ed, LucideIcons.House.glyph(), "Home", "home", "editor_home_button", iconSize = 24.dp) { ed.closeEditor() }
        Text(ed.project.name, style = FcType.Label, color = Fc.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        ModeSwitch(ed)
        Spacer(Modifier.width(4.dp))
        HelpIconButton(ed, LucideIcons.Undo2.glyph(), "Undo", "undo", "editor_top_bar_undo_button", st?.canUndo == true, iconSize = 24.dp) { ed.undo() }
        HelpIconButton(ed, LucideIcons.Redo2.glyph(), "Redo", "redo", "editor_top_bar_redo_button", st?.canRedo == true, iconSize = 24.dp) { ed.redo() }
        Box(
            Modifier
                .padding(start = 4.dp, end = 8.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (ed.duration > 0L) Color.White else Color.White.copy(alpha = 0.4f))
                .helpClick(ed, "export") { ed.export() }
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
                    .helpClick(ed, "mode", role = Role.Tab) { ed.switchProMode(pro) }
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
            val sideMax = max(200f, w.value * 0.5f).dp
            val side = (ed.proPanes[ProPane.SIDE]?.let { w * it } ?: min(320f, w.value * 0.28f).dp).coerceIn(200.dp, sideMax)
            val monitorsMax = max(120f, (h - 180.dp).value).dp
            val monitors = (ed.proPanes[ProPane.MONITORS]?.let { h * it } ?: (h * 0.45f)).coerceIn(120.dp, monitorsMax)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(monitors)) {
                        SourceMonitor(ed, Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(2.dp).fillMaxHeight().background(Fc.Hairline))
                        ProgramMonitor(ed, Modifier.weight(1f).fillMaxHeight())
                    }
                    Divider(horizontal = true, "Resize monitors") { d ->
                        val now = with(density) { (ed.proPanes[ProPane.MONITORS]?.let { h * it } ?: monitors).coerceIn(120.dp, monitorsMax).toPx() }
                        ed.setPane(ProPane.MONITORS, with(density) { (now + d).toDp() }.coerceIn(120.dp, monitorsMax) / h)
                    }
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        ProToolBar(ed, vertical = true)
                        ProTimelinePanel(ed, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Divider(horizontal = false, "Resize Effect Controls") { d ->
                    val now = with(density) { (ed.proPanes[ProPane.SIDE]?.let { w * it } ?: side).coerceIn(200.dp, sideMax).toPx() }
                    ed.setPane(ProPane.SIDE, with(density) { (now - d).toDp() }.coerceIn(200.dp, sideMax) / w)
                }
                Column(Modifier.width(side).fillMaxHeight()) {
                    PanelTabs(listOf("Effect Controls", "Project"), ed.proSideTab) { ed.proSideTab = it }
                    if (ed.proSideTab == 0) EffectControlsPanel(ed, Modifier.fillMaxWidth().weight(1f))
                    else ProjectPanel(ed, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PortraitLayout(ed: EditorController) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        ProTopBar(ed)
        // the panel tabs on top, then the Program monitor (drag its bottom edge to resize it), then the panel
        PanelTabs(listOf("Timeline", "Source", "Effects", "Project"), ed.proTab) { ed.proTab = it }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val h = maxHeight
            val monitorMax = max(120f, (h - 200.dp).value).dp
            val monitor = (ed.proPanes[ProPane.PORTRAIT_MONITOR]?.let { h * it } ?: (h * 0.4f)).coerceIn(120.dp, monitorMax)
            Column(Modifier.fillMaxSize()) {
                ProgramMonitor(ed, Modifier.fillMaxWidth().height(monitor))
                Divider(horizontal = true, "Resize Program monitor") { d ->
                    val now = with(density) { (ed.proPanes[ProPane.PORTRAIT_MONITOR]?.let { h * it } ?: monitor).coerceIn(120.dp, monitorMax).toPx() }
                    ed.setPane(ProPane.PORTRAIT_MONITOR, with(density) { (now + d).toDp() }.coerceIn(120.dp, monitorMax) / h)
                }
                when (ed.proTab) {
                    0 -> {
                        ProToolBar(ed, vertical = false)
                        ProTimelinePanel(ed, compact = true, modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                    1 -> SourceMonitor(ed, Modifier.fillMaxWidth().weight(1f))
                    2 -> EffectControlsPanel(ed, Modifier.fillMaxWidth().weight(1f))
                    else -> ProjectPanel(ed, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

/** The keys of Pro mode's resizable panes ([EditorController.proPanes]). */
object ProPane {
    /** Phone upright: the Program monitor's share of the space below the tabs. */
    const val PORTRAIT_MONITOR = "portraitMonitor"
    /** Tablet: the monitors' share of the height. */
    const val MONITORS = "monitors"
    /** Tablet: the side panel's share of the width. */
    const val SIDE = "side"
}

/** A drag handle between panels: [horizontal] lies along the width (it resizes heights). */
@Composable
private fun Divider(horizontal: Boolean, description: String, onDrag: (Float) -> Unit) {
    // the gesture outlives a frame: it calls the latest onDrag, which sees the size as it is now
    val drag by rememberUpdatedState(onDrag)
    Box(
        (if (horizontal) Modifier.fillMaxWidth().height(14.dp) else Modifier.width(14.dp).fillMaxHeight())
            .background(Fc.Black)
            .pointerInput(horizontal) {
                if (horizontal) {
                    detectVerticalDragGestures { change, d ->
                        change.consume()
                        drag(d)
                    }
                } else {
                    detectHorizontalDragGestures { change, d ->
                        change.consume()
                        drag(d)
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
        Text(trailing, style = FcType.Caption.copy(fontFeatureSettings = "tnum"), color = Fc.AccentLight, maxLines = 1)
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
    // the buttons wrap onto a second line rather than scroll (the playhead's timecode is above the timeline)
    FlowRow(
        Modifier.fillMaxWidth().background(Fc.Panel).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TransportButton(ed, "markIn", ProGlyphs.MarkIn, "Mark In", "pro_program_mark_in", has) { ed.markIn() }
        TransportButton(ed, "markOut", ProGlyphs.MarkOut, "Mark Out", "pro_program_mark_out", has) { ed.markOut() }
        TransportButton(ed, "goToIn", ProGlyphs.GoToIn, "Go to In", "pro_program_go_to_in", ed.pro?.markIn != null) { ed.goToIn() }
        JumpButton(ProGlyphs.GoToStart, "Go to previous cut; hold for the sequence start", "pro_program_go_to_start", has, { ed.goToCut(next = false) }) { ed.goToSequenceEdge(end = false) }
        TransportButton(ed, "stepBack", Glyphs.StepBack, "Step Back 1 Frame", "editor_previous_frame_button", has) { ed.step(-1) }
        TransportButton(ed, "play", if (ed.playing) Glyphs.Pause else Glyphs.Play, if (ed.playing) "Stop" else "Play", "editor_play_button", has) { ed.togglePlay() }
        TransportButton(ed, "stepForward", Glyphs.StepForward, "Step Forward 1 Frame", "editor_next_frame_button", has) { ed.step(1) }
        JumpButton(ProGlyphs.GoToEnd, "Go to next cut; hold for the sequence end", "pro_program_go_to_end", has, { ed.goToCut(next = true) }) { ed.goToSequenceEdge(end = true) }
        TransportButton(ed, "goToOut", ProGlyphs.GoToOut, "Go to Out", "pro_program_go_to_out", ed.pro?.markOut != null) { ed.goToOut() }
        TransportButton(ed, "addMarker", ProGlyphs.Marker, "Add Marker", "pro_program_add_marker", has) { ed.addMarker() }
        TransportButton(ed, "prevMarker", LucideIcons.ChevronLeft.glyph(), "Go to Previous Marker", "pro_program_prev_marker", ed.pro?.markers?.isNotEmpty() == true) { ed.goToMarker(next = false) }
        TransportButton(ed, "nextMarker", LucideIcons.ChevronRight.glyph(), "Go to Next Marker", "pro_program_next_marker", ed.pro?.markers?.isNotEmpty() == true) { ed.goToMarker(next = true) }
        val inOut = ed.pro?.markIn != null || ed.pro?.markOut != null
        TextButton44(ed, "lift", "Lift", "pro_program_lift", inOut) { ed.liftExtract(extract = false) }
        TextButton44(ed, "extract", "Extract", "pro_program_extract", inOut) { ed.liftExtract(extract = true) }
        TextButton44(ed, "safeMargins", if (ed.safeMargins) "Safe Margins ✓" else "Safe Margins", "pro_program_safe_margins", true) { ed.safeMargins = !ed.safeMargins }
        TextButton44(ed, "proxies", if (ed.useProxies) "Proxies ✓" else "Proxies", "pro_program_toggle_proxies", true) { ed.toggleProxies() }
        val res = when {
            ed.playbackScale >= 1f -> "Full"
            ed.playbackScale >= 0.5f -> "1/2"
            else -> "1/4"
        }
        TextButton44(ed, "resolution", "Resolution: $res", "pro_program_resolution", true) {
            ed.playbackScale = when {
                ed.playbackScale >= 1f -> 0.5f
                ed.playbackScale >= 0.5f -> 0.25f
                else -> 1f
            }
        }
        // a proxy being made: how far along, and how many wait (tap for the Project panel)
        val px = ed.proxies
        val making = px?.making
        if (px != null && making != null) {
            val more = if (px.waiting.isNotEmpty()) " · ${px.waiting.size} more" else ""
            TextButton44(ed, "proxyProgress", "Proxy ${(px.progress * 100).toInt()}%$more", "pro_program_proxy_progress", true) {
                if (ed.proDesktop) ed.proSideTab = 1 else ed.proTab = 3
            }
        }
    }
}

/**
 * Go to start / end: a tap goes to the cut before or after the playhead; holding goes to the
 * sequence's start or end. Its hold jumps
 * rather than explaining it: the Play button's help covers it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JumpButton(glyph: Glyph, description: String, tag: String, enabled: Boolean, onClick: () -> Unit, onHold: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = onHold, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) Fc.Icon else Fc.Disabled, size = 22.dp)
    }
}

@Composable
private fun TransportButton(ed: EditorController, help: String, glyph: Glyph, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    HelpIconButton(ed, glyph, description, help, tag, enabled, onClick = onClick)
}

/** A row of panel tabs (44 dp high). */
@Composable
private fun PanelTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).background(Fc.Panel)) {
        for ((i, label) in labels.withIndex()) {
            val on = selected == i
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .testTag("pro_tab_${label.lowercase().replace(' ', '_')}")
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = FcType.Caption, color = if (on) Fc.Title else Fc.Detail, maxLines = 1)
                if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(2.dp).background(Fc.AccentLight))
            }
        }
    }
}

/**
 * The Source monitor: the clip opened from the Project panel. Scrub it, mark its In and Out, then
 * Insert (,) or Overwrite (.) it into the sequence on the patched tracks.
 */
@Composable
private fun SourceMonitor(ed: EditorController, modifier: Modifier) {
    val src = ed.source
    Column(modifier.background(Fc.Black).testTag("pro_source_monitor")) {
        PanelTitle(if (src != null) "Source: ${src.name}" else "Source", src?.let { TimelineMath.timecode(it.playhead - it.start, it.rateNum, it.rateDen) } ?: "")
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(enabled = src != null, onClickLabel = "Play or stop the source") { ed.toggleSourcePlay() },
            contentAlignment = Alignment.Center,
        ) {
            val pic = ed.sourcePicture
            when {
                src == null -> Text("Open a clip from the Project panel", style = FcType.BodySmall, color = Fc.Detail, textAlign = TextAlign.Center)
                !src.hasVideo -> Text("Sound only", style = FcType.BodySmall, color = Fc.Detail)
                pic != null -> Image(pic, contentDescription = src.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        if (src != null) SourceScrubber(ed, src)
        FlowRow(
            Modifier.fillMaxWidth().background(Fc.Panel).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            val has = src != null
            TransportButton(ed, "sourceMarkIn", ProGlyphs.MarkIn, "Mark In", "pro_source_mark_in", has) { ed.sourceMarkIn() }
            TransportButton(ed, "sourceMarkOut", ProGlyphs.MarkOut, "Mark Out", "pro_source_mark_out", has) { ed.sourceMarkOut() }
            TransportButton(ed, "stepBack", Glyphs.StepBack, "Step Back 1 Frame", "pro_source_step_back", has) { ed.stepSource(-1) }
            TransportButton(ed, "sourcePlay", if (ed.sourcePlaying) Glyphs.Pause else Glyphs.Play, if (ed.sourcePlaying) "Stop" else "Play", "pro_source_play", has) { ed.toggleSourcePlay() }
            TransportButton(ed, "stepForward", Glyphs.StepForward, "Step Forward 1 Frame", "pro_source_step_forward", has) { ed.stepSource(1) }
            TextButton44(ed, "insert", "Insert", "pro_source_insert", has) { ed.sourceEdit(overwrite = false) }
            TextButton44(ed, "overwrite", "Overwrite", "pro_source_overwrite", has) { ed.sourceEdit(overwrite = true) }
            TextButton44(ed, "sourceClear", "Clear In/Out", "pro_source_clear_in_out", src?.markIn != null || src?.markOut != null) { ed.sourceClearInOut() }
            TransportButton(ed, "sourceClose", LucideIcons.X.glyph(), "Close Source", "pro_source_close", has) { ed.closeSource() }
        }
    }
}

/** The Source monitor's time bar: its In to Out, and the playhead; tap or drag to scrub. */
@Composable
private fun SourceScrubber(ed: EditorController, src: io.github.artcraftandroid.filmcraft.core.SourceInfo) {
    val span = max(1L, src.end - src.start)
    val seek by rememberUpdatedState({ x: Float, w: Float -> ed.seekSource(src.start + (x / w * span).toLong()) })
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Fc.Panel)
            .pointerInput(Unit) {
                detectTapGestures { seek(it.x, size.width.toFloat()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    seek(change.position.x, size.width.toFloat())
                }
            }
            .semantics { contentDescription = "Source time bar" },
    ) {
        val w = size.width
        fun x(t: Long) = ((t - src.start).toFloat() / span * w).coerceIn(0f, w)
        drawRect(Fc.Raised, Offset(0f, size.height / 2 - 2.dp.toPx()), Size(w, 4.dp.toPx()))
        val i = src.markIn
        val o = src.markOut
        if (i != null || o != null) {
            val x0 = i?.let(::x) ?: 0f
            val x1 = o?.let(::x) ?: w
            drawRect(Fc.Accent, Offset(x0, size.height / 2 - 4.dp.toPx()), Size(max(2f, x1 - x0), 8.dp.toPx()))
        }
        val px = x(src.playhead)
        drawLine(Fc.AccentLight, Offset(px, 2.dp.toPx()), Offset(px, size.height - 2.dp.toPx()), 2.dp.toPx())
    }
}

@Composable
private fun TextButton44(ed: EditorController, help: String, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .helpClick(ed, help, enabled, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FcType.Caption, color = if (enabled) Fc.Title else Fc.Disabled, maxLines = 1)
    }
}

/** The Project panel: the media imported into the project, with thumbnails; tap one to open it in the Source monitor. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectPanel(ed: EditorController, modifier: Modifier) {
    var importing by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<io.github.artcraftandroid.filmcraft.core.BinItem?>(null) }
    @Suppress("UNUSED_VARIABLE") val version = ed.media.version
    Column(modifier.background(Fc.Layer1).testTag("pro_project_panel")) {
        Row(Modifier.fillMaxWidth().height(44.dp).background(Fc.Panel).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Project: ${ed.project.name}", style = FcType.Caption, color = Fc.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                TextButton44(ed, "import", "Import", "pro_project_import", true) { importing = true }
                DropdownMenu(expanded = importing, onDismissRequest = { importing = false }, containerColor = Fc.Panel) {
                    for ((label, kind) in listOf("Photos and videos" to PickKind.PhotosAndVideos, "Files" to PickKind.FilesVisual, "Sounds" to PickKind.FilesAudio)) {
                        DropdownMenuItem(text = { Text(label, style = FcType.Body, color = Fc.Body) }, onClick = {
                            importing = false
                            ed.importToBin(kind)
                        })
                    }
                }
            }
        }
        val items = ed.bin
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Import media to see it here", style = FcType.BodySmall, color = Fc.Detail)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id.toLong() }) { b ->
                val open = ed.source?.item == b.id
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(if (open) Fc.Raised else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    ed.openSource(b.id)
                                    if (!ed.proDesktop) ed.proTab = 1
                                },
                                onLongClick = { menuFor = b },
                            )
                            .padding(horizontal = 8.dp)
                            .testTag("pro_bin_item_${b.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 72.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)).background(Fc.ThumbPlaceholder), contentAlignment = Alignment.Center) {
                            val thumb = when (b.kind) {
                                BinKind.VIDEO -> ed.media.frame(b.path, min(1.0, b.duration.toDouble() / TPS / 2), 80, still = false)
                                BinKind.IMAGE -> ed.media.frame(b.path, 0.0, 80, still = true)
                                BinKind.GENERATED -> ed.media.engineFrame(b.id.toLong(), 0, 80)
                                else -> null
                            }
                            if (thumb != null) {
                                Image(thumb.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else if (b.kind == BinKind.AUDIO) {
                                GlyphIcon(LucideIcons.Music.glyph(), Fc.Secondary, size = 20.dp)
                            } else if (b.kind == BinKind.MISSING) {
                                GlyphIcon(LucideIcons.TriangleAlert.glyph(), Fc.Negative, size = 20.dp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = FcType.BodySmall, color = Fc.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val kind = when (b.kind) {
                                BinKind.VIDEO -> "Video"
                                BinKind.AUDIO -> "Audio"
                                BinKind.IMAGE -> "Still"
                                BinKind.GENERATED -> "Generated"
                                BinKind.MISSING -> "Missing"
                            }
                            val length = if (b.duration > 0) " · " + TimelineMath.timecode(b.duration, ed.rateNum, ed.rateDen) else ""
                            val used = if (b.uses > 0u) " · used ${b.uses}×" else ""
                            val px = ed.proxies
                            val proxy = when {
                                b.hasProxy -> " · Proxy"
                                px != null && px.making == b.id -> " · Making proxy ${(px.progress * 100).toInt()}%"
                                px != null && b.id in px.waiting -> " · Proxy next"
                                else -> ""
                            }
                            Text(kind + length + used + proxy, style = FcType.Badge, color = Fc.Detail, maxLines = 1)
                        }
                    }
                    DropdownMenu(expanded = menuFor?.id == b.id, onDismissRequest = { menuFor = null }, containerColor = Fc.Panel) {
                        DropdownMenuItem(text = { Text("Open in Source Monitor", style = FcType.Body, color = Fc.Body) }, onClick = {
                            menuFor = null
                            ed.openSource(b.id)
                        })
                        DropdownMenuItem(text = { Text("Insert at Playhead", style = FcType.Body, color = Fc.Body) }, onClick = {
                            menuFor = null
                            ed.openSourceAndEdit(b.id, overwrite = false)
                        })
                        DropdownMenuItem(text = { Text("Overwrite at Playhead", style = FcType.Body, color = Fc.Body) }, onClick = {
                            menuFor = null
                            ed.openSourceAndEdit(b.id, overwrite = true)
                        })
                        if (b.kind == BinKind.VIDEO) {
                            val px = ed.proxies
                            val queued = px != null && (px.making == b.id || b.id in px.waiting)
                            if (b.hasProxy || queued) {
                                DropdownMenuItem(text = { Text(if (b.hasProxy) "Remove Proxy" else "Stop Making Proxy", style = FcType.Body, color = Fc.Body) }, onClick = {
                                    menuFor = null
                                    ed.removeProxy(b.id)
                                })
                            } else {
                                DropdownMenuItem(text = { Text("Create Proxy", style = FcType.Body, color = Fc.Body) }, onClick = {
                                    menuFor = null
                                    ed.createProxy(b.id)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The tool bar: a column beside the timeline on the desktop layout, a row above it on a phone upright. */
@Composable
private fun ProToolBar(ed: EditorController, vertical: Boolean) {
    // every tool shares the bar's length, so all eleven fit without scrolling
    if (vertical) {
        Column(Modifier.width(48.dp).fillMaxHeight().background(Fc.Panel).testTag("pro_tool_bar"), horizontalAlignment = Alignment.CenterHorizontally) {
            for (t in ProTool.entries) ToolButton(ed, t, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().height(44.dp).background(Fc.Panel).testTag("pro_tool_bar"), verticalAlignment = Alignment.CenterVertically) {
            for (t in ProTool.entries) ToolButton(ed, t, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ToolButton(ed: EditorController, t: ProTool, modifier: Modifier) {
    val on = ed.proTool == t
    Box(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) Fc.Accent else Color.Transparent)
            .helpClick(ed, "tool.${t.name}", role = Role.RadioButton) { ed.proTool = t }
            .testTag("pro_tool_${t.name.lowercase()}")
            .semantics {
                contentDescription = "${t.label} tool (${t.key})"
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(toolGlyph(t), if (on) Color.White else Fc.Icon, size = 20.dp)
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
        Key.Comma -> ed.sourceEdit(overwrite = false)
        Key.Period -> ed.sourceEdit(overwrite = true)
        Key.Semicolon -> ed.liftExtract(extract = false)
        Key.Apostrophe -> ed.liftExtract(extract = true)
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
