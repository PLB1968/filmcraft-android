package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import io.github.artcraftandroid.rushcraft.ui.DialogButton
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.Glyph
import io.github.artcraftandroid.rushcraft.ui.GlyphIcon
import io.github.artcraftandroid.rushcraft.ui.Glyphs
import io.github.artcraftandroid.rushcraft.ui.LucideIcons
import io.github.artcraftandroid.rushcraft.ui.glyph

/**
 * The bars a user can set up (Edit ▸ Customise Toolbars, or a long press on a bar's empty
 * part): which buttons each shows and in what order, the same in every project. The Tools bar
 * holds the editing tools; the others any [BarCommand].
 */
enum class ToolbarKind(val title: String, val defaults: List<String>) {
    Transport(
        "Program Monitor",
        // the essentials, on one line; the rest of the monitor's buttons are in its ⋯ menu
        listOf("markIn", "markOut", "goToStart", "stepBack", "play", "stepForward", "goToEnd", "addMarker"),
    ),
    Tools("Tools", ProTool.entries.map { it.name }),
    Timeline("Timeline", listOf("snap", "linked", "addMarker", "addTracks", "zoomFit", "markIn", "markOut")),
    Favourites("Favourites", listOf("titles", "captions", "colour", "editText", "voiceover"));

    /** The Program monitor's own buttons, which its ⋯ menu offers when they're not on the bar. */
    val family: List<String> get() = if (this == Transport) TRANSPORT_FAMILY else emptyList()

    /** [id] is a button this bar can hold. */
    fun knows(id: String): Boolean = if (this == Tools) ProTool.entries.any { it.name == id } else BarCommands.of(id) != null

    /** Every button this bar can hold, in the order the Add list shows them. */
    val choices: List<String> get() = if (this == Tools) ProTool.entries.map { it.name } else BarCommands.all.map { it.id }
}

private val TRANSPORT_FAMILY = listOf(
    "markIn", "markOut", "goToIn", "goToStart", "stepBack", "play", "stepForward", "goToEnd", "goToOut", "addMarker", "prevMarker", "nextMarker",
    "lift", "extract", "safeMargins", "proxies", "render", "resolution",
)

/** Every bar is this high, with icons this size and text in this style. */
internal val BarHeight = 44.dp
internal val BarIcon = 22.dp
internal val BarText = FcType.Caption.copy(fontSize = 14.sp)

/** A hairline between bars. */
@Composable
internal fun BarDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Fc.Hairline))
}

/**
 * A bar's buttons [ids] on one line: icons share the width (each at most a touch target wide),
 * so they fit a phone held upright; with text buttons or many, they scroll instead.
 */
@Composable
internal fun RowScope.CommandSlots(ed: EditorController, bar: ToolbarKind, ids: List<String>) {
    val cmds = ids.mapNotNull { BarCommands.of(it) }
    if (cmds.all { it.glyph != null } && cmds.size <= 9) {
        for (c in cmds) BarCommandButton(ed, c, bar, Modifier.weight(1f).widthIn(max = 52.dp).fillMaxHeight(), size = BarHeight)
    } else {
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (c in cmds) BarCommandButton(ed, c, bar, size = BarHeight)
        }
    }
}

/**
 * A ⋯ button opening the [bar]'s family buttons that aren't on it (their state in their names),
 * then Customise.
 */
@Composable
internal fun BarOverflow(ed: EditorController, bar: ToolbarKind) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(BarHeight).clip(CircleShape).combinedClickableCompat { open = true }.testTag("bar_${bar.name.lowercase()}_more").semantics { contentDescription = "More ${bar.title} buttons" },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(LucideIcons.Ellipsis.glyph(), Fc.Icon, size = BarIcon)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Fc.Panel) {
            val shown = ed.toolbar(bar)
            for (c in bar.family.filter { it !in shown }.mapNotNull { BarCommands.of(it) }) {
                val enabled = c.enabled(ed)
                val name = if (c.glyph == null) c.text(ed) else c.label + if (c.on?.invoke(ed) == true) " ✓" else ""
                DropdownMenuItem(
                    text = { Text(name, style = FcType.Body, color = if (enabled) Fc.Body else Fc.Disabled) },
                    enabled = enabled,
                    onClick = {
                        open = false
                        c.run(ed)
                    },
                )
            }
            // a proxy being made: how far along (tap for the Project panel)
            val px = ed.proxies
            if (px?.making != null) {
                val more = if (px.waiting.isNotEmpty()) " · ${px.waiting.size} more" else ""
                DropdownMenuItem(text = { Text("Making proxy ${(px.progress * 100).toInt()}%$more", style = FcType.Body, color = Fc.Secondary) }, onClick = {
                    open = false
                    if (ed.proDesktop) ed.proSideTab = 1 else ed.proTab = 3
                })
            }
            DropdownMenuItem(text = { Text("Customise…", style = FcType.Body, color = Fc.Body) }, onClick = {
                open = false
                ed.customising = bar
            })
        }
    }
}

