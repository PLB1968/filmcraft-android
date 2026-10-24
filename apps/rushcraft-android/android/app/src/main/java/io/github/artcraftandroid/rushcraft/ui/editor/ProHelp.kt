package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.artcraftandroid.rushcraft.ui.DialogButton
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.Glyph
import io.github.artcraftandroid.rushcraft.ui.GlyphIcon

/** What a Pro mode button is and does, shown when it is long-pressed. */
data class ProHelp(val title: String, val text: String, val shortcut: String = "")

/** The help of every Pro mode button, by key. */
object ProHelpText {
    private val all: Map<String, ProHelp> = mapOf(
        // the tools
        "tool.Selection" to ProHelp("Selection tool", "Tap a clip to select it (its linked sound too, while Linked Selection is on). Drag a selected clip to move it, or drag its edge to trim it; tap its edge to pick it and trim a frame at a time. On a selected sound, drag the yellow volume line up or down (or its dots, the keyframes); long-press a sound to add a keyframe or to duck music under dialogue. Tap a crossfade or fade to edit it in Effect Controls (its type, length, alignment and settings). Long-press a clip, a cut or an empty spot for more.", "V"),
        "tool.TrackSelectForward" to ProHelp("Track Select Forward tool", "Tap to select every clip on that track from the tap onward; long-press to select from there on every track. Drag to move them all together.", "A"),
        "tool.RippleEdit" to ProHelp("Ripple Edit tool", "Drag a clip's edge to trim it; the clips after it move along so no gap opens. Tap an edge to pick it and trim a frame at a time.", "B"),
        "tool.RollingEdit" to ProHelp("Rolling Edit tool", "Drag a cut between two clips to move it: one clip gets longer as the other gets shorter, and nothing else moves. Tap a cut to pick it and roll it a frame at a time.", "N"),
        "tool.RateStretch" to ProHelp("Rate Stretch tool", "Drag a clip's edge to change its length by changing its speed: the same footage plays faster or slower.", "R"),
        "tool.Razor" to ProHelp("Razor tool", "Cuts happen at the playhead: tap a clip to cut it there (its linked sound too), or long-press to cut every track there. Double-tap this button to cut at the playhead with any tool: the selected clip, or every track when no selected clip is under the playhead. The clip before the cut is selected after.", "C, or Ctrl+K to cut at the playhead"),
        "tool.Slip" to ProHelp("Slip tool", "Drag a clip to change which part of its footage it shows. Its place and length on the timeline stay the same.", "Y"),
        "tool.Slide" to ProHelp("Slide tool", "Drag a clip to move it between its neighbours. They get longer or shorter to make room, so the total length stays the same.", "U"),
        "tool.Hand" to ProHelp("Hand tool", "Drag to scroll the timeline without selecting or moving anything.", "H"),
        "tool.Zoom" to ProHelp("Zoom tool", "Tap the timeline to zoom in there; long-press to zoom out. You can also pinch with two fingers.", "Z"),
        "tool.Type" to ProHelp("Type tool", "Tap the timeline or the Program monitor to add a title at that point.", "T"),
        // the timeline bar
        "snap" to ProHelp("Snap in Timeline", "When on, clips, edges and trims stick to nearby cuts, the playhead, In/Out points and markers as you drag.", "S"),
        "linked" to ProHelp("Linked Selection", "When on, selecting a video clip also selects its sound (and the other way round), so they move and trim together.", ""),
        "addMarker" to ProHelp("Add Marker", "Puts a marker at the playhead. Double-tap a marker on the ruler to name it, give it a colour or a comment, or delete it.", "M"),
        "addTracks" to ProHelp("Add Tracks", "Adds a video track above the others, an audio track below them, or one of each.", ""),
        "zoomFit" to ProHelp("Zoom to Sequence", "Zooms the timeline so the whole sequence fits across the screen.", "\\"),
        "markIn" to ProHelp("Mark In", "Sets the In point at the playhead: where the part you're marking starts. Lift, Extract and Insert/Overwrite use it.", "I"),
        "markOut" to ProHelp("Mark Out", "Sets the Out point at the playhead: the last frame of the part you're marking.", "O"),
        // the track headers
        "patch" to ProHelp("Source patching", "The lit V or A shows where Insert and Overwrite put the Source monitor's picture or sound. Tap another track's box to send it there instead.", ""),
        "target" to ProHelp("Track targeting", "A highlighted track name means the track is targeted: Lift, Extract, Paste and moving between edits act on it. Tap to turn it on or off.", ""),
        "lock" to ProHelp("Lock track", "A locked track can't be edited: its clips can't be moved, trimmed, cut or covered.", ""),
        "output" to ProHelp("Toggle track output", "The eye shows or hides the track's picture in the Program monitor and in exports. The clips stay on the timeline.", ""),
        "mute" to ProHelp("Mute track", "Silences the track's sound in playback and exports.", ""),
        "solo" to ProHelp("Solo track", "Plays only the soloed audio tracks and silences the rest.", ""),
        "sync" to ProHelp("Sync lock", "When on, the track keeps in step with insert and ripple edits on other tracks, so its clips don't drift out of sync.", ""),
        // the monitors
        "nudge" to ProHelp("Trim by frames", "Moves the picked edit point (the yellow bracket) earlier or later by one frame, or five. Pick one by tapping a clip's end with the Ripple or Rolling Edit tool, or a selected clip's end with the Selection tool; the end you last dragged stays picked too.", "Ctrl+← / Ctrl+→ (Shift: five frames)"),
        "trimDone" to ProHelp("Done trimming", "Lets go of the picked edit point; the Program monitor shows the sequence again.", ""),
        "goToIn" to ProHelp("Go to In", "Moves the playhead to the In point.", ""),
        "goToOut" to ProHelp("Go to Out", "Moves the playhead to the Out point.", ""),
        "stepBack" to ProHelp("Step back one frame", "Moves the playhead back one frame.", "←"),
        "stepForward" to ProHelp("Step forward one frame", "Moves the playhead forward one frame.", "→"),
        "play" to ProHelp("Play / Stop", "Plays from the playhead, or stops. The buttons on either side of the frame steps go to the cut before or after the playhead; hold one to go to the start or end of the sequence.", "Space (L plays, K stops, J plays backwards)"),
        "prevMarker" to ProHelp("Go to previous marker", "Moves the playhead to the marker before it.", ""),
        "nextMarker" to ProHelp("Go to next marker", "Moves the playhead to the marker after it.", ""),
        "lift" to ProHelp("Lift", "Removes everything between In and Out on the targeted tracks and leaves a gap.", ";"),
        "extract" to ProHelp("Extract", "Removes everything between In and Out on the targeted tracks and closes the gap.", "'"),
        "safeMargins" to ProHelp("Safe Margins", "Shows the action-safe (90%) and title-safe (80%) frames, to keep important things away from the edges.", ""),
        "render" to ProHelp("Render effects", "Renders the stretches whose effects, titles or transitions are too heavy to play smoothly (the yellow and red parts of the bar along the top of the timeline); playback then shows the render and they turn green. Within In and Out when they are set. With Settings › Render effects in the background, this happens on its own a few seconds after each edit. Tap again while it renders to stop.", "Enter"),
        "proxies" to ProHelp("Toggle Proxies", "When on, the monitors play the small proxy copies of your videos, so playback is smooth. Exports always use the originals. Proxies of HD and 4K videos are made in the background (Settings › Proxies); long-press a video in the Project panel to make or remove one.", ""),
        "proxyProgress" to ProHelp("Making proxies", "A proxy (a small copy for smooth playback) is being made: how far along it is, and how many videos wait their turn. It plays smoothly once it's done. Tap to see them in the Project panel.", ""),
        "resolution" to ProHelp("Playback resolution", "Draws the picture at Full, 1/2 or 1/4 size while playing and scrubbing, so it runs smoothly. It's sharp again when you stop.", ""),
        "sourceMarkIn" to ProHelp("Mark source In", "Sets where the part of the source clip you want starts.", "I"),
        "sourceMarkOut" to ProHelp("Mark source Out", "Sets the last frame of the part of the source clip you want.", "O"),
        "sourcePlay" to ProHelp("Play source", "Plays the source clip (picture only), or stops.", ""),
        "insert" to ProHelp("Insert", "Puts the source clip's In to Out into the timeline at the sequence In (or the playhead) on the patched tracks, pushing later clips along.", ","),
        "overwrite" to ProHelp("Overwrite", "Puts the source clip's In to Out into the timeline at the sequence In (or the playhead) on the patched tracks, covering what's there.", "."),
        "sourceClear" to ProHelp("Clear source In/Out", "Removes the source clip's In and Out points, so all of it is used.", ""),
        "sourceClose" to ProHelp("Close source", "Closes the clip in the Source monitor.", ""),
        "import" to ProHelp("Import", "Adds photos, videos or sounds to the Project panel without putting them on the timeline. Tap one to open it in the Source monitor.", ""),
        // the top bar
        "home" to ProHelp("Home", "Saves and goes back to your projects.", ""),
        "undo" to ProHelp("Undo", "Takes back the last change.", "Ctrl+Z"),
        "redo" to ProHelp("Redo", "Puts back the last change you undid.", "Ctrl+Shift+Z"),
        "export" to ProHelp("Export", "Makes a video file of the sequence and saves it to your phone.", ""),
        // Effect Controls
        "transitionShorter" to ProHelp("One frame shorter", "Makes the transition a frame shorter, keeping its alignment on the cut.", ""),
        "transitionLonger" to ProHelp("One frame longer", "Makes the transition a frame longer, keeping its alignment on the cut. It can't run past the clips it joins.", ""),
        "transitionAlign" to ProHelp("Alignment", "Where the transition sits on its cut: centred on it, starting at it (it runs into the next clip) or ending at it (it runs into the clip before).", ""),
        "transitionReset" to ProHelp("Reset", "Puts the transition's own settings back to its type's defaults (its length and place stay).", ""),
        "fx" to ProHelp("Effect on/off (fx)", "Turns the effect off or on without losing its settings.", ""),
        "removeEffect" to ProHelp("Remove effect", "Takes the effect off the clip.", ""),
        "stopwatch" to ProHelp("Toggle animation (stopwatch)", "Turns keyframes on for this setting: a keyframe goes at the playhead, and every later change adds one. Turn it off to remove the keyframes and keep the current value.", ""),
        "kfPrev" to ProHelp("Go to previous keyframe", "Moves the playhead to this setting's keyframe before it.", ""),
        "kfToggle" to ProHelp("Add / remove keyframe", "Adds a keyframe at the playhead, or removes the one that's there.", ""),
        "kfNext" to ProHelp("Go to next keyframe", "Moves the playhead to this setting's keyframe after it.", ""),
    )

