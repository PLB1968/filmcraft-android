package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcDialog
import io.github.artcraftandroid.rushcraft.ui.FcSize
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.GlyphIcon
import io.github.artcraftandroid.rushcraft.ui.LucideIcons
import io.github.artcraftandroid.rushcraft.ui.PanelAction
import io.github.artcraftandroid.rushcraft.ui.ToolPanel
import io.github.artcraftandroid.rushcraft.ui.glyph

/**
 * Sequence ▸ Markers: every marker in time order with its colour, time, name and comment. Tap
 * one to go there; the pencil edits it, the bin deletes it; Delete All clears them (one undo).
 */
@Composable
fun MarkersPanel(ed: EditorController, close: () -> Unit, modifier: Modifier = Modifier) {
    val markers = ed.pro?.markers.orEmpty()
    var clearing by remember { mutableStateOf(false) }
    ToolPanel(
        "Markers", close, modifier, tag = "markers_panel",
        actions = {
            PanelAction(LucideIcons.Trash2.glyph(), "Delete all markers", { clearing = true }, enabled = markers.isNotEmpty(), tag = "markers_delete_all")
        },
    ) {
        if (markers.isEmpty()) {
            Text(
                "No markers yet. Add one at the playhead with the marker button (M on a keyboard).",
                style = FcType.Body, color = Fc.Detail, modifier = Modifier.padding(horizontal = FcSize.PanelPadding),
            )
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            val here = ed.markerAtPlayhead?.id
            for (m in markers) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(if (m.id == here) Fc.Raised else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Button, onClickLabel = "Go to this marker") { ed.seek(m.start, draft = false) }
                        .padding(start = FcSize.PanelPadding, end = 4.dp)
                        .testTag("marker_row_${m.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(markerColor(m.color)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            TimelineMath.timecode(m.start, ed.rateNum, ed.rateDen) + "  " + m.name.ifBlank { "Marker" },
                            style = FcType.Body, color = Fc.Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (m.comment.isNotBlank()) Text(m.comment, style = FcType.BodySmall, color = Fc.Detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RowIcon(LucideIcons.Pencil.glyph(), "Edit marker") { ed.markerEditing = m.id }
                    RowIcon(LucideIcons.Trash2.glyph(), "Delete marker") { ed.deleteMarker(m.id) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (clearing) {
        FcDialog(
            "Delete all ${markers.size} markers?",
            "Delete All",
            onConfirm = {
                clearing = false
                ed.deleteAllMarkers()
            },
            onDismiss = { clearing = false },
            destructive = true,
        ) {
            Text("Undo brings them back.", style = FcType.Body, color = Fc.Body)
        }
    }
}

@Composable
private fun RowIcon(glyph: io.github.artcraftandroid.rushcraft.ui.Glyph, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Fc.Icon, size = 20.dp)
    }
}