/**
 * A button a bar can hold: an icon ([glyph]) or, without one, its [text]. A tap runs [run]; a long
 * press runs [hold] if given, else explains the button ([help], or its [label] and [about]).
 * [menu] makes the tap open a short menu instead; [on] makes it a switch, lit while on.
 */
class BarCommand(
    val id: String,
    val label: String,
    val about: String = "",
    val help: String = "",
    val glyph: ((EditorController) -> Glyph)? = null,
    val text: (EditorController) -> String = { label },
    val enabled: (EditorController) -> Boolean = { true },
    val on: ((EditorController) -> Boolean)? = null,
    val hold: ((EditorController) -> Unit)? = null,
    val menu: ((EditorController) -> List<Pair<String, () -> Unit>>)? = null,
    val run: (EditorController) -> Unit = {},
)

/** Every button the Program Monitor, Timeline and Favourites bars can hold. */
object BarCommands {
    private fun icon(g: Glyph): (EditorController) -> Glyph = { g }
    private val has: (EditorController) -> Boolean = { it.duration > 0L }
    private val picked: (EditorController) -> Boolean = { it.proSelection.isNotEmpty() }

    val all: List<BarCommand> = listOf(
        // the monitor's transport
        BarCommand("markIn", "Mark In", help = "markIn", glyph = icon(ProGlyphs.MarkIn), enabled = has) { it.markIn() },
        BarCommand("markOut", "Mark Out", help = "markOut", glyph = icon(ProGlyphs.MarkOut), enabled = has) { it.markOut() },
        BarCommand("goToIn", "Go to In", help = "goToIn", glyph = icon(ProGlyphs.GoToIn), enabled = { it.pro?.markIn != null }) { it.goToIn() },
        BarCommand(
            "goToStart", "Go to Previous Cut or Marker", "Tap for the cut or marker before the playhead; hold for the sequence start.",
            glyph = icon(ProGlyphs.GoToStart), enabled = has, hold = { it.goToSequenceEdge(end = false) },
        ) { it.goToCut(next = false) },
        BarCommand("stepBack", "Step Back 1 Frame", help = "stepBack", glyph = icon(Glyphs.StepBack), enabled = has) { it.step(-1) },
        BarCommand("play", "Play / Stop", help = "play", glyph = { if (it.playing) Glyphs.Pause else Glyphs.Play }, enabled = has) { it.togglePlay() },
        BarCommand("stepForward", "Step Forward 1 Frame", help = "stepForward", glyph = icon(Glyphs.StepForward), enabled = has) { it.step(1) },
        BarCommand(
            "goToEnd", "Go to Next Cut or Marker", "Tap for the cut or marker after the playhead; hold for the sequence end.",
            glyph = icon(ProGlyphs.GoToEnd), enabled = has, hold = { it.goToSequenceEdge(end = true) },
        ) { it.goToCut(next = true) },
        BarCommand("goToOut", "Go to Out", help = "goToOut", glyph = icon(ProGlyphs.GoToOut), enabled = { it.pro?.markOut != null }) { it.goToOut() },
        BarCommand("addMarker", "Add Marker", help = "addMarker", glyph = icon(ProGlyphs.Marker), enabled = has) { it.addMarker() },
        BarCommand("prevMarker", "Go to Previous Marker", help = "prevMarker", glyph = icon(LucideIcons.ChevronLeft.glyph()), enabled = { it.pro?.markers?.isNotEmpty() == true }) { it.goToMarker(next = false) },
        BarCommand("nextMarker", "Go to Next Marker", help = "nextMarker", glyph = icon(LucideIcons.ChevronRight.glyph()), enabled = { it.pro?.markers?.isNotEmpty() == true }) { it.goToMarker(next = true) },
        BarCommand("lift", "Lift", help = "lift", enabled = { it.pro?.markIn != null || it.pro?.markOut != null }) { it.liftExtract(extract = false) },
        BarCommand("extract", "Extract", help = "extract", enabled = { it.pro?.markIn != null || it.pro?.markOut != null }) { it.liftExtract(extract = true) },
        BarCommand("safeMargins", "Safe Margins", help = "safeMargins", text = { if (it.safeMargins) "Safe Margins ✓" else "Safe Margins" }) { it.safeMargins = !it.safeMargins },
        BarCommand("proxies", "Toggle Proxies", help = "proxies", text = { if (it.useProxies) "Proxies ✓" else "Proxies" }) { it.toggleProxies() },
        BarCommand("render", "Render", help = "render", text = { e -> e.renderProgress?.let { "Rendering ${(it * 100).toInt()}%" } ?: "Render" }, enabled = has) {
            if (it.renderProgress != null) it.stopRender() else it.renderEffects()
        },
        BarCommand(
            "resolution", "Playback Resolution", help = "resolution",
            text = { "Resolution: " + when { it.playbackScale >= 1f -> "Full"; it.playbackScale >= 0.5f -> "1/2"; else -> "1/4" } },
        ) {
            it.playbackScale = when {
                it.playbackScale >= 1f -> 0.5f
                it.playbackScale >= 0.5f -> 0.25f
                else -> 1f
            }
        },
        // the timeline's
        BarCommand("snap", "Snap in Timeline", help = "snap", glyph = icon(ProGlyphs.Magnet), on = { it.pro?.snapping == true }) { it.setSnapping(it.pro?.snapping != true) },
        BarCommand("linked", "Linked Selection", help = "linked", glyph = icon(ProGlyphs.Link), on = { it.pro?.linkedSelection == true }) { it.setLinkedSelection(it.pro?.linkedSelection != true) },
        BarCommand(
            "addTracks", "Add Tracks", help = "addTracks", glyph = icon(LucideIcons.Plus.glyph()),
            menu = { e -> listOf("Add Video Track" to { e.addTracks(1, 0) }, "Add Audio Track" to { e.addTracks(0, 1) }, "Add Video and Audio Track" to { e.addTracks(1, 1) }) },
        ),
        BarCommand("zoomFit", "Zoom to Sequence", help = "zoomFit", glyph = icon(ProGlyphs.Fit)) {
            it.proPxPerSecond = 0f
            it.proScroll = 0L
        },
        BarCommand("razor", "Cut at Playhead", "Cuts the clips under the playhead (the selected ones, else every unlocked track).", glyph = icon(LucideIcons.Scissors.glyph()), enabled = has) { it.cutAtPlayhead() },
        // the menus'
        BarCommand("importMedia", "Import Photos and Videos", "Adds photos and videos to the Project panel.", text = { "Import" }) { it.importToBin(PickKind.PhotosAndVideos) },
        BarCommand("importSounds", "Import Sounds", "Adds music and sounds to the Project panel.", text = { "Sounds" }) { it.importToBin(PickKind.FilesAudio) },
        BarCommand("camera", "Record with Camera", "Records straight onto the timeline at the playhead.", text = { "Camera" }) { it.openCamera() },
        BarCommand("export", "Export", "Makes the video file.", text = { "Export" }, enabled = has) { it.export() },
        BarCommand("history", "Undo History", "Every step to undo; tap one to go back to it.", text = { "History" }, enabled = { it.status?.canUndo == true || it.status?.canRedo == true }) { it.openHistory() },
        BarCommand("editText", "Edit by Text", "Transcribe the sequence, then delete words to cut them, and remove filler words and long pauses.", text = { "Edit by Text" }, enabled = has) { it.openTextEdit() },
        BarCommand("colour", "Colour", "The selected clip's Lumetri colour.", text = { "Colour" }, enabled = picked) { it.openColor() },
        BarCommand("motion", "Motion", "The selected clip's entrance and exit, Ken Burns and picture-in-picture.", text = { "Motion" }, enabled = picked) { it.openMotion() },
        BarCommand("masks", "Masks & Green Screen", "Blur or show only an area, limit an effect to it, or key out a green or blue backdrop.", text = { "Masks" }, enabled = picked) { it.openMasks() },
        BarCommand("aspect", "Aspect Ratio", "The sequence's shape: wide, upright, square…", text = { "Aspect" }) { it.panel = Panel.AspectRatio },
        BarCommand("scenes", "Scene Edit Detection", "Cuts the selected video, else all, at each change of shot.", text = { "Scenes" }, enabled = has) { it.detectScenes() },
        BarCommand("beats", "Beat Markers", "Markers on the selected music's beats.", text = { "Beats" }, enabled = picked) { it.beatMarkers() },
        BarCommand("titles", "Titles", "Lower thirds, titles, end cards and credits at the playhead.", text = { "Titles" }) { it.panel = Panel.Titles },
        BarCommand("captions", "Captions", "Auto captions from the speech, and their style.", text = { "Captions" }, enabled = has) { it.panel = Panel.Captions },
        BarCommand("voiceover", "Voice-over", "Records your voice at the playhead.", text = { "Voice-over" }) { it.panel = Panel.Voiceover },
        BarCommand("tts", "Text to Speech", "Turns typed text into a voice at the playhead.", text = { "Speech" }) { it.panel = Panel.Speech(null) },
        BarCommand("loudness", "Auto-Match Loudness", "Evens out the selected sounds, else all.", text = { "Loudness" }, enabled = has) { it.matchLoudness() },
    )