    fun of(key: String): ProHelp? = all[key]
}

/**
 * A Pro mode button's touch: a tap does [onClick] (when [enabled]), a double tap [onDoubleClick]
 * if given; a long press explains the button ([help], see [ProHelpText]), even while it is
 * disabled.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.helpClick(
    ed: EditorController,
    help: String,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier =
    combinedClickable(
        role = role,
        onLongClick = { ProHelpText.of(help)?.let { ed.help = it } },
        onDoubleClick = onDoubleClick?.let { d -> { if (enabled) d() } },
        onClick = { if (enabled) onClick() },
    )

/** A round icon button with long-press help (Pro mode's FcIconButton). */
@Composable
internal fun HelpIconButton(ed: EditorController, glyph: Glyph, description: String, help: String, tag: String, enabled: Boolean = true, size: Dp = 44.dp, iconSize: Dp = 22.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .helpClick(ed, help, enabled, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) Fc.Icon else Fc.Disabled, size = iconSize)
    }
}

/** The help dialog: the button's name, what it does and its shortcut. */
@Composable
internal fun HelpDialog(help: ProHelp, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Fc.Panel)
                .padding(24.dp)
                .testTag("pro_help_dialog"),
        ) {
            Text(help.title, style = FcType.DialogTitle, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(help.text, style = FcType.Body, color = Fc.Body)
            if (help.shortcut.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("Keyboard: ${help.shortcut}", style = FcType.BodySmall, color = Fc.Secondary)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton("Got it", onDismiss, filled = true)
            }
        }
    }
}