    private val byId = all.associateBy { it.id }

    fun of(id: String): BarCommand? = byId[id]
}

/** One of a bar's buttons: [size] square for an icon, as wide as its text otherwise. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BarCommandButton(ed: EditorController, c: BarCommand, bar: ToolbarKind, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val enabled = c.enabled(ed)
    val on = c.on?.invoke(ed) == true
    var menuOpen by remember { mutableStateOf(false) }
    val explain = { ed.help = ProHelpText.of(c.help) ?: ProHelp(c.label, c.about) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val glyph = c.glyph
        val base = if (glyph != null) Modifier.size(size).clip(if (c.on != null) RoundedCornerShape(8.dp) else CircleShape) else Modifier.height(size).clip(RoundedCornerShape(8.dp))
        Box(
            base
                .background(if (on) Fc.Accent.copy(alpha = 0.35f) else Color.Transparent)
                .combinedClickable(
                    role = if (c.on != null) Role.Switch else Role.Button,
                    onLongClick = { c.hold?.let { h -> if (enabled) h(ed) } ?: explain() },
                    onClick = {
                        if (!enabled) return@combinedClickable
                        if (c.menu != null) menuOpen = true else c.run(ed)
                    },
                )
                .then(if (glyph == null) Modifier.padding(horizontal = 10.dp) else Modifier)
                .testTag("bar_${bar.name.lowercase()}_${c.id}")
                .semantics {
                    contentDescription = c.label
                    if (c.on != null) selected = on
                },
            contentAlignment = Alignment.Center,
        ) {
            if (glyph != null) {
                GlyphIcon(glyph(ed), when { !enabled -> Fc.Disabled; on -> Fc.AccentLight; else -> Fc.Icon }, size = BarIcon)
            } else {
                Text(c.text(ed), style = BarText, color = if (enabled) Fc.Title else Fc.Disabled, maxLines = 1)
            }
        }
        c.menu?.let { items ->
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Fc.Panel) {
                for ((label, action) in items(ed)) {
                    DropdownMenuItem(text = { Text(label, style = FcType.Body, color = Fc.Body) }, onClick = {
                        menuOpen = false
                        action()
                    })
                }
            }
        }
    }
}

/** A long press on a bar's empty part opens Customise Toolbars on it (its buttons keep their own long press). */
internal fun Modifier.customiseOnHold(ed: EditorController, bar: ToolbarKind): Modifier =
    pointerInput(bar) { detectTapGestures(onLongPress = { ed.customising = bar }) }

/** The user's Favourites, in the top bar after the menus, then a pencil to customise the bars. */
@Composable
internal fun FavouriteButtons(ed: EditorController) {
    for (id in ed.toolbar(ToolbarKind.Favourites)) BarCommands.of(id)?.let { BarCommandButton(ed, it, ToolbarKind.Favourites, size = BarHeight) }
    Box(
        Modifier.size(BarHeight).clip(CircleShape).combinedClickableCompat { ed.customising = ToolbarKind.Favourites }.testTag("pro_favourites_customise").semantics { contentDescription = "Customise Toolbars" },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(LucideIcons.Pencil.glyph(), Fc.Detail, size = 18.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(role = Role.Button, onClick = onClick)

/**
 * Customise Toolbars: for each bar, its buttons in order (drag the handle to move one, minus to
 * take it off) and the buttons it could add. Changes apply at once, in every project.
 */
@Composable
internal fun CustomiseToolbarsDialog(ed: EditorController, start: ToolbarKind) {
    var bar by remember { mutableStateOf(start) }
    Dialog(onDismissRequest = { ed.customising = null }) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(24.dp)).background(Fc.Panel).padding(vertical = 16.dp).testTag("pro_customise_toolbars"),
        ) {
            Text("Customise Toolbars", style = FcType.DialogTitle, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in ToolbarKind.entries) {
                    val sel = k == bar
                    Box(
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) Fc.Accent else Fc.Layer1).combinedClickableCompat { bar = k }.padding(horizontal = 14.dp).testTag("customise_tab_${k.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(k.title, style = FcType.Label, color = if (sel) Color.White else Fc.Body, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ToolbarEditor(ed, bar)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton("Reset ${bar.title}", { ed.resetToolbar(bar) }, filled = false)
                DialogButton("Done", { ed.customising = null }, filled = true)
            }
        }
    }
}

private fun label(bar: ToolbarKind, id: String): String =
    if (bar == ToolbarKind.Tools) ProTool.entries.firstOrNull { it.name == id }?.let { "${it.label} (${it.key})" } ?: id else BarCommands.of(id)?.label ?: id

/** One bar's list: what it shows, in order, then what it could add. */
@Composable
private fun ToolbarEditor(ed: EditorController, bar: ToolbarKind) {
    val density = LocalDensity.current
    val rowH = 52.dp
    val rowPx = with(density) { rowH.toPx() }
    var dragging by remember(bar) { mutableStateOf<String?>(null) }
    var offset by remember(bar) { mutableFloatStateOf(0f) }
    val shown = ed.toolbar(bar)
    SectionLabel("On the bar · drag ⇕ to move")
    if (shown.isEmpty()) {
        Text("Nothing yet: add buttons below.", style = FcType.BodySmall, color = Fc.Detail, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    // keyed by button, so a row keeps its drag while the list reorders under it
    for (id in shown) key(id) {
        val lifted = dragging == id
        Row(
            Modifier
                .fillMaxWidth()
                .height(rowH)
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer { translationY = if (lifted) offset else 0f }
                .background(if (lifted) Fc.Layer1 else Color.Transparent)
                .padding(horizontal = 12.dp)
                .testTag("customise_row_$id"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .pointerInput(bar, id) {
                        detectDragGestures(
                            onDragStart = { dragging = id; offset = 0f },
                            onDragEnd = { dragging = null; offset = 0f },
                            onDragCancel = { dragging = null; offset = 0f },
                        ) { change, d ->
                            change.consume()
                            offset += d.y
                            // past half a row: swap with the neighbour, and carry on from there
                            val now = ed.toolbar(bar)
                            val i = now.indexOf(id)
                            if (offset > rowPx / 2 && i in 0 until now.size - 1) {
                                ed.setToolbar(bar, now.toMutableList().apply { add(i + 1, removeAt(i)) })
                                offset -= rowPx
                            } else if (offset < -rowPx / 2 && i > 0) {
                                ed.setToolbar(bar, now.toMutableList().apply { add(i - 1, removeAt(i)) })
                                offset += rowPx
                            }
                        }
                    }
                    .semantics { contentDescription = "Move ${label(bar, id)}" },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(LucideIcons.ChevronsUpDown.glyph(), Fc.Icon, size = 20.dp)
            }
            Text(label(bar, id), style = FcType.Body, color = Fc.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            // the Selection tool always stays: a bar needs a tool to go back to
            val keep = bar == ToolbarKind.Tools && id == ProTool.Selection.name
            if (!keep) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).combinedClickableCompat { ed.setToolbar(bar, ed.toolbar(bar) - id) }.semantics { contentDescription = "Remove ${label(bar, id)}" },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(LucideIcons.Minus.glyph(), Fc.Icon, size = 20.dp)
                }
            }
        }
    }
    val more = bar.choices.filter { it !in shown }
    if (more.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Add a button")
        for (id in more) {
            Row(
                Modifier.fillMaxWidth().height(rowH).combinedClickableCompat { ed.setToolbar(bar, ed.toolbar(bar) + id) }.padding(horizontal = 12.dp).testTag("customise_add_$id"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    GlyphIcon(LucideIcons.Plus.glyph(), Fc.AccentLight, size = 20.dp)
                }
                Text(label(bar, id), style = FcType.Body, color = Fc.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = FcType.Caption, color = Fc.Detail, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp))
}
